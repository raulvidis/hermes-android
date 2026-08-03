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
    fun `sendIntent allows a benign view action and forwards to startActivity`() {
        every { mockService.startActivity(any()) } returns Unit
        val result = ActionExecutor.sendIntent(action = "android.intent.action.VIEW")
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

    // --- Expanded blocklist tests (CALL, CALL_PRIVILEGED, settings) ---

    @Test
    fun `sendIntent blocks CALL`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.CALL")
        assertFalse(result.success)
        assertTrue(result.message.contains("blocked for safety"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks CALL_PRIVILEGED`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.CALL_PRIVILEGED")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks settings accessibility`() {
        val result = ActionExecutor.sendIntent(action = "android.settings.ACCESSIBILITY_SETTINGS")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks settings manage overlay`() {
        val result = ActionExecutor.sendIntent(action = "android.settings.MANAGE_OVERLAY_PERMISSION")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks settings security`() {
        val result = ActionExecutor.sendIntent(action = "android.settings.SECURITY_SETTINGS")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks settings biometric enroll`() {
        val result = ActionExecutor.sendIntent(action = "android.settings.BIOMETRIC_ENROLL")
        assertFalse(result.success)
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks unknown settings prefix`() {
        val result = ActionExecutor.sendIntent(action = "android.settings.UNKNOWN_FUTURE_SETTING")
        assertFalse(result.success)
        assertTrue(result.message.contains("settings/provider actions are not allowed"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks provider action prefix`() {
        val result = ActionExecutor.sendIntent(action = "android.provider.action.SOME_ACTION")
        assertFalse(result.success)
        assertTrue(result.message.contains("settings/provider actions are not allowed"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    // --- URI scheme denylist tests ---

    @Test
    fun `sendIntent blocks tel URI scheme`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.VIEW", dataUri = "tel:123456")
        assertFalse(result.success)
        assertTrue(result.message.contains("URI scheme 'tel' is blocked"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks smsto URI scheme`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.VIEW", dataUri = "smsto:5551234")
        assertFalse(result.success)
        assertTrue(result.message.contains("URI scheme 'smsto' is blocked"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks intent redirect URI`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.VIEW", dataUri = "intent://example.com/#Intent;scheme=http;end")
        assertFalse(result.success)
        assertTrue(result.message.contains("URI scheme 'intent' is blocked"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks market URI scheme`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.VIEW", dataUri = "market://details?id=com.example")
        assertFalse(result.success)
        assertTrue(result.message.contains("URI scheme 'market' is blocked"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks content settings URI prefix`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.VIEW", dataUri = "content://settings/secure")
        assertFalse(result.success)
        assertTrue(result.message.contains("Content provider URI is blocked"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks content contacts URI prefix`() {
        val result = ActionExecutor.sendIntent(action = "android.intent.action.VIEW", dataUri = "content://com.android.contacts/contacts")
        assertFalse(result.success)
        assertTrue(result.message.contains("Content provider URI is blocked"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent error message does not contain double-colon for single-colon scheme`() {
        // The error message for tel: should say "URI scheme 'tel' is blocked" not "URI scheme 'tel://'"
        val result = ActionExecutor.sendIntent(action = "android.intent.action.VIEW", dataUri = "tel:123456")
        assertFalse(result.success)
        assertFalse("message should not contain '://' for single-colon schemes: ${result.message}",
            result.message.contains("://"))
    }

    @Test
    fun `sendIntent allows https URI with benign action`() {
        every { mockService.startActivity(any()) } returns Unit
        val result = ActionExecutor.sendIntent(action = "android.intent.action.VIEW", dataUri = "https://example.com")
        assertTrue("expected success — got: ${result.message}", result.success)
        verify(exactly = 1) { mockService.startActivity(any()) }
    }
}
