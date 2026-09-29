package com.hermesandroid.bridge.client

import android.content.Context
import android.os.Looper
import com.hermesandroid.bridge.auth.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The post-revival delayed reconnect must run on a scope that survives
 * `disconnect()` (review F9).
 *
 * `fireTermuxRestart()` calls `reconnectDelayed()`, which used to launch on the
 * per-connection `scope`. When that coroutine fired 30 s later it called
 * `connect(url, code)`, whose **first statement** is `disconnect()` — and
 * `disconnect()` does `scope?.cancel(); scope = null`, cancelling the very scope
 * the caller was running on. It worked only because nothing between
 * `disconnect()` and the end of `doConnect()` suspends, so cancelling the
 * enclosing job could not interrupt synchronous code. The first suspension point
 * added to either function turns "fires but never reconnects" back into the live
 * bug the call site's comment claims was fixed.
 *
 * These tests drive virtual time through the `revivalScope` seam. The URL is
 * loopback so nothing leaves the machine and no device is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelayClientRevivalReconnectTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        RelayClient.init(context)
        RelayClient.onStatusChanged = null
        RelayClient.serverUrl = null
        setPairingCode(null)
    }

    /**
     * AUTH-1: `RelayClient.pairingCode` is a read-only view of PairingManager, so
     * a test that needs a saved code — or none — has to put it there instead of
     * assigning it. The manager caches the code in a static that outlives the
     * Robolectric application, so clearing also drops `prefs`, which is the
     * fallback `getCode()` reads when the cache is empty.
     */
    private fun setPairingCode(code: String?) {
        pairingField("cachedCode").set(PairingManager, code)
        if (code == null) pairingField("prefs").set(PairingManager, null)
    }

    private fun pairingField(name: String) =
        PairingManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    @After
    fun tearDown() {
        RelayClient.disconnect()
        RelayClient.onStatusChanged = null
        RelayClient.serverUrl = null
        setPairingCode(null)
        RelayClient.revivalConnector = { url, code -> RelayClient.connect(url, code) }
        RelayClient.revivalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @Test
    fun `the scheduled reconnect still runs after disconnect`() {
        val testScope = TestScope()
        RelayClient.revivalScope = testScope
        val connected = mutableListOf<Pair<String, String>>()
        RelayClient.revivalConnector = { url, code -> connected += url to code }
        RelayClient.serverUrl = "http://127.0.0.1:1"
        setPairingCode("CODE123")

        RelayClient.reconnectDelayed()
        // The relay is dead when the watchdog fires, and tearing it down is the
        // first thing the reconnect itself does.
        RelayClient.disconnect()
        testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS)
        testScope.runCurrent()

        assertEquals(
            listOf("http://127.0.0.1:1" to "CODE123"),
            connected,
        )
    }

    @Test
    fun `disconnect does not cancel the revival scope`() {
        val testScope = TestScope()
        RelayClient.revivalScope = testScope

        RelayClient.disconnect()

        assertTrue(
            "the revival scope must outlive the per-connection scope",
            testScope.coroutineContext.isActive,
        )
    }

    @Test
    fun `connect from inside the revival coroutine completes doConnect`() {
        // The review's case: not just "the connector was called", but that the
        // real connect() — which cancels its own caller's scope on the way in —
        // still runs doConnect to completion. Observed through the status
        // callback doConnect publishes, with no network assertion.
        val testScope = TestScope()
        RelayClient.revivalScope = testScope
        val statuses = mutableListOf<String>()
        RelayClient.onStatusChanged = { _, message -> statuses += message }
        RelayClient.serverUrl = "http://127.0.0.1:1"
        setPairingCode("CODE123")

        RelayClient.reconnectDelayed()
        RelayClient.disconnect()
        testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS)
        testScope.runCurrent()
        // notifyStatus posts to the main looper.
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "doConnect never ran; statuses seen: $statuses",
            statuses.any { it.startsWith("Connecting to ws://127.0.0.1:1/ws") },
        )
    }

    @Test
    fun `no reconnect is scheduled without a saved server and code`() {
        val testScope = TestScope()
        RelayClient.revivalScope = testScope
        var calls = 0
        RelayClient.revivalConnector = { _, _ -> calls++ }

        RelayClient.reconnectDelayed()
        testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS * 2)
        testScope.runCurrent()

        assertEquals("nothing saved, nothing to reconnect to", 0, calls)
    }
}
