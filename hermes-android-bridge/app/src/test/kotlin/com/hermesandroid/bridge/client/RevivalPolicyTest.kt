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
     * Regression: the cooldown was a bare `now - lastFire < cooldown`. If the wall
     * clock is corrected backwards after a fire, lastFire sits in the FUTURE, the
     * subtraction goes negative and the watchdog stays shut until that bogus
     * timestamp passes.
     */
    @Test
    fun `a last fire in the future does not wedge the cooldown shut`() {
        val now = 1_000_000L
        val d = policy.onFailure(enabled = true, failures = 4, lastFireMs = now + 5_000_000L, nowMs = now)
        assertEquals(RevivalPolicy.Decision.Fire, d)
    }

    @Test
    fun `a future last fire still respects the fire budget`() {
        val now = 1_000_000L
        val d = policy.onFailure(
            enabled = true,
            failures = 4,
            lastFireMs = now + 5_000_000L,
            nowMs = now,
            fires = RevivalPolicy.DEFAULT_MAX_FIRES,
        )
        assertEquals(RevivalPolicy.Decision.Exhausted, d)
    }
}

/**
 * Review F6: the watchdog's only bounds were the failure threshold and the
 * cooldown, and `Fire` reset the counter to 0 — so a relay that is simply
 * unreachable (wrong server URL, rotated token, server down for the weekend)
 * made an opt-in device execute an arbitrary shell script every 10 minutes,
 * forever, with no attempt cap and nothing user-visible.
 *
 * Two bounds, both in the pure policy so they stay testable:
 * - a fire budget, spent which the watchdog stays off until a successful
 *   connection re-arms it;
 * - an auth failure does not count at all, because restarting Termux cannot fix
 *   a 401/403 and spending the budget on one would hide a real outage.
 */
class RevivalPolicyBudgetTest {

    private val policy = RevivalPolicy(failuresToTrigger = 3, cooldownMs = 600_000L, maxFires = 3)

    /** Walk the policy the way RelayClient does: persist what it returns. */
    private fun runOutage(
        cycles: Int,
        enabled: Boolean = true,
        authFailure: Boolean = false,
        stepMs: Long = 600_001L,
    ): List<RevivalPolicy.Decision> {
        var failures = 0
        var fires = 0
        var lastFire = 0L
        var now = 1_000_000L
        val decisions = mutableListOf<RevivalPolicy.Decision>()
        repeat(cycles) {
            val d = policy.onFailure(
                enabled = enabled,
                failures = failures,
                lastFireMs = lastFire,
                nowMs = now,
                fires = fires,
                authFailure = authFailure,
            )
            decisions += d
            when (d) {
                is RevivalPolicy.Decision.Wait -> failures = d.failures
                RevivalPolicy.Decision.Fire -> {
                    failures = 0
                    fires += 1
                    lastFire = now
                }
                RevivalPolicy.Decision.Exhausted -> Unit
            }
            now += stepMs
        }
        return decisions
    }

    @Test
    fun `a long outage fires only up to the budget and then stops`() {
        val decisions = runOutage(cycles = 60)

        val fired = decisions.count { it == RevivalPolicy.Decision.Fire }
        assertEquals("the budget caps the fires", 3, fired)
        // 60 cycles at a 10-minute step is a 10-hour outage. Before the cap it
        // produced a fire every cycle past the threshold, indefinitely.
        val lastFire = decisions.indexOfLast { it == RevivalPolicy.Decision.Fire }
        assertTrue(
            "firing must stop early in a long outage, but the last fire was at index $lastFire of ${decisions.size}",
            lastFire < decisions.size / 2,
        )
        assertEquals(
            "the caller needs to tell the user the watchdog gave up",
            RevivalPolicy.Decision.Exhausted,
            decisions.last(),
        )
    }

    @Test
    fun `once the budget is spent every further failure stays Exhausted`() {
        val decisions = runOutage(cycles = 12)

        // The policy reports the spent budget on every call after it; deduping
        // the user-visible notice is RelayClient's job, not the policy's. What
        // matters here is that the state is stable and the counter stops moving,
        // so nothing can creep back towards another Fire.
        val afterLastFire = decisions.indexOfLast { it == RevivalPolicy.Decision.Fire }
        assertTrue(
            "every decision after the last fire must be Exhausted",
            decisions.drop(afterLastFire + 1).all { it == RevivalPolicy.Decision.Exhausted },
        )
    }

    @Test
    fun `an auth failure never fires however long it lasts`() {
        val decisions = runOutage(cycles = 30, authFailure = true)

        assertEquals(0, decisions.count { it == RevivalPolicy.Decision.Fire })
        assertTrue(
            "a 401/403 is not evidence the Termux stack is dead",
            decisions.all { it is RevivalPolicy.Decision.Wait },
        )
        assertEquals(
            "an auth failure must not advance the counter either",
            0,
            (decisions.last() as RevivalPolicy.Decision.Wait).failures,
        )
    }

    @Test
    fun `an auth failure does not spend the fire budget`() {
        // 20 auth failures, then a genuine unreachable-host outage: the budget
        // must still be intact for the outage that a restart could fix.
        runOutage(cycles = 20, authFailure = true)
        val decisions = runOutage(cycles = 12)

        assertEquals(3, decisions.count { it == RevivalPolicy.Decision.Fire })
    }

    @Test
    fun `the gate still wins over the budget`() {
        val decisions = runOutage(cycles = 30, enabled = false)

        assertEquals(0, decisions.count { it == RevivalPolicy.Decision.Fire })
        assertTrue(decisions.none { it == RevivalPolicy.Decision.Exhausted })
    }

    @Test
    fun `a budget of one fire stops after the first`() {
        val single = RevivalPolicy(failuresToTrigger = 3, cooldownMs = 600_000L, maxFires = 1)
        var failures = 0
        var fires = 0
        var lastFire = 0L
        var now = 1_000_000L
        val decisions = mutableListOf<RevivalPolicy.Decision>()
        repeat(9) {
            val d = single.onFailure(true, failures, lastFire, now, fires = fires)
            decisions += d
            if (d == RevivalPolicy.Decision.Fire) {
                failures = 0; fires += 1; lastFire = now
            } else if (d is RevivalPolicy.Decision.Wait) {
                failures = d.failures
            }
            now += 600_001L
        }

        assertEquals(1, decisions.count { it == RevivalPolicy.Decision.Fire })
    }

    @Test
    fun `a re-armed budget fires again`() {
        // RelayClient re-arms by resetting the persisted fire count when a
        // connection actually opens. Model that: exhaust the budget, zero the
        // count the way onOpen does, and confirm the watchdog is live again
        // rather than dead for the rest of the process lifetime.
        var failures = 0
        var fires = 0
        var lastFire = 0L
        var now = 1_000_000L
        var fired = 0

        fun step() {
            val d = policy.onFailure(true, failures, lastFire, now, fires = fires)
            when (d) {
                is RevivalPolicy.Decision.Wait -> failures = d.failures
                RevivalPolicy.Decision.Fire -> { failures = 0; fires += 1; lastFire = now; fired++ }
                RevivalPolicy.Decision.Exhausted -> Unit
            }
            now += 600_001L
        }

        repeat(30) { step() }
        assertEquals("budget spent", 3, fired)

        fires = 0 // <- onOpen
        failures = 0
        repeat(30) { step() }
        assertEquals("a re-armed watchdog must be able to fire again", 6, fired)
    }

    @Test
    fun `default budget is bounded`() {
        val defaulted = RevivalPolicy(failuresToTrigger = 3, cooldownMs = 600_000L)
        var failures = 0
        var fires = 0
        var lastFire = 0L
        var now = 1_000_000L
        var fired = 0
        repeat(200) {
            val d = defaulted.onFailure(true, failures, lastFire, now, fires = fires)
            when (d) {
                is RevivalPolicy.Decision.Wait -> failures = d.failures
                RevivalPolicy.Decision.Fire -> { failures = 0; fires += 1; lastFire = now; fired++ }
                RevivalPolicy.Decision.Exhausted -> Unit
            }
            now += 600_001L
        }

        assertTrue("the default must be finite, got $fired fires in 200 cycles", fired in 1..10)
    }
}
