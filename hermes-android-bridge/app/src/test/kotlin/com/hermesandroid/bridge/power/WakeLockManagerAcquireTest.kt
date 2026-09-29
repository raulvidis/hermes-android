package com.hermesandroid.bridge.power

import android.os.PowerManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two wake-lock properties [WakeLockManagerWakeTest] cannot see, asserted
 * through the `WakeLockManager.powerManager` seam (review F3).
 *
 * Why this class has no Robolectric runner:
 * - Under Robolectric, `android.os.PowerManager` is already instrumented, so
 *   mockk's inline retransform is rejected with "class redefinition failed:
 *   attempted to change the class modifiers". Mocking it needs the plain
 *   (AGP mockable) android.jar.
 * - The shadow cannot report the flags either: `ShadowPowerManager.newWakeLock`
 *   builds the lock with `Shadow.newInstanceOf`, skipping the real constructor,
 *   so the lock's `mFlags` stays 0 whatever was passed in, and `ShadowWakeLock`
 *   keeps its timeout list in a private field with no getter.
 *
 * Both properties are the ones F3's failure scenario names: dropping
 * `ACQUIRE_CAUSES_WAKEUP` makes `press_key("wake")` report success while the
 * screen stays black, and swapping `acquire(10_000)` for `acquire()` holds the
 * lock forever on a device meant to be always-on. `wake()` returns `true` in
 * both cases, so nothing else in the suite would notice.
 */
class WakeLockManagerAcquireTest {

    private val pm = mockk<PowerManager>(relaxed = true)
    private val lock = mockk<PowerManager.WakeLock>(relaxed = true)

    @Before
    fun setup() {
        every { pm.isInteractive } returns false
        every { pm.newWakeLock(any(), any()) } returns lock
        WakeLockManager.forceRelease()
        WakeLockManager.powerManager = pm
    }

    @After
    fun tearDown() {
        WakeLockManager.forceRelease()
        WakeLockManager.powerManager = null
        unmockkAll()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `the lock turns the screen on rather than only keeping it on`() {
        val flags = slot<Int>()
        val tag = slot<String>()
        every { pm.newWakeLock(capture(flags), capture(tag)) } returns lock

        WakeLockManager.wake()

        // Without ACQUIRE_CAUSES_WAKEUP the lock keeps an already-lit screen lit
        // and does nothing for a black one.
        assertTrue(
            "ACQUIRE_CAUSES_WAKEUP must be set, got flags=${flags.captured}",
            flags.captured and PowerManager.ACQUIRE_CAUSES_WAKEUP != 0,
        )
        assertTrue(
            "SCREEN_BRIGHT_WAKE_LOCK must be set (bright, not dim), got flags=${flags.captured}",
            flags.captured and PowerManager.SCREEN_BRIGHT_WAKE_LOCK != 0,
        )
        assertTrue("the lock must be tagged as ours, got '${tag.captured}'", tag.captured.isNotEmpty())
        verify(exactly = 1) { pm.newWakeLock(any(), any()) }
    }

    @Test
    fun `the lock carries a timeout so it cannot be held forever`() {
        WakeLockManager.wake()

        // acquire() with no argument holds until released — an open-ended drain
        // on a device that is meant to stay online.
        verify(exactly = 1) { lock.acquire(10_000L) }
        verify(exactly = 0) { lock.acquire() }
    }

    @Test
    fun `forceRelease releases the lock it is holding`() {
        every { lock.isHeld } returns true

        WakeLockManager.wake()
        WakeLockManager.forceRelease()

        verify { lock.release() }
    }

    @Test
    fun `a second wake releases the first lock instead of stacking two`() {
        every { lock.isHeld } returns true
        val second = mockk<PowerManager.WakeLock>(relaxed = true)
        WakeLockManager.wake()

        every { pm.newWakeLock(any(), any()) } returns second
        WakeLockManager.wake()

        // acquireWakeLock() releases whatever it still holds before taking a new
        // lock; without that, repeated wakes stack held locks.
        verify(atLeast = 1) { lock.release() }
        verify { second.acquire(10_000L) }
    }

    @Test
    fun `nothing is acquired when the screen is already interactive`() {
        every { pm.isInteractive } returns true

        assertTrue(WakeLockManager.wake())

        verify(exactly = 0) { pm.newWakeLock(any(), any()) }
    }
}
