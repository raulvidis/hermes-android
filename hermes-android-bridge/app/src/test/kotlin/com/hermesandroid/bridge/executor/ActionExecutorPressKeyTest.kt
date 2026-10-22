package com.hermesandroid.bridge.executor

import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the pressKey "wake" branch (PR #101).
 *
 * Before #101, waking the screen required pressKey("power"), which maps to
 * GLOBAL_ACTION_POWER_DIALOG — the long-press reboot/emergency menu. "wake"
 * turns the screen on via WakeLockManager instead. These tests pin that
 * contract: wake never reaches performGlobalAction, unsupported keys are
 * rejected with a clear message, and normal keys still dispatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorPressKeyTest {

    private lateinit var mockService: BridgeAccessibilityService

    @Before
    fun setup() {
        mockService = mockk(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns mockService
        mockkObject(WakeLockManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `wake turns screen on via WakeLockManager, not a global action`() {
        every { WakeLockManager.wake() } returns true

        val result = ActionExecutor.pressKey("wake")

        assertTrue(result.success)
        assertEquals("Woke screen", result.message)
        // wake must NOT dispatch a global action (power dialog, etc.)
        verify(exactly = 0) { mockService.performGlobalAction(any()) }
    }

    @Test
    fun `wake reports failure when PowerManager unavailable`() {
        every { WakeLockManager.wake() } returns false

        val result = ActionExecutor.pressKey("wake")

        assertFalse(result.success)
        assertTrue(result.message.contains("Wake failed"))
        verify(exactly = 0) { mockService.performGlobalAction(any()) }
    }

    @Test
    fun `wake works without the accessibility service running`() {
        // wake only needs PowerManager, so a down accessibility service must
        // not short-circuit it with "Accessibility service not running".
        every { BridgeAccessibilityService.instance } returns null
        every { WakeLockManager.wake() } returns true

        val result = ActionExecutor.pressKey("wake")

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Woke screen", result.message)
    }

    @Test
    fun `non-wake keys still require the accessibility service`() {
        every { BridgeAccessibilityService.instance } returns null

        val result = ActionExecutor.pressKey("back")

        assertFalse(result.success)
        assertEquals("Accessibility service not running", result.message)
    }

    @Test
    fun `power still opens the power dialog`() {
        every { mockService.performGlobalAction(any()) } returns true

        val result = ActionExecutor.pressKey("power")

        assertTrue(result.success)
        verify(exactly = 1) {
            mockService.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            )
        }
        verify(exactly = 0) { WakeLockManager.wake() }
    }

    @Test
    fun `unsupported keyboard keys are rejected with a clear message`() {
        for (key in listOf("volume_up", "volume_down", "enter", "delete", "tab", "escape", "search")) {
            val result = ActionExecutor.pressKey(key)
            assertFalse(result.success)
            assertTrue(result.message.contains("not supported"))
        }
        verify(exactly = 0) { mockService.performGlobalAction(any()) }
    }

    @Test
    fun `unknown key is rejected`() {
        val result = ActionExecutor.pressKey("explode")

        assertFalse(result.success)
        assertEquals("Unknown key: explode", result.message)
        verify(exactly = 0) { mockService.performGlobalAction(any()) }
    }

    @Test
    fun `back dispatches GLOBAL_ACTION_BACK`() {
        every { mockService.performGlobalAction(any()) } returns true

        val result = ActionExecutor.pressKey("back")

        assertTrue(result.success)
        verify(exactly = 1) {
            mockService.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
        }
    }
}
