package com.hermesandroid.bridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRateLimiterTest {

    @Test
    fun `allows requests until the failure threshold is reached`() {
        var t = 1000L
        val rl = AuthRateLimiter(maxAttempts = 3, windowMs = 100, blockMs = 200) { t }

        rl.recordFailure("1.2.3.4")
        rl.recordFailure("1.2.3.4")
        assertFalse("2 failures should not block", rl.isBlocked("1.2.3.4"))

        rl.recordFailure("1.2.3.4") // 3rd in-window failure -> blocked
        assertTrue("3rd failure should block", rl.isBlocked("1.2.3.4"))
    }

    @Test
    fun `block expires after blockMs`() {
        var t = 1000L
        val rl = AuthRateLimiter(maxAttempts = 2, windowMs = 100, blockMs = 200) { t }

        repeat(2) { rl.recordFailure("10.0.0.1") }
        assertTrue(rl.isBlocked("10.0.0.1"))

        t += 201 // past the block window
        assertFalse("block should expire", rl.isBlocked("10.0.0.1"))
    }

    @Test
    fun `failures outside the sliding window do not count`() {
        var t = 1000L
        val rl = AuthRateLimiter(maxAttempts = 3, windowMs = 100, blockMs = 200) { t }

        rl.recordFailure("9.9.9.9") // at t=1000
        t += 101 // the first failure is now outside the 100ms window
        rl.recordFailure("9.9.9.9")
        rl.recordFailure("9.9.9.9") // only 2 in-window failures remain
        assertFalse("stale failures should be evicted", rl.isBlocked("9.9.9.9"))
    }

    @Test
    fun `different IPs are tracked independently`() {
        var t = 1000L
        val rl = AuthRateLimiter(maxAttempts = 2, windowMs = 100, blockMs = 200) { t }

        repeat(2) { rl.recordFailure("attacker") }
        assertTrue(rl.isBlocked("attacker"))
        assertFalse("unrelated IP must not be blocked", rl.isBlocked("bystander"))
    }

    @Test
    fun `sweep drops IPs whose failures have all left the window`() {
        var t = 1000L
        val rl = AuthRateLimiter(
            maxAttempts = 5, windowMs = 100, blockMs = 200, cleanupIntervalMs = 50
        ) { t }

        rl.recordFailure("stale")
        assertEquals(1, rl.trackedIpCount())

        t += 500 // "stale" is now outside the window; this call also sweeps
        rl.recordFailure("fresh")
        assertEquals("only the fresh IP should remain tracked", 1, rl.trackedIpCount())
    }

    @Test
    fun `reaching the threshold clears the failure history`() {
        val rl = AuthRateLimiter(maxAttempts = 2, windowMs = 100, blockMs = 200) { 1000L }

        repeat(2) { rl.recordFailure("ip") }
        assertTrue(rl.isBlocked("ip"))
        assertEquals(0, rl.trackedIpCount())
    }

    @Test
    fun `a new block after expiry is honoured`() {
        var t = 1000L
        val rl = AuthRateLimiter(maxAttempts = 1, windowMs = 100, blockMs = 200) { t }

        rl.recordFailure("ip")
        t += 201
        assertFalse(rl.isBlocked("ip"))
        rl.recordFailure("ip")
        assertTrue("re-block must take effect", rl.isBlocked("ip"))
    }

    @Test
    fun `concurrent failures with sweeping on every call are not lost`() {
        // cleanupIntervalMs = 0 sweeps on every call, maximising overlap
        // between recordFailure and the sweep. Every failure is in-window, so
        // all threads x perThread failures must count and trip the block.
        val threads = 8
        val perThread = 50
        val rl = AuthRateLimiter(
            maxAttempts = threads * perThread,
            windowMs = 60_000,
            blockMs = 300_000,
            cleanupIntervalMs = 0,
        ) { 1_000_000L }

        val gate = java.util.concurrent.CountDownLatch(1)
        val workers = (1..threads).map {
            Thread {
                gate.await()
                repeat(perThread) { rl.recordFailure("shared") }
            }.apply { start() }
        }
        gate.countDown()
        workers.forEach { it.join(10_000) }

        assertTrue("no failure may be dropped under concurrency", rl.isBlocked("shared"))
    }

    @Test
    fun `a clean IP is never blocked`() {
        val rl = AuthRateLimiter { 1000L }
        assertFalse(rl.isBlocked("never-seen"))
    }
}
