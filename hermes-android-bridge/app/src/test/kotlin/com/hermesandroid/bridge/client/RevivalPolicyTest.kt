package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevivalPolicyTest {

    private val policy = RevivalPolicy(failuresToTrigger = 5, cooldownMs = 600_000L)

    @Test
    fun `disabled never fires and never increments`() {
        val now = 1_000_000L
        val d = policy.onFailure(enabled = false, failures = 4, lastFireMs = 0L, nowMs = now)
        assertTrue("gate must block firing", d is RevivalPolicy.Decision.Wait)
        assertEquals("disabled must not advance the counter", 4, (d as RevivalPolicy.Decision.Wait).failures)
    }

    @Test
    fun `counter increments below the threshold`() {
        val now = 1_000_000L
        val d = policy.onFailure(enabled = true, failures = 0, lastFireMs = 0L, nowMs = now)
        assertTrue(d is RevivalPolicy.Decision.Wait)
        assertEquals(1, (d as RevivalPolicy.Decision.Wait).failures)
    }

    @Test
    fun `fires when the threshold is reached`() {
        val now = 1_000_000L
        val d = policy.onFailure(enabled = true, failures = 4, lastFireMs = 0L, nowMs = now)
        assertEquals("4 prior failures + this one = threshold", RevivalPolicy.Decision.Fire, d)
    }

    @Test
    fun `cooldown is respected after a fire`() {
        val lastFire = 1_000_000L
        val d = policy.onFailure(enabled = true, failures = 4, lastFireMs = lastFire, nowMs = lastFire + 60_000L)
        assertTrue("inside the 10-min cooldown must not re-fire", d is RevivalPolicy.Decision.Wait)
    }

    @Test
    fun `cooldown expires and firing resumes`() {
        val lastFire = 1_000_000L
        val d = policy.onFailure(enabled = true, failures = 4, lastFireMs = lastFire, nowMs = lastFire + 600_001L)
        assertEquals("after the cooldown the threshold fires again", RevivalPolicy.Decision.Fire, d)
    }

    /**
     * Regression: the cooldown used a bare `now - lastFire < cooldown`. If the
     * wall clock was corrected backwards after a fire, lastFire ends up in the
     * FUTURE, that subtraction goes negative and the watchdog stays wedged shut.
     * A future lastFire must be treated as "cooldown elapsed".
     */
    @Test
    fun `future last-fire timestamp is treated as cooldown elapsed`() {
        val now = 1_000_000L
        val futureLastFire = now + 5_000_000L
        val d = policy.onFailure(enabled = true, failures = 4, lastFireMs = futureLastFire, nowMs = now)
        assertEquals("a future lastFire must not wedge the watchdog shut", RevivalPolicy.Decision.Fire, d)
    }

    /**
     * Pin the policy against the constant production actually uses (3), not a
     * hand-picked test value, so a change to the threshold is caught here.
     */
    @Test
    fun `fires at the production trigger threshold`() {
        val prodPolicy = RevivalPolicy(
            failuresToTrigger = RelayClient.WATCHDOG_FAILURES_TO_TRIGGER,
            cooldownMs = 600_000L,
        )
        val now = 1_000_000L

        // Every failure below the threshold only advances the counter.
        var failures = 0
        repeat(RelayClient.WATCHDOG_FAILURES_TO_TRIGGER - 1) {
            val d = prodPolicy.onFailure(enabled = true, failures = failures, lastFireMs = 0L, nowMs = now)
            assertTrue("below threshold must wait", d is RevivalPolicy.Decision.Wait)
            failures = (d as RevivalPolicy.Decision.Wait).failures
        }

        // The failure that reaches the threshold fires.
        val d = prodPolicy.onFailure(enabled = true, failures = failures, lastFireMs = 0L, nowMs = now)
        assertEquals(
            "reaching WATCHDOG_FAILURES_TO_TRIGGER must fire",
            RevivalPolicy.Decision.Fire,
            d,
        )
    }
}
