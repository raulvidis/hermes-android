package com.hermesandroid.bridge.server

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
    fun `a clean IP is never blocked`() {
        val rl = AuthRateLimiter { 1000L }
        assertFalse(rl.isBlocked("never-seen"))
    }
}
