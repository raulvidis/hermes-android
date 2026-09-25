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

    // --- Expanded denylist: SMS/MMS/SIP schemes, Play Store web links,
    // content authorities, credential/admin actions, blocked packages ---

    private fun assertIntentBlocked(
        action: String = "android.intent.action.VIEW",
        dataUri: String? = null,
        packageOverride: String? = null,
        expectMessage: String? = null,
    ) {
        val result = ActionExecutor.sendIntent(
            action = action, dataUri = dataUri, packageOverride = packageOverride
        )
        assertFalse("expected block for action=$action uri=$dataUri pkg=$packageOverride", result.success)
        if (expectMessage != null) {
            assertTrue("message was: ${result.message}", result.message.contains(expectMessage))
        }
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks sms and mms URI schemes`() {
        assertIntentBlocked(dataUri = "sms:5551234", expectMessage = "URI scheme 'sms' is blocked")
        assertIntentBlocked(dataUri = "mms:5551234", expectMessage = "URI scheme 'mms' is blocked")
        assertIntentBlocked(dataUri = "mmsto:5551234", expectMessage = "URI scheme 'mmsto' is blocked")
    }

    @Test
    fun `sendIntent blocks sip voicemail and wtai URI schemes`() {
        assertIntentBlocked(dataUri = "sip:alice@example.com", expectMessage = "URI scheme 'sip' is blocked")
        assertIntentBlocked(dataUri = "voicemail:", expectMessage = "URI scheme 'voicemail' is blocked")
        assertIntentBlocked(dataUri = "wtai://wp/mc;5551234", expectMessage = "URI scheme 'wtai' is blocked")
    }

    @Test
    fun `sendIntent blocks schemes regardless of case`() {
        assertIntentBlocked(dataUri = "SMS:5551234", expectMessage = "URI scheme 'sms' is blocked")
        assertIntentBlocked(dataUri = "Tel:5551234", expectMessage = "URI scheme 'tel' is blocked")
    }

    @Test
    fun `sendIntent blocks Play Store web links`() {
        assertIntentBlocked(
            dataUri = "https://play.google.com/store/apps/details?id=com.example",
            expectMessage = "Play Store links are blocked"
        )
        assertIntentBlocked(
            dataUri = "http://PLAY.GOOGLE.COM/store/apps/details?id=com.example",
            expectMessage = "Play Store links are blocked"
        )
        assertIntentBlocked(
            dataUri = "https://market.android.com/details?id=com.example",
            expectMessage = "Play Store links are blocked"
        )
    }

    @Test
    fun `sendIntent allows non-store pages on play google com`() {
        every { mockService.startActivity(any()) } returns Unit
        val result = ActionExecutor.sendIntent(
            action = "android.intent.action.VIEW",
            dataUri = "https://play.google.com/about/developer-content-policy/"
        )
        assertTrue("expected success — got: ${result.message}", result.success)
        verify(exactly = 1) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent blocks sensitive content authorities`() {
        for (uri in listOf(
            "content://contacts/people",
            "content://call_log/calls",
            "content://sms/inbox",
            "content://mms/part",
            "content://mms-sms/conversations",
            "content://icc/adn",
        )) {
            assertIntentBlocked(dataUri = uri, expectMessage = "Content provider URI is blocked")
        }
    }

    @Test
    fun `sendIntent content authority check is case-insensitive and ignores a user prefix`() {
        assertIntentBlocked(dataUri = "content://CALL_LOG/calls", expectMessage = "Content provider URI is blocked")
        assertIntentBlocked(
            dataUri = "content://10@com.android.contacts/contacts",
            expectMessage = "Content provider URI is blocked"
        )
    }

    @Test
    fun `sendIntent allows an unrelated content authority`() {
        every { mockService.startActivity(any()) } returns Unit
        val result = ActionExecutor.sendIntent(
            action = "android.intent.action.VIEW",
            dataUri = "content://com.example.provider/items/1"
        )
        assertTrue("expected success — got: ${result.message}", result.success)
    }

    @Test
    fun `sendIntent blocks password credential and device admin actions`() {
        for (action in listOf(
            "android.app.action.SET_NEW_PASSWORD",
            "android.app.action.SET_NEW_PARENT_PROFILE_PASSWORD",
            "android.app.action.ADD_DEVICE_ADMIN",
            "android.app.action.PROVISION_MANAGED_PROFILE",
            "android.credentials.INSTALL",
            "android.credentials.RESET",
            "android.provider.Telephony.ACTION_CHANGE_DEFAULT",
            "android.app.role.action.REQUEST_ROLE",
            "android.intent.action.REBOOT",
        )) {
            assertIntentBlocked(action = action, expectMessage = "blocked for safety")
        }
    }

    @Test
    fun `sendIntent blocks targeting the Settings package`() {
        assertIntentBlocked(
            action = "android.intent.action.MAIN",
            packageOverride = "com.android.settings",
            expectMessage = "Target package 'com.android.settings' is blocked"
        )
        assertIntentBlocked(
            action = "android.intent.action.VIEW",
            packageOverride = "com.google.android.permissioncontroller",
            expectMessage = "is blocked for safety"
        )
    }

    @Test
    fun `sendIntent allows an ordinary package override`() {
        every { mockService.startActivity(any()) } returns Unit
        val result = ActionExecutor.sendIntent(
            action = "android.intent.action.VIEW",
            dataUri = "https://example.com",
            packageOverride = "com.android.chrome"
        )
        assertTrue("expected success — got: ${result.message}", result.success)
        verify(exactly = 1) { mockService.startActivity(any()) }
    }

    @Test
    fun `sendIntent turns a startActivity failure into an error result`() {
        every { mockService.startActivity(any()) } throws RuntimeException("no activity")
        val result = ActionExecutor.sendIntent(action = "com.example.app.OPEN_DETAIL")
        assertFalse(result.success)
        assertTrue(result.message.contains("Intent failed"))
    }

    // --- sendBroadcast ---

    @Test
    fun `sendBroadcast blocks a listed system broadcast`() {
        val result = ActionExecutor.sendBroadcast("android.intent.action.FACTORY_RESET")
        assertFalse(result.success)
        assertTrue(result.message.contains("blocked for safety"))
        verify(exactly = 0) { mockService.sendBroadcast(any()) }
    }

    @Test
    fun `sendBroadcast turns any exception into an error result`() {
        // Not just SecurityException: anything thrown must become a failed
        // ActionResult, never escape to the transport as a 500.
        every { mockService.sendBroadcast(any()) } throws IllegalArgumentException("bad extras")
        val result = ActionExecutor.sendBroadcast("com.example.app.PING")
        assertFalse(result.success)
        assertEquals("Broadcast failed: bad extras", result.message)
    }

    @Test
    fun `sendBroadcast sends an unlisted custom action`() {
        every { mockService.sendBroadcast(any()) } returns Unit
        val result = ActionExecutor.sendBroadcast("com.example.app.PING", mapOf("k" to "v"))
        assertTrue("expected success — got: ${result.message}", result.success)
        verify(exactly = 1) { mockService.sendBroadcast(any()) }
    }
}
