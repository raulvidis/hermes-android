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
    private val cleanupIntervalMs: Long = 120_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    // Each IP's list is only ever read or mutated inside a ConcurrentHashMap
    // compute/computeIfPresent call for that key, which runs atomically per
    // key. That is what keeps recordFailure and the sweep from racing: the
    // sweep can no longer remove a list another thread has just fetched and
    // is about to append to (which silently dropped that failure).
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()
    private val blocked = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastCleanup: Long = 0L

    /**
     * Returns `true` if [ip] is currently blocked. Clears the block once it
     * has expired.
     */
    fun isBlocked(ip: String): Boolean {
        val t = now()
        val until = blocked[ip] ?: return false
        if (t < until) return true
        // Conditional remove: only drop the expired value we just read. A
        // concurrent recordFailure may have installed a fresh block in the
        // meantime, and an unconditional remove would lift it.
        blocked.remove(ip, until)
        return false
    }

    /**
     * Records a failed auth attempt for [ip]. Once [maxAttempts] failures
     * accumulate within [windowMs], the IP is blocked for [blockMs] and its
     * failure history is cleared.
     *
     * Periodically sweeps [failures] to remove IPs whose timestamps have all
     * fallen outside the window, preventing unbounded growth when many
     * distinct IPs each make a few attempts below the threshold. Mirrors the
     * Python relay's `_auth_cleanup()` (runs every [cleanupIntervalMs]).
     */
    fun recordFailure(ip: String) {
        val t = now()
        val cutoff = t - windowMs
        failures.compute(ip) { _, existing ->
            val attempts = existing ?: mutableListOf()
            // Drop attempts that fell out of the sliding window.
            attempts.removeAll { it <= cutoff }
            attempts.add(t)
            if (attempts.size >= maxAttempts) {
                blocked[ip] = t + blockMs
                null // history cleared: remove the mapping
            } else {
                attempts
            }
        }
        maybeSweep(t, cutoff)
    }

    /**
     * Runs a cleanup sweep at most once per [cleanupIntervalMs]. Removes IPs
     * whose all timestamps are older than [cutoff]. A snapshot of keys is
     * iterated; each entry is inspected and removed atomically via
     * computeIfPresent, so a concurrent recordFailure for the same IP is
     * either fully before or fully after the check.
     */
    private fun maybeSweep(t: Long, cutoff: Long) {
        if (t - lastCleanup < cleanupIntervalMs) return
        lastCleanup = t
        for (ip in failures.keys.toList()) {
            failures.computeIfPresent(ip) { _, list ->
                if (list.none { it > cutoff }) null else list
            }
        }
        // Expired blocks would otherwise linger until that IP is seen again.
        for ((ip, until) in blocked.entries.toList()) {
            if (t >= until) blocked.remove(ip, until)
        }
    }

    /** Test hook: number of IPs with a tracked failure history. */
    internal fun trackedIpCount(): Int = failures.size
}
