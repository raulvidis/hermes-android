package com.hermesandroid.bridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RES-1 regression tests: caller-supplied durations are clamped, not trusted.
 *
 * `/wait` used its `timeoutMs` verbatim, so `{"timeoutMs": 2147483647}` started a
 * ~24.8-day loop of full accessibility-tree walks on the main thread — the
 * relay's own 30 s HTTP timeout hides it from the caller while the phone keeps
 * working. `/screen_record` clamped its maximum but not its minimum, so a
 * negative duration reached `Thread.sleep` and threw there.
 *
 * The ceiling for `/wait` (120 s) is deliberately far above both the relay's 30 s
 * response timeout and any wait the Python tools issue, so this is a DoS bound
 * rather than a behaviour change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommandDispatcherClampTest {

    @Test
    fun `wait timeout defaults to five seconds`() {
        assertEquals(5_000, CommandDispatcher.clampWaitTimeoutMs(null))
    }

    @Test
    fun `wait timeout keeps ordinary values untouched`() {
        listOf(0, 1, 500, 5_000, 30_000, 120_000).forEach {
            assertEquals(it, CommandDispatcher.clampWaitTimeoutMs(it))
        }
    }

    @Test
    fun `wait timeout is capped at two minutes`() {
        listOf(120_001, 600_000, 60_000_000, Int.MAX_VALUE).forEach {
            assertEquals(
                "$it must be capped",
                CommandDispatcher.MAX_WAIT_TIMEOUT_MS,
                CommandDispatcher.clampWaitTimeoutMs(it)
            )
        }
    }

    @Test
    fun `wait timeout cannot go negative`() {
        listOf(-1, -5_000, Int.MIN_VALUE).forEach {
            assertEquals(0, CommandDispatcher.clampWaitTimeoutMs(it))
        }
    }

    @Test
    fun `the wait ceiling is above the relay response timeout`() {
        // tools/android_relay.py:_RESPONSE_TIMEOUT is 30 s. A clamp below that
        // would truncate legitimate waits; far above it changes nothing observable.
        assertTrue(CommandDispatcher.MAX_WAIT_TIMEOUT_MS > 30_000)
    }

    @Test
    fun `record duration defaults to five seconds`() {
        assertEquals(5_000L, CommandDispatcher.clampRecordDurationMs(null))
    }

    @Test
    fun `record duration keeps ordinary values untouched`() {
        // Sub-second clips included: the floor must not silently lengthen them.
        listOf(1L, 250L, 1_000L, 5_000L, 15_000L, 30_000L).forEach {
            assertEquals(it, CommandDispatcher.clampRecordDurationMs(it))
        }
    }

    @Test
    fun `record duration is capped at thirty seconds`() {
        listOf(30_001L, 600_000L, Long.MAX_VALUE).forEach {
            assertEquals(
                CommandDispatcher.MAX_RECORD_DURATION_MS,
                CommandDispatcher.clampRecordDurationMs(it)
            )
        }
    }

    @Test
    fun `record duration has a floor so Thread_sleep cannot throw`() {
        listOf(0L, -1L, -30_000L, Long.MIN_VALUE).forEach {
            assertEquals(
                "$it must be raised to the floor",
                CommandDispatcher.MIN_RECORD_DURATION_MS,
                CommandDispatcher.clampRecordDurationMs(it)
            )
        }
        assertEquals(1L, CommandDispatcher.MIN_RECORD_DURATION_MS)
    }
}
