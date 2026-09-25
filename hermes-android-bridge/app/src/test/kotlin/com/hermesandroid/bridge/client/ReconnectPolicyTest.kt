package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `attempts are exhausted after maxRetries`() {
        val policy = ReconnectPolicy(maxRetries = 5)

        repeat(5) {
            assertFalse("attempt ${it + 1} should be allowed", policy.isExhausted)
            policy.nextBackoffMs()
        }
        assertTrue("budget should be spent after 5 attempts", policy.isExhausted)
    }

    /**
     * Regression: a bad server address used to retry forever. Every failed
     * attempt fires another failure callback, which previously restarted the
     * retry loop with a fresh counter, so the cap was never reached.
     */
    @Test
    fun `repeated failure callbacks never revive an exhausted budget`() {
        val policy = ReconnectPolicy(maxRetries = 5)

        var attemptsMade = 0
        // Drive the SAME decision function production uses, not a copy of the
        // loop, so this regression is pinned to the real code path.
        repeat(100) {
            val action = decideReconnect(
                shouldReconnect = true,
                hasScope = true,
                reconnectPending = false,
                isExhausted = policy.isExhausted,
            )
            if (action == ReconnectAction.ATTEMPT) {
                policy.nextBackoffMs()
                attemptsMade++
            }
        }

        assertEquals("must stop after the retry cap", 5, attemptsMade)
        assertTrue(policy.isExhausted)
    }

    @Test
    fun `backoff grows exponentially and clamps to the max`() {
        val policy = ReconnectPolicy(maxRetries = 10, maxBackoffMs = 30_000L, baseBackoffMs = 1_000L)

        assertEquals(1_000L, policy.nextBackoffMs())
        assertEquals(2_000L, policy.nextBackoffMs())
        assertEquals(4_000L, policy.nextBackoffMs())
        assertEquals(8_000L, policy.nextBackoffMs())
        assertEquals(16_000L, policy.nextBackoffMs())
        assertEquals("clamped", 30_000L, policy.nextBackoffMs())
        assertEquals("stays clamped", 30_000L, policy.nextBackoffMs())
    }

    @Test
    fun `reset restores the full budget after a successful connect`() {
        val policy = ReconnectPolicy(maxRetries = 3)

        repeat(3) { policy.nextBackoffMs() }
        assertTrue(policy.isExhausted)

        policy.reset()

        assertFalse("a successful connect should restore retries", policy.isExhausted)
        assertEquals(0, policy.attempts)
        assertEquals("backoff restarts from the base delay", 1_000L, policy.nextBackoffMs())
    }

    /**
     * Regression: resetting on every `onOpen` reopened the infinite loop for a
     * relay that accepts the socket and immediately drops it (wrong pairing
     * code). Only a session that stayed up refills the budget.
     */
    @Test
    fun `a flapping server cannot refill the budget forever`() {
        val policy = ReconnectPolicy(maxRetries = 5, stableSessionMs = 60_000L)

        var attemptsMade = 0
        repeat(100) {
            val action = decideReconnect(
                shouldReconnect = true,
                hasScope = true,
                reconnectPending = false,
                isExhausted = policy.isExhausted,
            )
            if (action == ReconnectAction.ATTEMPT) {
                policy.nextBackoffMs()
                attemptsMade++
                // Connection opens, then dies 250ms later — never stable.
                policy.onSessionEnded(250L)
            }
        }

        assertEquals("short-lived sessions must not refill retries", 5, attemptsMade)
        assertTrue(policy.isExhausted)
    }

    @Test
    fun `a stable session refills the budget`() {
        val policy = ReconnectPolicy(maxRetries = 5, stableSessionMs = 60_000L)

        repeat(4) { policy.nextBackoffMs() }
        policy.onSessionEnded(90_000L) // an hour-long session dropping is not a failure streak

        assertEquals(0, policy.attempts)
        assertFalse(policy.isExhausted)
    }

    @Test
    fun `session exactly at the stability threshold counts as stable`() {
        val policy = ReconnectPolicy(maxRetries = 5, stableSessionMs = 60_000L)

        repeat(3) { policy.nextBackoffMs() }
        policy.onSessionEnded(60_000L)

        assertEquals(0, policy.attempts)
    }

    @Test
    fun `backoff never overflows into a negative delay`() {
        val policy = ReconnectPolicy(maxRetries = 1_000, maxBackoffMs = 30_000L)

        repeat(200) {
            val backoff = policy.nextBackoffMs()
            assertTrue("backoff must stay positive, got $backoff", backoff in 1L..30_000L)
        }
    }
}
