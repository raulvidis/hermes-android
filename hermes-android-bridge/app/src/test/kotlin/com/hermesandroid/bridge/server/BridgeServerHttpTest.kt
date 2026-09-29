package com.hermesandroid.bridge.server

import android.content.Context
import android.os.BatteryManager
import com.google.gson.JsonParser
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import com.hermesandroid.bridge.notification.NotificationStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBatteryManager
import java.io.File
import java.net.ServerSocket

/**
 * BridgeServer/ BridgeRouter exercised over real HTTP: the actual Netty
 * engine is started on an ephemeral port and driven with OkHttp, the same
 * way the Python agent drives it over USB/LAN.
 *
 * The bridge binds 0.0.0.0 and grants full device control once authenticated,
 * so the interceptor's contracts are security-critical:
 *  - AUTH ENFORCEMENT: everything but /ping requires a valid Bearer pairing
 *    code; wrong/missing tokens get 401; a brute-forcing IP gets locked out
 *    with 429 (AuthRateLimiter);
 *  - ROUTE REGISTRATION: /ping, the dedicated /mic_file download route, and
 *    the catch-all into CommandDispatcher all answer;
 *  - ALLOWLIST BEHAVIOUR: unknown routes are refused with 404, malformed
 *    bodies with 400 — and documented routes like /battery are actually
 *    reachable (the 92b4204 gap class).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BridgeServerHttpTest {

    private lateinit var app: Context
    private lateinit var client: OkHttpClient
    private lateinit var validToken: String
    private var port: Int = 0

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        PairingManager.init(app)
        validToken = "Bearer " + PairingManager.getCode()
        // NotificationStore is another process-wide singleton: earlier test
        // classes in this JVM leave entries behind, and /notifications counts
        // them.
        NotificationStore.clear()
        client = OkHttpClient()
        // BridgeServer and its throttle are process-wide singletons: clear
        // whatever an earlier test left behind.
        BridgeServer.stop()
        BridgeServer.resetAuthThrottle()
        startBridge()
    }

    @After
    fun tearDown() {
        BridgeServer.stop()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        CommandDispatcher.batteryContext = { BridgeApplication.instance }
        micFileContext = { BridgeApplication.instance }
        runCatching { MicrophoneRecordingFiles.directory(app).listFiles()?.forEach { it.delete() } }
    }

    // ---------------------------------------------------------------- helpers

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startBridge() {
        repeat(3) {
            port = freePort()
            try {
                BridgeServer.start(port)
                awaitServerReady()
                return
            } catch (expected: Exception) {
                BridgeServer.stop()
            }
        }
        fail("could not start BridgeServer on any port")
    }

    private fun awaitServerReady() {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val up = runCatching {
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/ping").build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (up) return
            Thread.sleep(50)
        }
        fail("BridgeServer did not become ready on port $port")
    }

    private fun call(method: String, path: String, token: String? = null, body: String? = null): Response {
        val builder = Request.Builder()
            .url("http://127.0.0.1:$port$path")
            .method(method, body?.toRequestBody(null))
        token?.let { builder.header("Authorization", it) }
        return client.newCall(builder.build()).execute()
    }

    /** True when a request to `port` completes at all (server still bound). */
    private fun portAnswers(p: Int): Boolean = runCatching {
        client.newCall(Request.Builder().url("http://127.0.0.1:$p/ping").build())
            .execute().use { true }
    }.getOrDefault(false)

    private fun bodyJson(response: Response) =
        JsonParser.parseString(response.body!!.string()).asJsonObject

    // ------------------------------------------------------------- /ping auth

    @Test
    fun `ping answers without a token and reports itself unauthenticated`() {
        call("GET", "/ping").use { response ->
            assertEquals(200, response.code)
            val json = bodyJson(response)
            assertEquals("ok", json.get("status").asString)
            assertEquals(false, json.get("authenticated").asBoolean)
            assertEquals(false, json.get("accessibilityService").asBoolean)
        }
    }

    @Test
    fun `ping with a valid pairing code reports authenticated`() {
        call("GET", "/ping", token = validToken).use { response ->
            assertEquals(200, response.code)
            assertEquals(true, bodyJson(response).get("authenticated").asBoolean)
        }
    }

    // ------------------------------------------------------- auth enforcement

    @Test
    fun `a protected route rejects a missing token`() {
        call("GET", "/notifications").use { response ->
            assertEquals(401, response.code)
            assertEquals("Invalid or missing pairing code", bodyJson(response).get("error").asString)
        }
    }

    @Test
    fun `a protected route rejects a wrong or bare token`() {
        call("GET", "/notifications", token = "Bearer WRONG1").use { assertEquals(401, it.code) }
        // A token without the documented "Bearer " prefix is refused too.
        call("GET", "/notifications", token = "PAIRINGXYZ").use { assertEquals(401, it.code) }
    }

    @Test
    fun `a valid token reaches a protected route`() {
        call("GET", "/notifications", token = validToken).use { response ->
            assertEquals(200, response.code)
            val json = bodyJson(response)
            assertEquals(0, json.get("count").asInt)
            assertEquals(false, json.get("listenerActive").asBoolean)
        }
    }

    @Test
    fun `repeated failed auths lock the source IP out even with a valid token`() {
        // 5 failures (AuthRateLimiter default maxAttempts) each get a plain
        // 401; the block takes effect on the next request.
        repeat(5) {
            call("GET", "/notifications", token = "Bearer WRONG1").use { assertEquals(401, it.code) }
        }

        call("GET", "/notifications", token = validToken).use { response ->
            // The throttle precedes the token check: a locked-out client is
            // refused even with the right code, so brute force cannot keep
            // guessing the 6-char code from a blocked IP.
            assertEquals(429, response.code)
            assertEquals(
                "Too many failed authentication attempts. Try again later.",
                bodyJson(response).get("error").asString,
            )
        }
    }

    // ------------------------------------------------------ route registration

    @Test
    fun `unknown routes are refused as unknown commands`() {
        call("GET", "/definitely_not_a_route", token = validToken).use { response ->
            assertEquals(404, response.code)
            assertEquals(
                "Unknown command: GET /definitely_not_a_route",
                bodyJson(response).get("error").asString,
            )
        }
    }

    @Test
    fun `a malformed JSON body is a 400 before dispatch`() {
        call("POST", "/tap", token = validToken, body = "not json at all").use { response ->
            assertEquals(400, response.code)
            assertTrue(
                bodyJson(response).get("error").asString.startsWith("Invalid JSON body"),
            )
        }
    }

    @Test
    fun `battery is served over the local HTTP transport`() {
        // The 92b4204 gap: a route can be documented and dispatched yet still
        // be unreachable on a transport. Pin /battery on the HTTP side (the
        // relay side is pinned in RelayClientRelayTest).
        CommandDispatcher.batteryContext = { app }
        val shadowBattery = Shadow.extract<ShadowBatteryManager>(
            app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager,
        )
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 87)
        shadowBattery.setIsCharging(true)

        call("GET", "/battery", token = validToken).use { response ->
            assertEquals(200, response.code)
            val json = bodyJson(response)
            assertEquals(87, json.get("batteryPercentage").asInt)
            assertEquals(true, json.get("charging").asBoolean)
        }
    }

    @Test
    fun `mic_file is served by its dedicated route`() {
        micFileContext = { app }
        val directory = MicrophoneRecordingFiles.directory(app)
        val wav = "RIFF\u0000\u0000\u0000\u0000WAVEjunk".toByteArray()
        File(directory, "recording_20260102_120000_000.wav").writeBytes(wav)

        // The dedicated route is registered BEFORE the catch-all, so this
        // reaches the file download, not the dispatcher's 404.
        call("GET", "/mic_file?name=recording_20260102_120000_000.wav", token = validToken).use { response ->
            assertEquals(200, response.code)
            assertEquals("no-store", response.header("Cache-Control"))
            assertTrue(
                "Content-Disposition must name the file",
                response.header("Content-Disposition")
                    ?.contains("recording_20260102_120000_000.wav") == true,
            )
            assertArrayEquals(wav, response.body!!.bytes())
        }

        // The route's own not-found answer, distinct from "Unknown command".
        call("GET", "/mic_file?name=recording_19990101_000000_000.wav", token = validToken).use { response ->
            assertEquals(404, response.code)
            assertEquals("Recording not found", bodyJson(response).get("error").asString)
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun `a second start is a no-op while the server is running`() {
        val otherPort = freePort()
        BridgeServer.start(otherPort)

        call("GET", "/ping").use { assertEquals(200, it.code) } // original still serves
        assertFalse("a second start() must not bind another port", portAnswers(otherPort))
    }

    @Test
    fun `stop closes the port and a fresh start brings the server back`() {
        BridgeServer.stop()
        assertFalse("after stop the port must be closed", portAnswers(port))

        startBridge()
        call("GET", "/ping").use { assertEquals(200, it.code) }
    }
}
