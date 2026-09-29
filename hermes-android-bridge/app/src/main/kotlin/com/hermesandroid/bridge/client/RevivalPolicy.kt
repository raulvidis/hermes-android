package com.hermesandroid.bridge.client

/**
 * Pure decision logic for the Termux revival watchdog.
 *
 * Kept free of Android/prefs so the gate, the failure counter, the trigger
 * threshold, the cooldown and the fire budget can be pinned by plain JVM unit
 * tests.
 */
class RevivalPolicy(
    private val failuresToTrigger: Int,
    private val cooldownMs: Long,
    private val maxFires: Int = DEFAULT_MAX_FIRES,
) {
    sealed class Decision {
        /** Not firing yet — [failures] is the counter value to persist. */
        data class Wait(val failures: Int) : Decision()

        /** Fire the revival (the caller persists the reset + the timestamp). */
        object Fire : Decision()

        /**
         * The fire budget is spent. The watchdog stays off until the caller
         * re-arms it by resetting the persisted fire count — which RelayClient
         * does when a connection actually opens. Reported on every failure past
         * the budget so the caller's state stays stable; deduping the
         * user-visible notice is the caller's job.
         */
        object Exhausted : Decision()
    }

    /**
     * Called once per connection failure.
     *
     * @param enabled the opt-in gate — when false the watchdog NEVER fires.
     * @param failures the persisted failure counter before this call.
     * @param lastFireMs the persisted timestamp of the last fire (0 = never).
     * @param nowMs the current wall clock.
     * @param fires how many times the watchdog has already fired since it was
     *   last re-armed. Bounds the total, so an unreachable relay cannot make an
     *   opt-in device run a shell script every cooldown forever.
     * @param authFailure the failure was an HTTP 401/403. Restarting Termux
     *   cannot fix a credential problem, so it neither advances the counter nor
     *   spends the budget — otherwise a rotated pairing code would burn the
     *   budget and mask the outage a restart could have fixed.
     */
    fun onFailure(
        enabled: Boolean,
        failures: Int,
        lastFireMs: Long,
        nowMs: Long,
        fires: Int = 0,
        authFailure: Boolean = false,
    ): Decision {
        // Opt-in gate: silent auto-execution must be explicitly enabled.
        if (!enabled) return Decision.Wait(failures)
        // Not evidence the stack is dead — leave the counters alone.
        if (authFailure) return Decision.Wait(failures)
        // Hard cap. Without it the threshold + cooldown cycle repeats forever.
        if (fires >= maxFires) return Decision.Exhausted
        val next = failures + 1
        if (next < failuresToTrigger) return Decision.Wait(next)
        // Reached the threshold, but the cooldown bounds re-firing.
        if (nowMs - lastFireMs < cooldownMs) return Decision.Wait(next)
        return Decision.Fire
    }

    companion object {
        /**
         * Three fires at a 10-minute cooldown is ~30 minutes of automatic
         * recovery attempts — enough for a stack that takes a while to come
         * back, not enough to turn a weekend outage into 400 script runs.
         */
        const val DEFAULT_MAX_FIRES = 3
    }
}
