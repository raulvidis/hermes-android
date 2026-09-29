package com.hermesandroid.bridge.executor

import android.content.Intent
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
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
 * CTRL-2 regression tests: the `sendIntent` data-URI denylist.
 *
 * Three concrete gaps in the previous list:
 *  1. `sms:` was missing while `smsto:`/`mmsto:` were present, so
 *     `SENDTO` + `data_uri="sms:+19005550100"` reached `startActivity`;
 *  2. only two `content://` prefixes were blocked, so SMS/MMS/call-log/telephony
 *     and the other contacts authorities were all accepted;
 *  3. nothing was trimmed, so a leading space defeated scheme extraction
 *     entirely (`" tel:+1900…"` → scheme `" tel"`) and the case-sensitive
 *     `content://` prefix check missed `"CONTENT://sms"`.
 *
 * Table-driven, with the bypass attempts (leading whitespace, mixed case) as
 * first-class rows rather than afterthoughts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorSendIntentUriTest {

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

    private companion object {
        const val VIEW = "android.intent.action.VIEW"

        /** URI → why it must be blocked. */
        val BLOCKED: List<Pair<String, String>> = listOf(
            // telephony, incl. the whitespace/case bypasses
            "tel:+19005550100" to "single-colon telephony scheme",
            " tel:+19005550100" to "leading space",
            "\ttel:+19005550100" to "leading tab",
            "\ntel:+19005550100" to "leading newline",
            "TEL:+19005550100" to "upper-case scheme",
            "Tel:+19005550100" to "mixed-case scheme",
            "tel :+19005550100" to "space before the colon",
            // SMS/MMS — the headline gap: sms: was missing entirely
            "sms:+19005550100" to "sms scheme",
            "SMS:+19005550100" to "sms scheme, upper case",
            " sms:+19005550100" to "sms scheme, leading space",
            "smsto:+19005550100" to "smsto scheme",
            "mms:+19005550100" to "mms scheme",
            "mmsto:+19005550100" to "mmsto scheme",
            // redirects / store deep links (already blocked — pinned here)
            "market://details?id=com.example" to "market deep link",
            "intent://example.com/#Intent;scheme=http;end" to "intent redirect",
            "MARKET://details?id=com.example" to "market deep link, upper case",
            // personal-data content providers
            "content://sms/inbox" to "SMS provider",
            "content://SMS/inbox" to "SMS provider, upper case",
            "  content://sms/inbox" to "SMS provider, leading spaces",
            "content://mms/1" to "MMS provider",
            "content://mms-sms/conversations" to "MMS-SMS provider",
            "content://call_log/calls" to "call log provider",
            "content://telephony/carriers" to "telephony provider",
            "content://contacts/people" to "contacts provider",
            "content://icc/adn" to "SIM contacts",
            "content://settings/secure" to "settings provider",
            "content://com.android.contacts/data" to "AOSP contacts provider",
            "content://com.android.calllog/calls" to "AOSP call log provider",
            "content://com.android.mms/1" to "AOSP MMS provider",
            "content://com.android.mms-sms/conversations" to "AOSP MMS-SMS provider",
            "content://com.android.providers.telephony/sms" to "AOSP telephony authority",
            "content://com.android.providers.contacts/contacts" to "AOSP contacts authority"
        )

        /** URI → why it must still work. */
        val ALLOWED: List<Pair<String, String>> = listOf(
            "https://example.com/page" to "plain https deep link",
            "http://example.com/page" to "plain http deep link",
            "HTTPS://EXAMPLE.COM/page" to "upper case is not a bypass in the safe direction",
            "geo:37.422,-122.084" to "map link",
            "content://com.example.notes/items/1" to "an app's own provider",
            "example://open?id=42" to "custom app scheme"
        )
    }

    @Test
    fun `every denylisted URI is rejected before startActivity`() {
        val failures = mutableListOf<String>()

        for ((uri, why) in BLOCKED) {
            val result = ActionExecutor.sendIntent(action = VIEW, dataUri = uri)
            when {
                result.success -> failures.add("$uri ($why): was ALLOWED")
                !result.message.contains("blocked for safety") ->
                    failures.add("$uri ($why): unexpected message ${result.message}")
            }
        }

        verify(exactly = 0) { mockService.startActivity(any()) }
        assertTrue("denylist gaps:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `benign URIs still reach startActivity`() {
        val failures = mutableListOf<String>()

        for ((uri, why) in ALLOWED) {
            val result = ActionExecutor.sendIntent(action = VIEW, dataUri = uri)
            if (!result.success) failures.add("$uri ($why): ${result.message}")
        }

        assertTrue("over-blocking:\n" + failures.joinToString("\n"), failures.isEmpty())
        verify(exactly = ALLOWED.size) { mockService.startActivity(any()) }
    }

    @Test
    fun `the sms scheme is blocked for SENDTO, the audit's concrete example`() {
        val result = ActionExecutor.sendIntent(
            action = "android.intent.action.SENDTO",
            dataUri = "sms:+19005550100",
            extras = mapOf("sms_body" to "hi")
        )

        assertFalse(result.success)
        assertTrue(result.message.contains("URI scheme 'sms' is blocked"))
        verify(exactly = 0) { mockService.startActivity(any()) }
    }

    @Test
    fun `a leading-whitespace URI cannot slip past the scheme check`() {
        val result = ActionExecutor.sendIntent(action = VIEW, dataUri = " tel:+19005550100")

        assertFalse(result.success)
        // The reported scheme is the trimmed, lower-cased one — no stray whitespace.
        assertTrue("got: ${result.message}", result.message.contains("URI scheme 'tel' is blocked"))
        assertFalse("got: ${result.message}", result.message.contains("' tel'"))
    }

    @Test
    fun `the message never contains a double colon for single-colon schemes`() {
        val result = ActionExecutor.sendIntent(action = VIEW, dataUri = "sms:5551234")

        assertFalse(result.success)
        assertFalse("got: ${result.message}", result.message.contains("://"))
    }

    @Test
    fun `the trimmed URI is what reaches the intent`() {
        val captured = slot<Intent>()
        every { mockService.startActivity(capture(captured)) } returns Unit

        val result = ActionExecutor.sendIntent(action = VIEW, dataUri = "  https://example.com/page  ")

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("https://example.com/page", captured.captured.dataString)
    }

    @Test
    fun `a null data URI is unaffected`() {
        val result = ActionExecutor.sendIntent(action = VIEW)

        assertTrue("expected success — got: ${result.message}", result.success)
        verify(exactly = 1) { mockService.startActivity(any()) }
    }
}
