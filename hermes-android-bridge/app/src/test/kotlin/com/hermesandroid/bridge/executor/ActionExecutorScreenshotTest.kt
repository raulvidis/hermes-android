package com.hermesandroid.bridge.executor

import com.hermesandroid.bridge.bridgeTest
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterization tests for [ActionExecutor.takeScreenshot], captured before
 * the executor split.
 *
 * The pre-Android-11 guard (SDK < 30) is not covered here: no API-28
 * Robolectric jar is cached in this environment and network access is
 * unavailable. It is a four-line guard that moves verbatim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorScreenshotTest {

    @Before
    fun setup() {
        mockkObject(BridgeAccessibilityService.Companion)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `takeScreenshot fails without the accessibility service`() = bridgeTest {
        every { BridgeAccessibilityService.instance } returns null

        val result = ActionExecutor.takeScreenshot()

        assertFalse(result.success)
        assertEquals("Accessibility service not running", result.message)
    }
}
