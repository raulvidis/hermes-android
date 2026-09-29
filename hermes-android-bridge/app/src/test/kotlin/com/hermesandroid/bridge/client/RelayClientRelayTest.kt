package com.hermesandroid.bridge.client

import android.content.Context
import android.os.BatteryManager
import android.os.Looper
import com.google.gson.JsonParser
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.server.CommandDispatcher
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBatteryManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * RelayClient exercised against a real WebSocket peer (MockWebServer speaks
 * the same OkHttp the app ships). The relay is the DEFAULT transport, so
 * these tests pin the contract the Python relay depends on:
 *
 *  - AUTH HANDSHAKE: the pairing code rides the Authorization Bearer header,
 *    never the URL (query strings land verbatim in reverse-proxy access logs).
 *  - MESSAGE FRAMING: commands in, request_id-stamped JSON results out.
 *  - ERROR HANDLING: malformed or unaddressable commands answer with an
 *    error envelope instead of killing the connection; an unreachable relay
 *    exhausts the retry budget instead of looping forever.
 *  - STATE TRANSITIONS: connecting → connected → closed/reconnecting →
 *    terminal failure, and user disconnect cancelling a pending reconnect.
 *
 * Reconnect backoff is driven through RelayClient.backoffDelay (test seam)
 * so the retry cap can be observed without waiting out real seconds; the
 * backoff VALUES themselves are covered by ReconnectPolicyTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelayClientRelayTest {

    private lateinit var server: MockWebServer
    private val statuses = CopyOnWriteArrayList<Pair<Boolean, String>>()
    private val backoffs = CopyOnWriteArrayList<Long>()

    /** The code PairingManager holds for this test — the one the relay is shown. */
    private var savedCode: String = ""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // RelayClient is a process-wide singleton: neutralise whatever state
        // an earlier test left behind before wiring this test's observers.
        RelayClient.onStatusChanged = null
        RelayClient.disconnect()
        resetPairingManager()
        // AUTH-1: PairingManager is the single source of the code, and
        // scheduleReconnect/autoConnect read it back — so model production and
        // give it one, otherwise a reconnect has no credentials to dial with.
        PairingManager.init(RuntimeEnvironment.getApplication())
        savedCode = PairingManager.getCode()
        RelayClient.init(RuntimeEnvironment.getApplication())
        RelayClient.onStatusChanged = { connected, message -> statuses += connected to message }
        RelayClient.backoffDelay = { backoffs += it }
    }

    /**
     * AUTH-1 hygiene: PairingManager is a process-wide object whose `prefs` and
     * `cachedCode` statics outlive the Robolectric application that created
     * them, so a code left behind by another test class would still be sitting
     * there when this class starts. Clearing both keeps `setUp` the only place
     * that decides what the manager holds.
     */
    private fun resetPairingManager() {
        for (name in listOf("cachedCode", "prefs")) {
            val field = PairingManager::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(PairingManager, null)
        }
    }

    @After
    fun tearDown() {
        RelayClient.onStatusChanged = null
        RelayClient.disconnect()
        resetPairingManager()
        // Restore the production seam: the next test class must wait for real
        // time like production does.
        RelayClient.backoffDelay = { delay(it) }
        CommandDispatcher.batteryContext = { BridgeApplication.instance }
        runCatching { server.shutdown() } // cleanup must never mask a test's own result
    }

    // ---------------------------------------------------------------- helpers

    /** The relay's side of the WebSocket: records opens, messages, closes. */
    private class RelayPeer : WebSocketListener() {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val messages = LinkedBlockingQueue<String>()
        @Volatile var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.add(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // A well-behaved peer (the real relay) acknowledges the close
            // handshake; without the echo the socket lingers half-closed.
            webSocket.close(1000, null)
            closed.countDown()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.countDown()
        }

        fun awaitOpen(timeoutMs: Long = 10_000): Boolean = opened.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun nextMessage(timeoutMs: Long = 10_000): String? = messages.poll(timeoutMs, TimeUnit.MILLISECONDS)

        fun send(text: String) {
            socket?.send(text)
        }
    }

    /** Flushes status callbacks (posted to the Robolectric main looper). */
    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitStatus(fragment: String, timeoutMs: Long = 10_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idleMainLooper()
            statuses.firstOrNull { it.second.contains(fragment) }?.let { return it.second }
            Thread.sleep(25)
        }
        fail("expected a status containing \"$fragment\"; statuses seen: ${statuses.joinToString { it.second }}")
        error("unreachable")
    }

    /** Polls until [condition] holds; the client-side flag can land a beat
     *  after the server-side onOpen callback that unblocked the test. */
    private fun awaitCondition(desc: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail(desc)
    }

    private fun connectToRelay(code: String = savedCode) {
        RelayClient.connect("http://localhost:${server.port}", code)
    }

    // ------------------------------------------------------- auth handshake

    @Test
    fun `handshake authenticates with a Bearer header and never puts the code in the URL`() {
        val relay = RelayPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay))

        connectToRelay()

        assertTrue("relay should see the WebSocket open", relay.awaitOpen())
        awaitCondition("client must report itself connected after the handshake") { RelayClient.isConnected }
        awaitStatus("Connected to")

        val upgrade = server.takeRequest(10, TimeUnit.SECONDS)
        assertNotNull("the upgrade request should have been recorded", upgrade)
        assertEquals("must dial the relay's /ws route", "/ws", upgrade!!.path)
        assertEquals("Bearer $savedCode", upgrade.getHeader("Authorization"))
        // The pairing code must ride the header, never the URL — query
        // strings end up verbatim in reverse-proxy access logs.
        assertFalse("token leaked into the request line: ${upgrade.requestLine}", upgrade.requestLine.contains(savedCode))
    }

    // ------------------------------------------------------ message framing

    @Test
    fun `commands round-trip with their request id and the relay auth flag`() {
        val relay = RelayPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay))
        connectToRelay()
        assertTrue(relay.awaitOpen())

        relay.send("""{"request_id":"r-1","method":"GET","path":"/ping","params":{},"body":{}}""")

        val reply = relay.nextMessage()
        assertNotNull("client must answer the command", reply)
        val json = JsonParser.parseString(reply).asJsonObject
        assertEquals("the answer must echo the request id", "r-1", json.get("request_id").asString)
        assertEquals(200, json.get("status").asInt)
        val result = json.getAsJsonObject("result")
        assertEquals("ok", result.get("status").asString)
        // The relay connection authenticates at handshake time, so commands
        // are dispatched as authenticated — this flag is what /ping reports.
        assertEquals(true, result.get("authenticated").asBoolean)
    }

    @Test
    fun `a command without a request id answers 500 instead of hanging the relay`() {
        val relay = RelayPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay))
        connectToRelay()
        assertTrue(relay.awaitOpen())

        relay.send("""{"method":"GET","path":"/ping","params":{},"body":{}}""")

        val json = JsonParser.parseString(relay.nextMessage()).asJsonObject
        assertEquals(500, json.get("status").asInt)
        assertEquals(
            "Command is missing request_id",
            json.getAsJsonObject("result").get("error").asString,
        )
    }

    @Test
    fun `an unknown command answers 404 with the method and path`() {
        val relay = RelayPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay))
        connectToRelay()
        assertTrue(relay.awaitOpen())

        relay.send("""{"request_id":"r-2","method":"DELETE","path":"/no_such_route"}""")

        val json = JsonParser.parseString(relay.nextMessage()).asJsonObject
        assertEquals("r-2", json.get("request_id").asString)
        assertEquals(404, json.get("status").asInt)
        assertEquals(
            "Unknown command: DELETE /no_such_route",
            json.getAsJsonObject("result").get("error").asString,
        )
    }

    @Test
    fun `garbage input is survived, not answered, and the connection keeps working`() {
        val relay = RelayPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay))
        connectToRelay()
        assertTrue(relay.awaitOpen())

        relay.send("this is not json {{{")
        assertNull(
            "no reply is possible: the request id cannot be recovered",
            relay.nextMessage(500),
        )

        // The error path must not have torn the socket down.
        relay.send("""{"request_id":"r-3","method":"GET","path":"/ping","params":{},"body":{}}""")
        val json = JsonParser.parseString(relay.nextMessage()).asJsonObject
        assertEquals("r-3", json.get("request_id").asString)
        assertEquals(200, json.get("status").asInt)
    }

    @Test
    fun `GET battery is served to the relay transport`() {
        // 92b4204: /battery was documented and served locally but missing
        // from the RELAY's allowlist — reachable over USB/LAN, refused over
        // the default transport, silently. CommandDispatcher's route table
        // is what the relay dispatches through, so pin the battery route on
        // this side of the wire too.
        val app = RuntimeEnvironment.getApplication()
        CommandDispatcher.batteryContext = { app }
        val shadowBattery = Shadow.extract<ShadowBatteryManager>(
            app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager,
        )
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 87)
        shadowBattery.setIsCharging(true)

        val relay = RelayPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay))
        connectToRelay()
        assertTrue(relay.awaitOpen())

        relay.send("""{"request_id":"r-4","method":"GET","path":"/battery","params":{},"body":{}}""")

        val json = JsonParser.parseString(relay.nextMessage()).asJsonObject
        assertEquals(200, json.get("status").asInt)
        val result = json.getAsJsonObject("result")
        assertEquals(87, result.get("batteryPercentage").asInt)
        assertEquals(true, result.get("charging").asBoolean)
    }

    // ---------------------------------------------------- state transitions

    @Test
    fun `a dropped session reconnects after one backoff step`() {
        val relay1 = RelayPeer()
        val relay2 = RelayPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay1))
        server.enqueue(MockResponse().withWebSocketUpgrade(relay2))

        connectToRelay()
        assertTrue(relay1.awaitOpen())
        awaitStatus("Connected to")

        // The relay drops the session well inside the 60s stability bar, so
        // this close must NOT refill the reconnect budget.
        relay1.socket?.close(1000, "relay restarting")

        assertTrue("client must open the second attempt", relay2.awaitOpen())
        awaitStatus("Reconnecting in 1s")
        assertEquals("exactly one backoff step must be consumed", listOf(1000L), backoffs.toList())
        awaitCondition("client must report itself connected after reconnecting") { RelayClient.isConnected }
    }

    @Test
    fun `user disconnect closes the relay connection`() {
        val relay = RelayPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay))
        connectToRelay()
        assertTrue(relay.awaitOpen())
        awaitStatus("Connected to")

        RelayClient.disconnect()

        assertTrue("server side must observe the close", relay.closed.await(5, TimeUnit.SECONDS))
        assertFalse(RelayClient.isConnected)
        awaitStatus("Disconnected")
    }

    // -------------------------------------------------- reconnect cap/backoff

    @Test
    fun `an unreachable relay stops after the retry cap instead of looping forever`() {
        // The relay refuses the handshake (a 401 is what a wrong or rotated
        // code produces). More rejections than the budget could ever consume.
        repeat(8) { server.enqueue(MockResponse().setResponseCode(401)) }

        connectToRelay()

        awaitStatus("Failed to connect after 5 attempts")

        // 1 initial attempt + 5 budgeted retries; the 6th failure is terminal.
        assertEquals(6, server.requestCount)
        assertEquals(
            "backoff doubles from 1s and stays under the 30s cap",
            listOf(1000L, 2000L, 4000L, 8000L, 16000L),
            backoffs.toList(),
        )
        assertFalse(RelayClient.isConnected)

        // Terminal means terminal: nothing else fires after exhaustion.
        Thread.sleep(700)
        idleMainLooper()
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `disconnect cancels a reconnect still waiting out its backoff`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val gate = CompletableDeferred<Unit>()
        RelayClient.backoffDelay = { backoffs += it; gate.await() }

        connectToRelay()
        awaitStatus("Reconnecting in 1s") // the reconnect is scheduled and parked

        RelayClient.disconnect() // user-initiated: must cancel that attempt
        gate.complete(Unit) // release the park; nothing must come of it
        Thread.sleep(700)
        idleMainLooper()

        assertEquals("only the initial connection attempt may happen", 1, server.requestCount)
        assertFalse(RelayClient.isConnected)
    }

    @Test
    fun `autoConnect reconnects using previously saved credentials`() {
        val relay = RelayPeer()
        server.enqueue(MockResponse().withWebSocketUpgrade(relay))

        // AUTH-1: the saved code lives in PairingManager, which is the single
        // source autoConnect() reads — RelayClient keeps no copy of its own.
        // Rotating here also proves autoConnect dials with the code the manager
        // holds *now*, not one captured at connect() time.
        val rotatedCode = PairingManager.regenerateCode()

        RelayClient.serverUrl = "http://localhost:${server.port}"
        RelayClient.autoConnect()

        assertTrue(relay.awaitOpen())
        awaitCondition("client must report itself connected after autoConnect") { RelayClient.isConnected }
        val upgrade = server.takeRequest(10, TimeUnit.SECONDS)
        assertEquals("Bearer $rotatedCode", upgrade!!.getHeader("Authorization"))
    }
}
