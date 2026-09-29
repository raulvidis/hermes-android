package com.hermesandroid.bridge.server

import android.content.Context
import android.os.BatteryManager
import com.google.gson.JsonObject
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.bridgeTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBatteryManager

/**
 * Contract tests for GET /battery.
 *
 * The endpoint must report the platform's own battery values, never anything
 * scraped from the status bar, and must never invent a number: an unreadable
 * level is a 503, not a 0 and not a guess. A caller that alarms on low battery
 * is worse off with a plausible wrong value than with an honest failure.
 *
 * The response shape is fixed — `batteryPercentage` and `charging`, both
 * present, both non-null — because the two transports serialise differently:
 * BridgeServer's Gson calls serializeNulls(), RelayClient's default Gson drops
 * nulls. A nullable field would therefore appear over HTTP and vanish over the
 * relay, so "unknown" is expressed as a 503 rather than as a null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommandDispatcherBatteryTest {

    private lateinit var shadowBattery: ShadowBatteryManager

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        // BridgeApplication.instance is published from Application.onCreate,
        // which Robolectric does not run — and its setter is private, so it
        // cannot simply be assigned. That is what the dispatcher's
        // batteryContext seam is for.
        CommandDispatcher.batteryContext = { context }
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        // Shadow.extract rather than Shadows.shadowOf: it works for any
        // @Implements-annotated shadow without depending on a generated
        // overload existing for this particular type.
        shadowBattery = Shadow.extract<ShadowBatteryManager>(manager)
    }

    @After
    fun tearDown() {
        CommandDispatcher.batteryContext = { BridgeApplication.instance }
    }

    private suspend fun battery(): Pair<Any, Int> =
        CommandDispatcher.dispatch("GET", "/battery", JsonObject(), JsonObject(), true)

    @Suppress("UNCHECKED_CAST")
    private fun fields(rawResult: Any): Map<String, Any> = rawResult as Map<String, Any>

    @Test
    fun `battery reports the system percentage and charge state`() = bridgeTest {
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 87)
        shadowBattery.setIsCharging(true)

        val (rawResult, status) = battery()

        assertEquals(200, status)
        val result = fields(rawResult)
        assertEquals(87, result["batteryPercentage"])
        assertEquals(true, result["charging"])
    }

    @Test
    fun `battery reports a flat charge state with exactly the same keys`() = bridgeTest {
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 42)
        shadowBattery.setIsCharging(false)

        val (rawResult, status) = battery()

        assertEquals(200, status)
        val result = fields(rawResult)
        // Shape must not vary with the values: a consumer that checks
        // "charging" in the payload has to be able to rely on it being there.
        assertEquals(setOf("batteryPercentage", "charging"), result.keys)
        assertEquals(false, result["charging"])
    }

    @Test
    fun `battery is unavailable when the platform reports no level`() = bridgeTest {
        // Nothing set. The shadow answers Integer.MIN_VALUE for an unset
        // property, which is exactly what the real getIntProperty reports on
        // failure when targetSdkVersion >= P — and this app targets 34.
        shadowBattery.setIsCharging(true)

        val (rawResult, status) = battery()

        assertEquals(503, status)
        assertEquals("Battery state unavailable on this device", fields(rawResult)["error"])
    }

    @Test
    fun `battery rejects an out-of-range level instead of reporting it`() = bridgeTest {
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 999)
        shadowBattery.setIsCharging(false)

        val (_, status) = battery()

        assertEquals(503, status)
    }

    @Test
    fun `battery works without the accessibility service running`() = bridgeTest {
        // The whole point of reading BatteryManager instead of the status-bar
        // node: no accessibility dependency to be missing. Nothing in this
        // test brings the service up, and the call still succeeds.
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 63)
        shadowBattery.setIsCharging(true)

        val (rawResult, status) = battery()

        assertEquals(200, status)
        assertEquals(63, fields(rawResult)["batteryPercentage"])
    }
}
