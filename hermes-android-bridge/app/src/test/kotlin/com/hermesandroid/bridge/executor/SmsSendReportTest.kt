package com.hermesandroid.bridge.executor

import android.app.Activity
import android.telephony.SmsManager
import com.hermesandroid.bridge.model.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `a single-segment failure is returned immediately`() {
        // Nothing else can arrive for a one-segment send, so waiting out the bound
        // would only cost the caller ten seconds.
        val report = SmsSendReport(expectedReports = 1)
        report.onResult(SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        val started = System.nanoTime()
        val result = report.await(30_000L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, result)
        assertTrue("a known failure must not stall the request, took ${elapsedMs}ms", elapsedMs < 5_000L)
    }

    /**
     * Review F23: a multipart send used to answer on the first failed segment, so
     * the confirmed count was whatever had happened to arrive by then. That cannot
     * distinguish "no segment went out" — safe to retry — from "the first two did",
     * where a retry delivers them a second time to the same recipient. The
     * remaining reports are waited for, up to the same bound as everything else.
     */
    @Test
    fun `a multipart failure waits for the remaining segment reports`() {
        val report = SmsSendReport(expectedReports = 3)
        report.onResult(Activity.RESULT_OK)
        report.onResult(SmsManager.RESULT_ERROR_NO_SERVICE)

        val started = System.nanoTime()
        val outcome = report.await(timeout)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE, outcome)
        assertEquals(1, report.snapshot().confirmedParts)
        assertTrue(
            "one segment report is still outstanding, so the count is not final " +
                "yet; waited ${elapsedMs}ms of a ${timeout}ms bound",
            elapsedMs >= timeout - 100L,
        )
    }

    /**
     * Review F29: a failed report never counted the latch down, so the latch only
     * reached zero when every *successful* report had arrived. A three-segment send
     * that got two OKs and one failure — i.e. every report the radio was ever going
     * to send — still sat out the whole bound, because one count was outstanding
     * forever. F23 introduced the wait to make the tally complete; this is what makes
     * it end when the tally actually is.
     */
    @Test
    fun `every segment report is counted before the outcome is decided`() {
        val report = SmsSendReport(expectedReports = 3)
        report.onResult(Activity.RESULT_OK)
        report.onResult(Activity.RESULT_OK)
        report.onResult(SmsManager.RESULT_ERROR_RADIO_OFF)

        val started = System.nanoTime()
        val outcome = report.await(5_000L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(SmsManager.RESULT_ERROR_RADIO_OFF, outcome)
        assertEquals("both confirmed segments must be counted", 2, report.snapshot().confirmedParts)
        assertTrue(
            "all three reports had arrived; waiting ${elapsedMs}ms more is pure latency",
            elapsedMs < 1_000L,
        )
    }

    @Test
    fun `an all-failed multipart send also answers as soon as the last report lands`() {
        val report = SmsSendReport(expectedReports = 3)
        report.onResult(SmsManager.RESULT_ERROR_RADIO_OFF)
        report.onResult(SmsManager.RESULT_ERROR_RADIO_OFF)

        val started = System.nanoTime()
        report.onResult(SmsManager.RESULT_ERROR_NO_SERVICE)
        val outcome = report.await(5_000L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(
            "the first failure code is the one reported",
            SmsManager.RESULT_ERROR_RADIO_OFF,
            outcome,
        )
        assertEquals(0, report.snapshot().confirmedParts)
        assertEquals(3, report.snapshot().reportedParts)
        assertTrue("waited ${elapsedMs}ms for reports that had all arrived", elapsedMs < 1_000L)
    }

    @Test
    fun `reportedParts counts every report, success or failure`() {
        val report = SmsSendReport(expectedReports = 4)
        report.onResult(Activity.RESULT_OK)
        report.onResult(SmsManager.RESULT_ERROR_NO_SERVICE)
        report.onResult(Activity.RESULT_OK)

        val read = report.snapshot()
        assertEquals(2, read.confirmedParts)
        assertEquals(3, read.reportedParts)
    }

    // ── Review F36: the tally is read once, atomically ────────────────────────

    /**
     * The window the review named. `await()` returns when the bound runs out, but
     * the receiver is still registered and the radio can still deliver: a
     * `RESULT_OK` landing between the caller's read of `confirmedParts` and its read
     * of `reportedParts` produced `confirmed = 0, reported = parts`, which is exactly
     * the shape `describe` maps to [SmsSendReport.STATUS_FAILED] — "nothing went out,
     * a retry is safe" — for a message that was partly delivered.
     *
     * The race itself is timing-dependent and cannot be replayed deterministically
     * in a unit test, so what is pinned here is the contract that closes it: the
     * tally is one immutable object, read under the same lock that guards every
     * field, and a report arriving afterwards cannot move it.
     */
    @Test
    fun `a tally snapshot taken before a late report is not moved by it`() {
        val report = SmsSendReport(expectedReports = 2)
        report.onResult(SmsManager.RESULT_ERROR_NO_SERVICE)

        val outcome = report.await(timeout) // bound runs out; one of two reported
        val tally = report.snapshot()
        report.onResult(Activity.RESULT_OK) // the late report

        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE, outcome)
        assertEquals(0, tally.confirmedParts)
        assertEquals(1, tally.reportedParts)
        assertEquals("the snapshot must be immutable", 1, tally.reportedParts)
        assertEquals("while a fresh snapshot does", 2, report.snapshot().reportedParts)

        val safe = SmsSendReport.describe(tally, parts = 2, timeoutMs = timeout)
        assertTrue(
            "a partly delivered message must never be called retry-safe: ${safe.message}",
            safe.success,
        )
        assertEquals("queued_unconfirmed", statusOf(safe))

        // The split read the old call site performed, for contrast: confirmedParts
        // taken before the late report and reportedParts after it.
        val split = SmsSendReport.describe(
            outcome,
            parts = 2,
            timeoutMs = timeout,
            confirmedParts = 0,
            reportedParts = 2,
        )
        assertFalse(split.success)
        assertEquals("failed", statusOf(split))
    }

    @Test
    fun `awaitTally returns the same answer await does, in one read`() {
        val report = SmsSendReport(expectedReports = 3)
        report.onResult(Activity.RESULT_OK)
        report.onResult(Activity.RESULT_OK)
        report.onResult(Activity.RESULT_OK)

        val tally = report.awaitTally(timeout)

        assertEquals(Activity.RESULT_OK, tally.outcomeCode)
        assertEquals(Activity.RESULT_OK, report.await(timeout))
        assertEquals(3, tally.confirmedParts)
        assertEquals(3, tally.reportedParts)
        assertTrue(tally.everySegmentReported)
        assertEquals("sent", statusOf(SmsSendReport.describe(tally, parts = 3, timeoutMs = timeout)))
    }

    @Test
    fun `a tally whose bound ran out reports itself incomplete`() {
        val report = SmsSendReport(expectedReports = 3)
        report.onResult(Activity.RESULT_OK)

        val tally = report.awaitTally(timeout)

        assertNull("no failure and not every segment reported", tally.outcomeCode)
        assertFalse(tally.everySegmentReported)
        assertEquals(1, tally.confirmedParts)
        assertEquals(1, tally.reportedParts)
        assertEquals(
            "queued_unconfirmed",
            statusOf(SmsSendReport.describe(tally, parts = 3, timeoutMs = timeout)),
        )
    }

    @Test
    fun `reportedParts never disagrees with confirmedParts`() {
        // The old getter was `confirmed.get() + rejected.get()` — two atomic reads,
        // so a report landing between them made the sum describe two different
        // instants. One lock, one read.
        val report = SmsSendReport(expectedReports = 500)
        val writer = Thread {
            repeat(500) { index ->
                report.onResult(
                    if (index % 2 == 0) Activity.RESULT_OK
                    else SmsManager.RESULT_ERROR_NO_SERVICE,
                )
            }
        }
        writer.start()
        var violations = 0
        val deadline = System.nanoTime() + 5_000_000_000L
        while (writer.isAlive && System.nanoTime() < deadline) {
            val tally = report.snapshot()
            if (tally.confirmedParts > tally.reportedParts ||
                tally.reportedParts > 500 ||
                tally.confirmedParts < 0
            ) {
                violations++
            }
        }
        writer.join(5_000L)

        assertEquals("an inconsistent tally was observed $violations times", 0, violations)
        val final = report.snapshot()
        assertEquals(500, final.reportedParts)
        assertEquals(250, final.confirmedParts)
    }

    @Test
    fun `a single-segment failure is still immediate`() {
        val report = SmsSendReport(expectedReports = 1)
        report.onResult(SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(1, report.snapshot().reportedParts)
        assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, report.await(timeout))
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

    /**
     * Review F14: an absent send report used to be `success = false`, which is the
     * one answer guaranteed to cause a duplicate. The message *was* handed to the
     * radio — `sendTextMessage` returned without throwing — and the report simply
     * did not arrive inside the window, which is what a device on one bar of signal
     * does routinely. An agent reading `success: false` retries, and the recipient
     * gets the same SMS twice (or three times, when the retry also times out).
     * "I cannot confirm it" is not "it failed", and the two must not share a flag
     * the caller treats as "do it again".
     */
    @Test
    fun `an absent report is queued-unconfirmed, not a retryable failure`() {
        val result = SmsSendReport.describe(null, parts = 1, timeoutMs = 10_000L)

        assertTrue(
            "success=false invites a retry, and the recipient gets the message twice",
            result.success,
        )
        assertEquals("queued_unconfirmed", statusOf(result))
        assertTrue(
            "the message must still say it is unconfirmed, got: ${result.message}",
            result.message.contains("unconfirmed", ignoreCase = true),
        )
    }

    @Test
    fun `the unconfirmed message forbids resending and says why`() {
        val message = SmsSendReport.describe(null, parts = 1, timeoutMs = 10_000L).message

        assertTrue(
            "an agent must be told not to retry, got: $message",
            message.contains("do not resend", ignoreCase = true),
        )
        assertTrue(
            "and told what a retry would do, got: $message",
            message.contains("duplicate", ignoreCase = true),
        )
    }

    @Test
    fun `a partly reported multipart send is unconfirmed too, not failed`() {
        // Two of three segments confirmed, the third never reported. Resending
        // would duplicate the two that definitely went out.
        val report = SmsSendReport(expectedReports = 3)
        report.onResult(Activity.RESULT_OK)
        report.onResult(Activity.RESULT_OK)

        val result = SmsSendReport.describe(report.await(timeout), parts = 3, timeoutMs = timeout)

        assertTrue(result.success)
        assertEquals("queued_unconfirmed", statusOf(result))
        assertTrue(
            "the segment count is not PII and says what is in flight: ${result.message}",
            result.message.contains("3"),
        )
    }

    /**
     * Review F23: `failed` is documented to the agent as "the send definitively
     * did not happen, a retry is safe". For a multipart send with some segments
     * already confirmed that is false — the recipient has those segments, and a
     * retry gives them the same opening twice.
     */
    @Test
    fun `a multipart send with confirmed segments is partially sent, not failed`() {
        val result = SmsSendReport.describe(
            SmsManager.RESULT_ERROR_NO_SERVICE,
            parts = 3,
            timeoutMs = 10_000L,
            confirmedParts = 2,
        )

        assertTrue(
            "success=false is read as 'try again', which duplicates the segments " +
                "the recipient already has",
            result.success,
        )
        assertEquals("partially_sent", statusOf(result))
        assertEquals(2, deliveredOf(result))
        assertTrue(
            "the message must forbid the retry: ${result.message}",
            result.message.contains("do not resend", ignoreCase = true),
        )
        assertTrue(
            "and must say what did arrive: ${result.message}",
            result.message.contains("2") && result.message.contains("3"),
        )
        assertTrue(
            "and must name the radio reason: ${result.message}",
            result.message.contains("no service", ignoreCase = true),
        )
    }

    @Test
    fun `a failure with no segment confirmed stays a retryable failure`() {
        val result = SmsSendReport.describe(
            SmsManager.RESULT_ERROR_RADIO_OFF,
            parts = 3,
            timeoutMs = 10_000L,
            confirmedParts = 0,
            reportedParts = 3,
        )

        assertFalse(result.success)
        assertEquals("failed", statusOf(result))
        assertEquals(0, deliveredOf(result))
        assertTrue(result.message.contains("radio off", ignoreCase = true))
    }

    @Test
    fun `a single-segment failure is never partially sent`() {
        val result = SmsSendReport.describe(
            SmsManager.RESULT_ERROR_GENERIC_FAILURE,
            parts = 1,
            timeoutMs = 10_000L,
            confirmedParts = 0,
            reportedParts = 1,
        )

        assertFalse(result.success)
        assertEquals("failed", statusOf(result))
    }

    /**
     * Review F30: `failed` is documented to the agent, in the schema, the docstring
     * and `skill.md`, as "nothing went out, a retry is safe". That is only true when
     * every segment reported and every one of them failed. One failure and two
     * segments that never answered is not evidence nothing went out — it is evidence
     * we do not know — and answering `failed` there invites the retry that
     * duplicates whatever the silent segments delivered.
     */
    @Test
    fun `a failure with unreported segments is not a retryable failure`() {
        val result = SmsSendReport.describe(
            SmsManager.RESULT_ERROR_RADIO_OFF,
            parts = 3,
            timeoutMs = 10_000L,
            confirmedParts = 0,
            reportedParts = 1,
        )

        assertTrue(
            "success=false is read as 'try again', and two segments never reported",
            result.success,
        )
        assertEquals("queued_unconfirmed", statusOf(result))
        assertEquals(0, deliveredOf(result))
        assertTrue(
            "the message must still name the radio reason: ${result.message}",
            result.message.contains("radio off", ignoreCase = true),
        )
        assertTrue(
            "and must forbid the retry: ${result.message}",
            result.message.contains("do not resend", ignoreCase = true),
        )
        assertTrue(
            "and must say how much of the tally is missing: ${result.message}",
            result.message.contains("1") && result.message.contains("3"),
        )
    }

    @Test
    fun `only a complete tally can say the send failed`() {
        val code = SmsManager.RESULT_ERROR_NO_SERVICE
        for (reported in 0..3) {
            val result = SmsSendReport.describe(
                code,
                parts = 3,
                timeoutMs = 10_000L,
                confirmedParts = 0,
                reportedParts = reported,
            )
            if (reported == 3) {
                assertFalse("every segment reported and all failed", result.success)
                assertEquals("failed", statusOf(result))
            } else {
                assertTrue("$reported of 3 reports is not a complete tally", result.success)
                assertEquals("queued_unconfirmed", statusOf(result))
            }
        }
    }

    @Test
    fun `an incomplete tally from a real report never says retry-safe`() {
        // End to end through the latch: one of three segments answers, the rest
        // never do, and the bound runs out.
        val report = SmsSendReport(expectedReports = 3)
        report.onResult(SmsManager.RESULT_ERROR_NO_SERVICE)

        val outcome = report.await(timeout)

        val result = SmsSendReport.describe(
            outcome,
            parts = 3,
            timeoutMs = timeout,
            confirmedParts = report.snapshot().confirmedParts,
            reportedParts = report.snapshot().reportedParts,
        )
        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE, outcome)
        assertTrue(result.success)
        assertEquals("queued_unconfirmed", statusOf(result))
    }

    /**
     * Review F27: registration can fail, and then no report can arrive at all. The
     * status stays `queued_unconfirmed` — the message is with the radio either way,
     * so the do-not-resend rule is unchanged — but the wording must not claim we
     * waited for a report that nothing was listening for.
     */
    @Test
    fun `an unobservable report is unconfirmed and says why`() {
        val result = SmsSendReport.describeUnobservable(parts = 3, reason = "no send-report receiver")

        assertTrue(result.success)
        assertEquals("queued_unconfirmed", statusOf(result))
        assertEquals(0, deliveredOf(result))
        assertTrue(
            "the message must say nothing was listening: ${result.message}",
            result.message.contains("cannot be observed", ignoreCase = true),
        )
        assertTrue(
            "and must still forbid the retry: ${result.message}",
            result.message.contains("do not resend", ignoreCase = true),
        )
        assertFalse(
            "the timeout wording claims a wait that never happened: ${result.message}",
            result.message.contains("no send report arrived within"),
        )
        assertTrue("the segment count is not PII: ${result.message}", result.message.contains("3"))
    }

    @Test
    fun `only a definite radio report is a failure`() {
        // The line the contract turns on: a report saying the send failed is a
        // failure; the absence of a report is not.
        val definite = listOf(
            SmsManager.RESULT_ERROR_GENERIC_FAILURE,
            SmsManager.RESULT_ERROR_RADIO_OFF,
            SmsManager.RESULT_ERROR_NULL_PDU,
            SmsManager.RESULT_ERROR_NO_SERVICE,
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED,
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE,
            SmsManager.RESULT_ENCODING_ERROR,
            SmsManager.RESULT_NETWORK_ERROR,
            SmsManager.RESULT_MODEM_ERROR,
            SmsManager.RESULT_SYSTEM_ERROR,
            SmsManager.RESULT_NO_MEMORY,
            SmsManager.RESULT_SMS_BLOCKED_DURING_EMERGENCY,
            9_999, // an unknown non-OK code is still a definite report
        )
        for (code in definite) {
            val result = SmsSendReport.describe(
                code,
                parts = 1,
                timeoutMs = 10_000L,
                confirmedParts = 0,
                reportedParts = 1,
            )
            assertFalse("code $code is a definite failure", result.success)
            assertEquals("failed", statusOf(result))
        }

        val sent = SmsSendReport.describe(Activity.RESULT_OK, parts = 1, timeoutMs = 10_000L)
        assertTrue(sent.success)
        assertEquals("sent", statusOf(sent))
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
            val result = SmsSendReport.describe(
                code,
                parts = 1,
                timeoutMs = 10_000L,
                confirmedParts = 0,
                reportedParts = 1,
            )
            assertFalse("code $code must not report success", result.success)
            assertTrue(
                "code $code should say '$expected', got: ${result.message}",
                result.message.contains(expected, ignoreCase = true),
            )
        }
    }

    @Test
    fun `an unknown radio code still fails closed`() {
        val result = SmsSendReport.describe(
            9_999,
            parts = 1,
            timeoutMs = 10_000L,
            confirmedParts = 0,
            reportedParts = 1,
        )

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
            SmsSendReport.describe(SmsManager.RESULT_ERROR_NO_SERVICE, 3, 10_000L, 2, 3),
            SmsSendReport.describe(SmsManager.RESULT_ERROR_NO_SERVICE, 3, 10_000L, 0, 1),
            SmsSendReport.describeUnobservable(2, "no send-report receiver"),
        )
        for (outcome in outcomes) {
            assertFalse(
                "a phone-number-shaped string leaked into '${outcome.message}'",
                Regex("""\+?\d{6,}""").containsMatchIn(outcome.message),
            )
            assertTrue(
                "every outcome carries a machine-readable status",
                statusOf(outcome) in
                    setOf("sent", "queued_unconfirmed", "partially_sent", "failed"),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun statusOf(result: ActionResult): String {
        val data = result.data as? Map<String, Any?>
        assertNotNull("the outcome carries no status: $result", data)
        return data!!["status"] as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun deliveredOf(result: ActionResult): Int {
        val data = result.data as? Map<String, Any?>
        assertNotNull("the outcome carries no segment tally: $result", data)
        return data!!["delivered"] as Int
    }
}
