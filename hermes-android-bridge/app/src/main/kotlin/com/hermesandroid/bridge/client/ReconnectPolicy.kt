package com.hermesandroid.bridge.client

/**
 * Attempt counter + exponential backoff for [RelayClient] reconnects.
 *
 * State lives here rather than in the reconnect coroutine on purpose: every
 * failed connect fires another `onFailure`, which schedules another reconnect.
 * A counter local to that coroutine restarts at zero each time, so an
 * unreachable address never exhausts its retries and loops forever.
 *
 * The budget is only restored by [reset] (a user-initiated connect) or by
 * [onSessionEnded] for a session that stayed up at least [stableSessionMs].
 * Merely reaching `onOpen` is NOT enough: a relay that accepts the socket and
 * immediately closes it (wrong pairing code, auth reject) would otherwise
 * refill the budget on every attempt and flap forever.
 *
 * All state is guarded by this object's monitor — [RelayClient] touches it from
 * OkHttp callback threads, the reconnect coroutine, and the main thread.
 */
class ReconnectPolicy(
    private val maxRetries: Int = 5,
    private val maxBackoffMs: Long = 30_000L,
    private val baseBackoffMs: Long = 1_000L,
    private val stableSessionMs: Long = 60_000L,
) {

    private var attemptCount: Int = 0

    val attempts: Int
        @Synchronized get() = attemptCount

    /** True once [maxRetries] attempts have been handed out without a reset. */
    val isExhausted: Boolean
        @Synchronized get() = attemptCount >= maxRetries

    val limit: Int
        get() = maxRetries

    /**
     * Consume one attempt and return how long to wait before it.
     * Caller must check [isExhausted] first.
     */
    @Synchronized
    fun nextBackoffMs(): Long {
        val exponent = attemptCount.coerceAtMost(30)
        attemptCount++
        val backoff = baseBackoffMs shl exponent
        return if (backoff <= 0L) maxBackoffMs else backoff.coerceAtMost(maxBackoffMs)
    }

    /**
     * Report that a connection that had opened is now gone, having lasted
     * [durationMs]. Only a session that proved stable refills the budget.
     */
    @Synchronized
    fun onSessionEnded(durationMs: Long) {
        if (durationMs >= stableSessionMs) attemptCount = 0
    }

    @Synchronized
    fun reset() {
        attemptCount = 0
    }
}

/**
 * What [RelayClient.scheduleReconnect] should do when a dead socket lands back
 * on it. Extracted as a pure enum + function so the exact ordering the
 * production path relies on can be unit-tested without OkHttp, coroutines or
 * Android — the test exercises THIS, not a copy of the loop.
 */
enum class ReconnectAction {
    /** Not reconnecting at all (disconnected, or the scope is already gone). */
    IGNORE,

    /** Another callback for the same dead socket already scheduled an attempt. */
    SKIP_PENDING,

    /** The retry budget for this cycle is spent — hand off to the give-up /
     *  watchdog path. */
    EXHAUSTED,

    /** Schedule the next backed-off reconnect attempt. */
    ATTEMPT,
}

/**
 * Decide what a reconnect request should do.
 *
 * The ordering is load-bearing and mirrors the production callback path:
 *  1. bail if we should not reconnect or the scope is gone,
 *  2. THEN honour an in-flight pending attempt (so a second callback for the
 *     same socket does not consume the budget the first one just spent),
 *  3. THEN check exhaustion,
 *  4. otherwise attempt.
 *
 * `reconnectPending` MUST be evaluated before `isExhausted`: swapping them lets
 * the second of onClosed/onFailure declare failure while the first's final
 * attempt is still waiting out its backoff, silently skipping the last retry.
 */
fun decideReconnect(
    shouldReconnect: Boolean,
    hasScope: Boolean,
    reconnectPending: Boolean,
    isExhausted: Boolean,
): ReconnectAction {
    if (!shouldReconnect || !hasScope) return ReconnectAction.IGNORE
    if (reconnectPending) return ReconnectAction.SKIP_PENDING
    if (isExhausted) return ReconnectAction.EXHAUSTED
    return ReconnectAction.ATTEMPT
}
