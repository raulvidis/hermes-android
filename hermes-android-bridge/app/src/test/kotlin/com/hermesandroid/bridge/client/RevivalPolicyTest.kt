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
}
