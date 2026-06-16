package com.hermesandroid.bridge.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-IP auth-failure throttle.
 *
 * Mirrors the policy enforced by the Python relay (`android_relay.py`):
 * [maxAttempts] failures within a sliding [windowMs] window block the IP for
 * [blockMs]. The bridge binds `0.0.0.0:8765` by default, so without this a
 * host on a shared/hostile network can brute-force the 6-char pairing code
 * directly, bypassing the relay's own throttle. A cracked code yields full
 * remote control of the device.
 *
 * [now] is injected so the sliding-window/block behaviour is deterministic
 * under test.
 */
internal class AuthRateLimiter(
    private val maxAttempts: Int = 5,
    private val windowMs: Long = 60_000L,
    private val blockMs: Long = 300_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()
    private val blocked = ConcurrentHashMap<String, Long>()

    /**
     * Returns `true` if [ip] is currently blocked. Clears the block once it
     * has expired.
     */
    fun isBlocked(ip: String): Boolean {
        val until = blocked[ip] ?: return false
        if (now() < until) return true
        blocked.remove(ip)
        return false
    }

    /**
     * Records a failed auth attempt for [ip]. Once [maxAttempts] failures
     * accumulate within [windowMs], the IP is blocked for [blockMs] and its
     * failure history is cleared.
     */
    fun recordFailure(ip: String) {
        val t = now()
        val cutoff = t - windowMs
        val attempts = failures.computeIfAbsent(ip) { mutableListOf() }
        synchronized(attempts) {
            // Drop attempts that fell out of the sliding window.
            attempts.removeAll { it <= cutoff }
            attempts.add(t)
            if (attempts.size >= maxAttempts) {
                blocked[ip] = t + blockMs
                attempts.clear()
            }
        }
    }
}
