package com.hermesandroid.bridge.server

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.os.Build
import com.google.gson.JsonObject
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract tests for the GET /battery endpoint.
 *
 * Battery level must come from AccessibilityService.getBatteryPercentage() (API 30+),
 * NOT from parsing status-bar text — OEM text formats differ by language and skin
 * ("电量剩余 53。", "電池電量為百分之 87。", "87%", …) and any parser built from
 * samples will eventually mis-read. These tests pin that contract: the endpoint
 * returns the system percentage verbatim with no auth requirement, and reports a
 * clean error when the accessibility service is absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommandDispatcherBatteryTest {

    private lateinit var mockService: BridgeAccessibilityService

    @Before
    fun setup() {
        mockService = mockk(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns mockService
        val config = mockk<Configuration>()
        val info = mockk<AccessibilityServiceInfo>(relaxed = true)
        every { mockService.batteryPercentage } returns 87
        every { mockService.resources.configuration } returns config
        every { config.constants } returns info
        every { info.chargerConnected } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `battery returns the system percentage without authentication`() = runTest {
        val (result, status) = CommandDispatcher.dispatch("GET", "/battery", JsonObject(), JsonObject(), false)

        assertEquals(200, status)
        val map = result as Map<*, *>
        assertEquals(87, map["batteryPercentage"])
        assertEquals(true, map["chargerConnected"])
    }

    @Test
    fun `battery reports 503 when the accessibility service is not running`() = runTest {
        every { BridgeAccessibilityService.instance } returns null

        val (result, status) = CommandDispatcher.dispatch("GET", "/battery", JsonObject(), JsonObject(), true)

        assertEquals(503, status)
        val map = result as Map<*, *>
        assertEquals("Accessibility service not running", map["error"])
    }

    @Test
    @Config(sdk = [30])
    fun `battery reports 501 on pre-Android-12 devices`() = runTest {
        assertEquals(30, Build.VERSION.SDK_INT)

        val (result, status) = CommandDispatcher.dispatch("GET", "/battery", JsonObject(), JsonObject(), true)

        assertEquals(501, status)
        val map = result as Map<*, *>
        assertEquals("Requires Android 12 (API 31)", map["error"])
    }
}
