package com.hermesandroid.bridge.client

import android.content.Context
import android.os.Looper
import com.hermesandroid.bridge.auth.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * `disconnect()` (review F9), **and** must be cancellable by the user (review
 * F11).
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
 * F9's remedy — an object-level `revivalScope` nobody cancels — over-corrected:
 * `disconnect()` sets `shouldReconnect = false` and tears the session down, but
 * the pending reconnect survived it, so a user who tapped **Disconnect** (or
 * **Connect** against a different relay) was silently overridden 30 s later by a
 * dial they had already cancelled. The scope stays; the *job* is now tracked and
 * cancelled by `disconnect()` and by `connect()`, and the coroutine re-checks
 * `shouldReconnect` before dialling. Clearing its own handle before calling the
 * connector is what keeps F9's intent: the reconnect is never cancelled by the
 * `connect()` it makes.
 *
 * These tests drive virtual time through the `revivalScope` seam. The URL is
 * loopback so nothing leaves the machine and no device is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelayClientRevivalReconnectTest {

    private lateinit var context: Context
    private lateinit var originalConnector: (String, String) -> Unit

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        RelayClient.init(context)
        RelayClient.onStatusChanged = null
        RelayClient.serverUrl = null
        // Saved rather than reconstructed: the production default closes over
        // the private doConnect, and one test here needs exactly that.
        originalConnector = RelayClient.connector
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
        RelayClient.connector = originalConnector
        RelayClient.revivalConnector = { url, code -> RelayClient.connect(url, code) }
        RelayClient.revivalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    /**
     * F11: the reconnect is *wanted* when the watchdog schedules it, and
     * unwanted from then on if the user says so. `reconnectDelayed()` is reached
     * only from the retry-budget-exhausted path, which has just set
     * `shouldReconnect = false` — so scheduling re-arms it, and the guard inside
     * the coroutine is what a later `disconnect()` trips.
     */
    @Test
    fun `the scheduled reconnect fires when the retry budget was already exhausted`() {
        val testScope = TestScope()
        RelayClient.revivalScope = testScope
        val connected = mutableListOf<Pair<String, String>>()
        RelayClient.revivalConnector = { url, code -> connected += url to code }
        RelayClient.serverUrl = "http://127.0.0.1:1"
        setPairingCode("CODE123")

        // Exhaustion (or any earlier teardown) leaves the flag cleared; the
        // watchdog then schedules the attempt it wants to make.
        RelayClient.disconnect()
        RelayClient.reconnectDelayed()
        testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS)
        testScope.runCurrent()

        assertEquals(
            listOf("http://127.0.0.1:1" to "CODE123"),
            connected,
        )
    }

    @Test
    fun `a user disconnect cancels a pending revival reconnect`() {
        // The bug F9's fix introduced: disconnect() set shouldReconnect = false
        // and tore the session down, but the coroutine on revivalScope was
        // nobody's to cancel, so it dialled 30 s later anyway — resurrecting a
        // connection the user had just refused.
        val testScope = TestScope()
        RelayClient.revivalScope = testScope
        val connected = mutableListOf<Pair<String, String>>()
        RelayClient.revivalConnector = { url, code -> connected += url to code }
        RelayClient.serverUrl = "http://127.0.0.1:1"
        setPairingCode("CODE123")

        RelayClient.reconnectDelayed()
        RelayClient.disconnect()
        testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS * 2)
        testScope.runCurrent()

        assertEquals("the user disconnected; nothing may dial", emptyList<Pair<String, String>>(), connected)
    }

    @Test
    fun `a newer manual connect supersedes a pending revival reconnect`() {
        val testScope = TestScope()
        RelayClient.revivalScope = testScope
        val revived = mutableListOf<Pair<String, String>>()
        val dialled = mutableListOf<Pair<String, String>>()
        RelayClient.revivalConnector = { url, code -> revived += url to code }
        RelayClient.connector = { url, code -> dialled += url to code }
        RelayClient.serverUrl = "http://127.0.0.1:1"
        setPairingCode("CODE123")

        RelayClient.reconnectDelayed()
        // The user taps Connect against a different relay while the watchdog's
        // 30 s timer is still running.
        RelayClient.connect("http://127.0.0.1:2", "CODE123")
        testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS * 2)
        testScope.runCurrent()

        assertEquals("the manual connect is the only dial", listOf("http://127.0.0.1:2" to "CODE123"), dialled)
        assertEquals(
            "the stale reconnect must not override the newer manual connect",
            emptyList<Pair<String, String>>(),
            revived,
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
        // F9's case, kept: not just "the connector was called", but that the
        // real connect() — which cancels its own caller's job on the way in —
        // still runs doConnect to completion. Observed through the status
        // callback doConnect publishes, with no network assertion.
        val testScope = TestScope()
        RelayClient.revivalScope = testScope
        val statuses = mutableListOf<String>()
        RelayClient.onStatusChanged = { _, message -> statuses += message }
        RelayClient.serverUrl = "http://127.0.0.1:1"
        setPairingCode("CODE123")

        RelayClient.reconnectDelayed()
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

    /**
     * Review F32: `shouldReconnect` alone is not the guard. The coroutine wakes from
     * its delay and then has to wait for the monitor, and `disconnect()`/`connect()`
     * are both `@Synchronized` — so a user who disconnects and reconnects while it
     * was waiting leaves the flag **true**, because their own `connect()` set it. A
     * flag-only guard then dials the old URL over the top of the connection the user
     * just made. The job has to check itself as well: still uncancelled, and still
     * the one `reconnectDelayed()` last scheduled.
     */
    @Test
    fun `a user reconnect leaves the flag armed but the stale job must not dial`() {
        val testScope = TestScope()
        RelayClient.revivalScope = testScope
        RelayClient.serverUrl = "http://127.0.0.1:1"
        setPairingCode("CODE123")

        RelayClient.reconnectDelayed()
        val stale = RelayClient.revivalReconnectJob
        assertNotNull("reconnectDelayed must publish its handle", stale)
        assertTrue("nothing has overtaken it yet", RelayClient.revivalReconnectMayDial(stale))

        // The user taps Connect against a different relay: this cancels the pending
        // job *and* re-arms shouldReconnect, which is why the flag cannot be the
        // guard on its own.
        RelayClient.connect("http://127.0.0.1:2", "CODE123")

        assertFalse(
            "the flag is armed again by the user's own connect, but this job was " +
                "cancelled and superseded",
            RelayClient.revivalReconnectMayDial(stale),
        )
    }

    @Test
    fun `a job cancelled while it waited for the monitor must not dial`() {
        RelayClient.connect("http://127.0.0.1:1", "CODE123")  // arms shouldReconnect
        val cancelled = Job().also { it.cancel() }
        RelayClient.revivalReconnectJob = cancelled

        assertFalse(RelayClient.revivalReconnectMayDial(cancelled))
    }

    @Test
    fun `a job superseded by a newer watchdog fire must not dial`() {
        RelayClient.connect("http://127.0.0.1:1", "CODE123")
        val older = Job()
        val newer = Job()
        RelayClient.revivalReconnectJob = newer

        assertFalse(RelayClient.revivalReconnectMayDial(older))
        assertTrue(RelayClient.revivalReconnectMayDial(newer))
        older.cancel()
        newer.cancel()
    }

    @Test
    fun `a cleared handle must not dial`() {
        RelayClient.connect("http://127.0.0.1:1", "CODE123")
        val self = Job()

        RelayClient.disconnect()   // clears the handle and the flag
        RelayClient.revivalReconnectJob = null

        assertFalse(RelayClient.revivalReconnectMayDial(self))
        self.cancel()
    }

    @Test
    fun `a null job must not dial`() {
        RelayClient.connect("http://127.0.0.1:1", "CODE123")

        assertFalse(RelayClient.revivalReconnectMayDial(null))
    }

    /**
     * Review F25: the guard and the dial have to be one step. The coroutine checked
     * `shouldReconnect` and cleared its own handle inside `synchronized`, then
     * called the connector *after* releasing the monitor — so a `disconnect()`
     * landing in that gap found no job left to cancel and a flag nothing would
     * re-read, and the reconnect went ahead anyway. That is F11 again, one
     * instruction narrower. `disconnect()` and `connect()` are both `@Synchronized`,
     * so dialling under the same monitor is what makes the window close; the lock is
     * reentrant, which is also how F9's "must not cancel itself" survives.
     */
    @Test
    fun `the reconnect is dialled under the monitor so a disconnect cannot slip in`() {
        val testScope = TestScope()
        RelayClient.revivalScope = testScope
        var heldLock = false
        RelayClient.revivalConnector = { _, _ -> heldLock = Thread.holdsLock(RelayClient) }
        RelayClient.serverUrl = "http://127.0.0.1:1"
        setPairingCode("CODE123")

        RelayClient.reconnectDelayed()
        testScope.advanceTimeBy(RelayClient.REVIVAL_RECONNECT_DELAY_MS)
        testScope.runCurrent()

        assertTrue(
            "the connector ran without the RelayClient monitor, so disconnect() " +
                "can land between the guard and the dial and be ignored",
            heldLock,
        )
    }
}
