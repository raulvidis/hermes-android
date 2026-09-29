package com.hermesandroid.bridge.power

import android.content.Context
import android.os.PowerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPowerManager

/**
 * Behavioural tests for the "wake" press_key (PR #101), restored after review F3.
 *
 * `wake()` returns `true` unconditionally once a PowerManager is present — it
 * never reports what `acquireWakeLock()` did. A suite that only asserts the
 * return value therefore passes for any implementation that does not throw,
 * including one that acquires nothing, one that drops `ACQUIRE_CAUSES_WAKEUP`
 * (the screen stays black while the tool reports success) and one that calls
 * `acquire()` with no timeout (the lock is held forever on a device meant to be
 * always-on). These tests observe the lock itself.
 *
 * This class runs against the real Robolectric shadow: `ShadowPowerManager.getLatestWakeLock()`
 * says whether a lock was created at all, and `Shadow.extract` on it gives the
 * tag, the hold count and `isHeld`. `Shadows.shadowOf()` has no PowerManager
 * overload, which is why this uses `Shadow.extract` — the same reason
 * CommandDispatcherBatteryTest reaches ShadowBatteryManager that way.
 *
 * The wake-lock *flags* and the *timeout* are not observable here, and cannot be
 * made so: `ShadowPowerManager.newWakeLock` builds the lock with
 * `Shadow.newInstanceOf`, which skips the real constructor, so the lock's
 * `mFlags` stays 0 whatever was passed, and `ShadowWakeLock` keeps its timeout
 * list in a private field with no getter. Those two assertions live in
 * [WakeLockManagerAcquireTest], which runs without Robolectric and mocks the
 * PowerManager instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeLockManagerWakeTest {

    private lateinit var powerManager: PowerManager
    private lateinit var shadowPower: ShadowPowerManager

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowPower = Shadow.extract<ShadowPowerManager>(powerManager)
        // latestWakeLock is static on the shadow, so it outlives the instance.
        ShadowPowerManager.clearWakeLocks()
        WakeLockManager.forceRelease()
        WakeLockManager.powerManager = null
        WakeLockManager.init(context)
    }

    @After
    fun tearDown() {
        WakeLockManager.forceRelease()
        WakeLockManager.powerManager = null
        ShadowPowerManager.clearWakeLocks()
    }

    // ── Against the real shadow: was a lock created, and is it held? ──────────

    @Test
    fun `wake creates no lock when the screen is already interactive`() {
        shadowPower.setIsInteractive(true)

        assertTrue(WakeLockManager.wake())

        assertNull(
            "an already-awake screen must not cost a wake lock",
            ShadowPowerManager.getLatestWakeLock(),
        )
    }

    @Test
    fun `wake creates and holds a lock when the screen is off`() {
        shadowPower.setIsInteractive(false)

        assertTrue(WakeLockManager.wake())

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("a screen-off wake must acquire a lock", lock)
        assertTrue("the lock must actually be held", lock!!.isHeld)
        assertEquals("hermes:bridge_action", Shadow.extract<ShadowPowerManager.ShadowWakeLock>(lock).tag)
        assertEquals(
            "exactly one acquire — no double-hold",
            1,
            Shadow.extract<ShadowPowerManager.ShadowWakeLock>(lock).timesHeld,
        )
    }

    @Test
    fun `forceRelease clears the held lock`() {
        shadowPower.setIsInteractive(false)
        WakeLockManager.wake()
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(lock)
        assertTrue(lock!!.isHeld)

        WakeLockManager.forceRelease()

        assertFalse("forceRelease must leave nothing held", lock.isHeld)
    }

    @Test
    fun `repeated wakes release the previous lock instead of stacking`() {
        shadowPower.setIsInteractive(false)

        WakeLockManager.wake()
        val first = ShadowPowerManager.getLatestWakeLock()
        WakeLockManager.wake()
        val second = ShadowPowerManager.getLatestWakeLock()

        assertNotNull(first)
        assertNotNull(second)
        assertTrue("the superseded lock must be released", !first!!.isHeld || first === second)
        assertTrue("the current lock must be held", second!!.isHeld)
    }

    @Test
    fun `wake reports failure when no PowerManager was ever wired up`() {
        WakeLockManager.powerManager = null

        assertFalse(WakeLockManager.wake())
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }
}
