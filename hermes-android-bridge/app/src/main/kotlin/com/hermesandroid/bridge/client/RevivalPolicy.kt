package com.hermesandroid.bridge.client

/**
 * Pure decision logic for the Termux revival watchdog.
 *
 * Kept free of Android/prefs so the gate, the failure counter, the trigger
 * threshold and the cooldown can be pinned by plain JVM unit tests.
 */
class RevivalPolicy(
    private val failuresToTrigger: Int,
    private val cooldownMs: Long,
) {
    sealed class Decision {
        /** Not firing yet — [failures] is the counter value to persist. */
        data class Wait(val failures: Int) : Decision()

        /** Fire the revival (the caller persists the reset + the timestamp). */
        object Fire : Decision()
    }

    /**
     * Called once per connection failure.
     *
     * @param enabled the opt-in gate — when false the watchdog NEVER fires.
     * @param failures the persisted failure counter before this call.
     * @param lastFireMs the persisted timestamp of the last fire (0 = never).
     * @param nowMs the current wall clock.
     */
    fun onFailure(enabled: Boolean, failures: Int, lastFireMs: Long, nowMs: Long): Decision {
        // Opt-in gate: silent auto-execution must be explicitly enabled.
        if (!enabled) return Decision.Wait(failures)
        val next = failures + 1
        if (next < failuresToTrigger) return Decision.Wait(next)
        // Reached the threshold, but the cooldown bounds re-firing.
        // Use a signed elapsed value: if lastFireMs is in the FUTURE relative to
        // now (wall clock corrected backwards after the last fire), sinceLast is
        // negative and must be treated as "cooldown elapsed" so the watchdog is
        // not wedged shut until the bogus future timestamp passes.
        val sinceLast = nowMs - lastFireMs
        if (sinceLast in 0 until cooldownMs) return Decision.Wait(next)
        return Decision.Fire
    }
}
