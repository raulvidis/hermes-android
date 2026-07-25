package com.hermesandroid.bridge.executor

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
 * Regression tests for the sendIntent safety denylist.
 *
 * sendIntent() forwards a raw action string to startActivity() with no
 * validation, exposing the remote-control bridge to package install/uninstall
 * and factory-reset intents. The fix mirrors the existing sendBroadcast
 * blocklist (PR #87): a set of dangerous activity actions is rejected before
 * the Intent is constructed. These tests pin that contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorSendIntentTest {

    private lateinit var mockService: BridgeAccessibilityService

    @Before
    fun setup() {
        mockService = mockk(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns mockService
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `sendIntent rejects empty action`() {
        val result = ActionExecutor.sendIntent(action = "")
        assertFalse(result.success)
        assertEquals("Intent action is empty", result.message)
        // startActivity must never be reached.
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks INSTALL_PACKAGE`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.INSTALL_PACKAGE")
        assertFalse(result.success)
        assertTrue(result.message.contains("blocked for safety"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks DELETE package uninstall`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.DELETE")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks UNINSTALL_PACKAGE`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.UNINSTALL_PACKAGE")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks MASTER_CLEAR factory reset`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.MASTER_CLEAR")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks FACTORY_RESET`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.FACTORY_RESET")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks ACTION_SHUTDOWN`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.ACTION_SHUTDOWN")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent allows a benign settings action and forwards to startActivity`() {
        every { mockService.startActivity(any()) } returns Unit
        val result = ActionExecutor.sendIntent(action = "android.settings.WIFI_SETTINGS")
        assertTrue("expected success — got: ${result.message}", result.success)
        verify(exactly = 1) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent allows a custom deep-link action and forwards to startActivity`() {
        every { mockService.startActivity(any()) } returns Unit
        val result = ActionExecutor.sendIntent(action = "com.example.app.OPEN_DETAIL")
        assertTrue("expected success — got: ${result.message}", result.success)
        verify(exactly = 1) { mockService.startActivity(any()) }
    }
}
