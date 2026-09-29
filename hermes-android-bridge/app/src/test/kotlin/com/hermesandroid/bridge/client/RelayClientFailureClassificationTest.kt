package com.hermesandroid.bridge.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `lastFailureHttpCode` must describe **one attempt** (review F12).
 *
 * `maybeFireTermuxRevival` turns it into `authFailure`: a 401/403 means "this is a
 * credential problem, restarting Termux cannot fix it", so the watchdog refuses to
 * spend its fire budget. A stale value therefore does not mislabel a log line, it
 * switches the recovery path off — and the branch that reads it is reached from
 * `onClosed`, which writes nothing at all.
 *
 * Two ways it went stale:
 *  1. nothing reset it per attempt, so a code recorded by an earlier attempt — or
 *     an earlier `connect()` cycle, since `connect()`/`disconnect()` never touched
 *     it either — was still sitting there when exhaustion was reached through a
 *     clean close;
 *  2. `onFailure` checked `myGeneration != generation` and *then* assigned, outside
 *     the monitor. `onFailure` runs on an OkHttp callback thread, so a socket that
 *     had already been superseded could pass the check, be descheduled, and write
 *     after the current attempt had connected — attaching an old credential failure
 *     to a live session.
 *
 * Both are closed by resetting at the top of `doConnect` and routing the write
 * through the guarded, `@Synchronized` [RelayClient.recordFailureHttpCode].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelayClientFailureClassificationTest {

    private lateinit var context: Context
    private lateinit var originalConnector: (String, String) -> Unit

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        RelayClient.init(context)
        RelayClient.onStatusChanged = null
        RelayClient.serverUrl = null
        originalConnector = RelayClient.connector
        // No socket is ever opened: these tests are about the classification
        // field, and the loopback URL keeps a stray dial on the machine.
        RelayClient.connector = { _, _ -> }
        RelayClient.lastFailureHttpCode = 0
    }

    @After
    fun tearDown() {
        RelayClient.disconnect()
        RelayClient.connector = originalConnector
        RelayClient.onStatusChanged = null
        RelayClient.serverUrl = null
        RelayClient.lastFailureHttpCode = 0
        RelayClient.revivalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @Test
    fun `the current attempt records its own http status`() {
        val generation = RelayClient.currentGeneration()

        RelayClient.recordFailureHttpCode(generation, 401)

        assertEquals(401, RelayClient.lastFailureHttpCode)
    }

    @Test
    fun `a superseded attempt cannot classify the current one`() {
        val stale = RelayClient.currentGeneration()
        // The user taps Connect: disconnect() and doConnect() each advance the
        // counter, so the socket that was in flight is no longer the current one.
        RelayClient.connect("ws://127.0.0.1:1", "CODE123")
        assertNotEquals("connect must advance the attempt counter", stale, RelayClient.currentGeneration())

        RelayClient.recordFailureHttpCode(stale, 401)

        assertEquals(
            "a 401 from a socket that was already superseded must not be read as " +
                "this attempt's credential failure",
            0,
            RelayClient.lastFailureHttpCode,
        )
    }

    @Test
    fun `a transport failure clears a previous credential failure`() {
        val generation = RelayClient.currentGeneration()
        RelayClient.recordFailureHttpCode(generation, 403)
        assertEquals(403, RelayClient.lastFailureHttpCode)

        // Same attempt, second callback: `response` is null for a transport-level
        // failure, so the code is 0 and must overwrite the 403 rather than leave
        // the watchdog thinking this is a credential problem.
        RelayClient.recordFailureHttpCode(generation, 0)

        assertEquals(0, RelayClient.lastFailureHttpCode)
    }

    @Test
    fun `a new attempt starts unclassified so onClosed cannot inherit a stale code`() {
        RelayClient.recordFailureHttpCode(RelayClient.currentGeneration(), 401)
        assertEquals(401, RelayClient.lastFailureHttpCode)

        RelayClient.connect("ws://127.0.0.1:1", "CODE123")

        assertEquals(
            "exhaustion reached via onClosed writes nothing, so the field must " +
                "already be reset by the attempt that is running",
            0,
            RelayClient.lastFailureHttpCode,
        )
    }
}
