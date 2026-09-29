package com.hermesandroid.bridge.executor

import android.app.Activity
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review L2: `android_send_sms` reported success for messages that were never sent.
 *
 * `SmsManager.sendTextMessage` returns as soon as the message is *queued*, not
 * when it is sent — the only way to know is the `sentIntent` PendingIntent the
 * platform fires with a result code, and both `sentIntent` and `deliveryIntent`
 * were passed as null. `sendSms` then returned `ActionResult(true, "SMS sent")`
 * unconditionally. Because the recipient and body are stripped from the response
 * by design, there was nothing in the tool output to contradict the claim: the
 * agent sent a 300-character SMS, was told it was delivered, and told the user so.
 *
 * These are plain JVM tests — [SmsSendReport] touches no Android runtime, only
 * compile-time constants — so the timeout bound and the result mapping are pinned
 * without a radio.
 */
class SmsSendReportTest {

    private val timeout = 250L

    @Test
    fun `no report within the timeout yields null, and does not block longer`() {
        val report = SmsSendReport()

        val started = System.nanoTime()
        val result = report.await(timeout)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNull("an absent send report must not be reported as success", result)
        assertTrue(
            "the wait must be bounded by the timeout, took ${elapsedMs}ms for a ${timeout}ms budget",
            elapsedMs < timeout + 2_000L,
        )
    }

    @Test
    fun `a single ok report is returned`() {
        val report = SmsSendReport()

        report.onResult(Activity.RESULT_OK)

        assertEquals(Activity.RESULT_OK, report.await(timeout))
    }

    @Test
    fun `a failure wins over a success that arrives later`() {
        val report = SmsSendReport(expectedReports = 2)

        report.onResult(SmsManager.RESULT_ERROR_RADIO_OFF)
        report.onResult(Activity.RESULT_OK)

        assertEquals(
            "one failed part means the message was not sent",
            SmsManager.RESULT_ERROR_RADIO_OFF,
            report.await(timeout),
        )
    }

    @Test
    fun `a failure is returned immediately without waiting out the timeout`() {
        val report = SmsSendReport(expectedReports = 3)
        report.onResult(SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        val started = System.nanoTime()
        val result = report.await(30_000L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, result)
        assertTrue("a known failure must not stall the request, took ${elapsedMs}ms", elapsedMs < 5_000L)
    }

    @Test
    fun `every part of a multipart send must report ok`() {
        val report = SmsSendReport(expectedReports = 3)

        report.onResult(Activity.RESULT_OK)
        report.onResult(Activity.RESULT_OK)

        assertNull("two of three parts confirmed is not a confirmed send", report.await(timeout))
    }

    @Test
    fun `a fully reported multipart send succeeds`() {
        val report = SmsSendReport(expectedReports = 3)

        repeat(3) { report.onResult(Activity.RESULT_OK) }

        assertEquals(Activity.RESULT_OK, report.await(timeout))
    }

    @Test
    fun `a report arriving before the wait starts is not lost`() {
        val report = SmsSendReport()
        report.onResult(Activity.RESULT_OK)

        assertEquals(Activity.RESULT_OK, report.await(timeout))
    }

    // ── Mapping to what the tool says ────────────────────────────────────────

    @Test
    fun `an absent report is a failure, not a silent success`() {
        val result = SmsSendReport.describe(null, parts = 1, timeoutMs = 10_000L)

        assertFalse(result.success)
        assertTrue(
            "the message must say delivery is unconfirmed, got: ${result.message}",
            result.message.contains("unconfirmed", ignoreCase = true),
        )
    }

    @Test
    fun `a confirmed single-part send keeps the existing message`() {
        val result = SmsSendReport.describe(Activity.RESULT_OK, parts = 1, timeoutMs = 10_000L)

        assertTrue(result.success)
        assertEquals("SMS sent", result.message)
    }

    @Test
    fun `a confirmed multipart send reports the segment count`() {
        val result = SmsSendReport.describe(Activity.RESULT_OK, parts = 3, timeoutMs = 10_000L)

        assertTrue(result.success)
        assertTrue("segment count is useful and is not PII: ${result.message}", result.message.contains("3"))
    }

    @Test
    fun `radio failures name the reason`() {
        val reasons = mapOf(
            SmsManager.RESULT_ERROR_GENERIC_FAILURE to "generic",
            SmsManager.RESULT_ERROR_RADIO_OFF to "radio off",
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED to "limit",
            SmsManager.RESULT_ENCODING_ERROR to "encoding",
        )
        for ((code, expected) in reasons) {
            val result = SmsSendReport.describe(code, parts = 1, timeoutMs = 10_000L)
            assertFalse("code $code must not report success", result.success)
            assertTrue(
                "code $code should say '$expected', got: ${result.message}",
                result.message.contains(expected, ignoreCase = true),
            )
        }
    }

    @Test
    fun `an unknown radio code still fails closed`() {
        val result = SmsSendReport.describe(9_999, parts = 1, timeoutMs = 10_000L)

        assertFalse(result.success)
        assertTrue(result.message.isNotEmpty())
    }

    @Test
    fun `no outcome echoes a recipient or a body`() {
        // AGENTS.md: strip phone numbers, recipients and message content from tool
        // responses. describe() is given neither, so this pins that it stays that
        // way — a future change that threads the address through would fail here.
        val outcomes = listOf(
            SmsSendReport.describe(null, 1, 10_000L),
            SmsSendReport.describe(Activity.RESULT_OK, 1, 10_000L),
            SmsSendReport.describe(Activity.RESULT_OK, 4, 10_000L),
            SmsSendReport.describe(SmsManager.RESULT_ERROR_RADIO_OFF, 1, 10_000L),
            SmsSendReport.describe(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED, 2, 10_000L),
            SmsSendReport.describe(9_999, 1, 10_000L),
        )
        for (outcome in outcomes) {
            assertFalse(
                "a phone-number-shaped string leaked into '${outcome.message}'",
                Regex("""\+?\d{6,}""").containsMatchIn(outcome.message),
            )
        }
    }
}
