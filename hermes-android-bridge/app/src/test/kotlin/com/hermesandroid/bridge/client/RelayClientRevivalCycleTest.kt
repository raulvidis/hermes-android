package com.hermesandroid.bridge.client

import android.content.Context
import android.os.Looper
import com.hermesandroid.bridge.auth.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The revival watchdog must be able to reach its threshold on an unattended
 * device (ported from fix/review-relay-client c9c4f1f).
 *
 * The failure counter only advances when a retry cycle exhausts, and exhaustion
 * was terminal: nothing started the next cycle, so with the switch on and the
 * stack dead the watchdog sat at 1/3 until a human tapped Connect — which is
 * exactly the situation the watchdog exists for. While it is armed, a counting
 * (Wait) decision now schedules the next cycle itself, on the same cancellable
 * post-revival reconnect a fire uses.
 *
 * The relay is a loopback MockWebServer answering 503 (an outage, not a
 * credential problem); the post-revival delay runs on a [TestScope].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelayClientRevivalCycleTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var testScope: TestScope
    private val statuses = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        context = RuntimeEnvironment.getApplication()
        RelayClient.onStatusChanged = null
        RelayClient.disconnect()
        resetPairingManager()
        PairingManager.init(context)
        RelayClient.init(context)
        prefs().edit().clear().apply()
        RelayClient.onStatusChanged = { _, message -> statuses += message }
        RelayClient.backoffDelay = { }
        testScope = TestScope()
        RelayClient.revivalScope = testScope
    }

    @After
    fun tearDown() {
        RelayClient.onStatusChanged = null
        RelayClient.disconnect()
        prefs().edit().clear().apply()
        resetPairingManager()
        RelayClient.backoffDelay = { delay(it) }
        RelayClient.revivalConnector = { url, code -> RelayClient.connect(url, code) }
        RelayClient.revivalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runCatching { server.shutdown() }
    }

    private fun prefs() = context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)

    private fun resetPairingManager() {
        for (name in listOf("cachedCode", "prefs")) {
            val field = PairingManager::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(PairingManager, null)
        }
    }

    private fun enableRevival(enabled: Boolean = true) {
        prefs().edit().putBoolean("termux_revival_enabled", enabled).commit()
    }

    private fun enqueue(code: Int, times: Int) {
        repeat(times) { server.enqueue(MockResponse().setResponseCode(code)) }
    }

    private fun awaitStatus(fragment: String, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (statuses.any { it.contains(fragment) }) return
            Thread.sleep(20)
        }
        fail("expected a status containing \"$fragment\"; saw: ${statuses.joinToString()}")
    }

    /** Wait for a scheduled post-revival reconnect other than [previous]. */
    private fun awaitScheduledReconnect(previous: Job?, timeoutMs: Long = 10_000): Job {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val job = RelayClient.revivalReconnectJob
            if (job != null && job !== previous) return job
            Thread.sleep(20)
        }
        fail("no post-revival reconnect was scheduled; saw: ${statuses.joinToString()}")
        error("unreachable")
    }

    private fun connect() = RelayClient.connect("http://localhost:${server.port}", PairingManager.getCode())

    @Test
    fun `an armed watchdog schedules the next cycle after an exhausted one`() {
        enableRevival()
        enqueue(503, 6)
        val dialled = CopyOnWriteArrayList<String>()
        RelayClient.revivalConnector = { url, _ -> dialled += url }

        connect()
        awaitScheduledReconnect(previous = null)
        awaitStatus("revival watchdog 1/3")

        testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS)
        testScope.runCurrent()

        assertEquals(listOf("http://localhost:${server.port}"), dialled.toList())
    }

    @Test
    fun `unattended cycles reach the threshold and fire the Termux intent`() {
        enableRevival()
        // Three exhausted cycles of 1 + 5 attempts each, no human in the loop.
        enqueue(503, 6 * RevivalPolicy.DEFAULT_MAX_FIRES)

        connect()
        var job: Job? = null
        repeat(2) {
            job = awaitScheduledReconnect(previous = job)
            testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS)
            testScope.runCurrent()
        }
        awaitStatus("firing Termux revival (1/")

        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull("the third exhausted cycle must start RUN_COMMAND", started)
        assertEquals(RelayClient.TERMUX_RUN_COMMAND_ACTION, started!!.action)
        // And the relay is not left dark after the fire either.
        awaitScheduledReconnect(previous = job)
    }

    @Test
    fun `a disabled watchdog keeps the terminal give-up`() {
        enableRevival(false)
        enqueue(503, 6)

        connect()
        awaitStatus("Failed to connect after 5 attempts")
        Thread.sleep(300)

        assertNull(RelayClient.revivalReconnectJob)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `an auth failure does not start another cycle`() {
        enableRevival()
        enqueue(401, 6)

        connect()
        awaitStatus("Failed to connect after 5 attempts")
        Thread.sleep(300)

        assertNull(RelayClient.revivalReconnectJob)
        assertTrue(statuses.none { it.contains("revival watchdog") })
    }

    @Test
    fun `a user disconnect stops the cycle`() {
        enableRevival()
        enqueue(503, 6)
        val dialled = CopyOnWriteArrayList<String>()
        RelayClient.revivalConnector = { url, _ -> dialled += url }

        connect()
        awaitScheduledReconnect(previous = null)
        RelayClient.disconnect()
        testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS)
        testScope.runCurrent()

        assertTrue("disconnect must cancel the next cycle", dialled.isEmpty())
    }
}
