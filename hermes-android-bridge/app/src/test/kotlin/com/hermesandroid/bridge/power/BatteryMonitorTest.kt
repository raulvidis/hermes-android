package com.hermesandroid.bridge.power

import android.content.Context
import android.os.BatteryManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBatteryManager

/**
 * Contract tests for BatteryMonitor, the platform-backed source of GET /battery.
 *
 * Note on scope: this class deliberately parses nothing. There is no
 * ACTION_BATTERY_CHANGED intent handling anywhere in the bridge — the level and
 * charge state come from BatteryManager properties, because a scraped status-bar
 * reading changes wording with the OEM skin and the device language and ends up
 * reporting a confident wrong number. The tests therefore pin the *reading*
 * contract, and above all the "no answer is null, never a guess" rule:
 *
 * - Integer.MIN_VALUE is how getIntProperty reports failure on targetSdk >= P
 *   (this app targets 34), so it must never surface as a level.
 * - Anything outside 0..100 is treated the same way.
 * - A binder failure in the battery service costs one unavailable reading, not
 *   the whole request: it must be swallowed into null, not propagated.
 *
 * Both methods must also work with no accessibility service running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BatteryMonitorTest {

    private lateinit var context: Context
    private lateinit var shadowBattery: ShadowBatteryManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        // Shadow.extract rather than Shadows.shadowOf: works for any
        // @Implements-annotated shadow without needing a generated overload.
        shadowBattery = Shadow.extract<ShadowBatteryManager>(manager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** A context whose battery service is [manager] (null = service absent). */
    private fun contextWith(manager: Any?): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Context.BATTERY_SERVICE) } returns manager
        return ctx
    }

    // --- percentage ----------------------------------------------------------

    @Test
    fun `percentage reports the platform level`() {
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 87)

        assertEquals(87, BatteryMonitor.percentage(context))
    }

    @Test
    fun `percentage accepts the empty boundary`() {
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 0)

        assertEquals(0, BatteryMonitor.percentage(context))
    }

    @Test
    fun `percentage accepts the full boundary`() {
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 100)

        assertEquals(100, BatteryMonitor.percentage(context))
    }

    @Test
    fun `percentage is null when the platform reports no answer`() {
        // Nothing set: the shadow answers Integer.MIN_VALUE, exactly what the
        // real getIntProperty reports on failure when targetSdkVersion >= P.
        assertNull(BatteryMonitor.percentage(context))
    }

    @Test
    fun `percentage is null for an explicit MIN_VALUE reading`() {
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, Integer.MIN_VALUE)

        assertNull(BatteryMonitor.percentage(context))
    }

    @Test
    fun `percentage rejects a level above 100 instead of reporting it`() {
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 101)
        assertNull(BatteryMonitor.percentage(context))

        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 999)
        assertNull(BatteryMonitor.percentage(context))
    }

    @Test
    fun `percentage rejects a negative level instead of reporting it`() {
        // -1 is the other "unknown" some OEM battery services hand back. A
        // low-battery alarm reading -1 as "critically low" would page a user
        // about a phone that is fine.
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, -1)

        assertNull(BatteryMonitor.percentage(context))
    }

    @Test
    fun `percentage is null when there is no battery service`() {
        assertNull(BatteryMonitor.percentage(contextWith(null)))
    }

    @Test
    fun `percentage is null when the service is not a BatteryManager`() {
        // Defensive `as?`: a context that hands back something else must not
        // turn into a ClassCastException inside a request handler.
        assertNull(BatteryMonitor.percentage(contextWith("not-a-battery-manager")))
    }

    @Test
    fun `percentage survives a battery service that throws`() {
        val manager = mockk<BatteryManager>(relaxed = true)
        every { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } throws
            RuntimeException("binder transaction failed")

        assertNull(BatteryMonitor.percentage(contextWith(manager)))
    }

    @Test
    fun `percentage reports a level that becomes readable on a later call`() {
        // No caching: an unavailable reading must not stick.
        assertNull(BatteryMonitor.percentage(context))

        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 42)

        assertEquals(42, BatteryMonitor.percentage(context))
    }

    @Test
    fun `percentage works without the accessibility service running`() {
        // The reason for reading BatteryManager at all: no accessibility
        // dependency to be missing. Nothing here brings the service up.
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 63)

        assertEquals(63, BatteryMonitor.percentage(context))
    }

    // --- charging ------------------------------------------------------------

    @Test
    fun `charging reports the platform charge state`() {
        shadowBattery.setIsCharging(true)
        assertTrue(BatteryMonitor.charging(context)!!)

        shadowBattery.setIsCharging(false)
        assertFalse(BatteryMonitor.charging(context)!!)
    }

    @Test
    fun `charging is null when there is no battery service`() {
        assertNull(BatteryMonitor.charging(contextWith(null)))
    }

    @Test
    fun `charging is null when the service is not a BatteryManager`() {
        assertNull(BatteryMonitor.charging(contextWith(42)))
    }

    @Test
    fun `charging survives a battery service that throws`() {
        val manager = mockk<BatteryManager>(relaxed = true)
        every { manager.isCharging } throws RuntimeException("binder transaction failed")

        assertNull(BatteryMonitor.charging(contextWith(manager)))
    }

    @Test
    fun `charging is independent of the level reading`() {
        // A phone plugged into a supply too weak to charge reports
        // charging=false with a perfectly readable level. Both must be returned
        // as-is rather than one being inferred from the other.
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 7)
        shadowBattery.setIsCharging(false)

        assertEquals(7, BatteryMonitor.percentage(context))
        assertFalse(BatteryMonitor.charging(context)!!)
    }

    @Test
    fun `an unreadable level does not hide a readable charge state`() {
        // The two reads are independent: /battery can be 503 on the level while
        // charging is still perfectly knowable.
        shadowBattery.setIsCharging(true)

        assertNull(BatteryMonitor.percentage(context))
        assertTrue(BatteryMonitor.charging(context)!!)
    }
}
