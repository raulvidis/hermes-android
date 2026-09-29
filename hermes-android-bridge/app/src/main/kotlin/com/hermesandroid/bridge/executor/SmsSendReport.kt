package com.hermesandroid.bridge.executor

import android.app.Activity
import android.telephony.SmsManager
import com.hermesandroid.bridge.model.ActionResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded wait for the radio's SMS send report (review L2).
 *
 * `SmsManager.sendTextMessage` / `sendMultipartTextMessage` return as soon as the
 * message is *queued*, not when it is sent. The only way to learn the outcome is
 * the `sentIntent` PendingIntent the platform fires with a result code. Both
 * sentIntent and deliveryIntent used to be passed as `null`, so `sendSms` could
 * not distinguish "sent" from "never left the device" and reported success
 * unconditionally.
 *
 * Deliberately free of Android runtime — only compile-time `static final int`
 * constants — so the timeout bound and the result mapping are pinned by plain
 * JVM tests instead of needing a radio.
 *
 * One report per segment: a multipart send fires the sentIntent once per part, so
 * the message is only confirmed when every part reports. A failure ends the wait
 * immediately for a single-segment send, but a multipart one keeps waiting for the
 * remaining reports (review F23): answering on the first failure could not say
 * whether the segments that already went out did, and that is the difference
 * between a retry that is safe and one that duplicates part of the message.
 *
 * An absent report is *not* a failure. The message was handed to the radio and
 * the report merely did not arrive inside the bound, so [describe] answers
 * `queued_unconfirmed` and tells the caller not to resend (review F14);
 * `success = false` is reserved for a report that definitively says the send
 * failed.
 *
 * @param expectedReports how many send reports to wait for — the segment count.
 */
class SmsSendReport(private val expectedReports: Int = 1) {

    private val latch = CountDownLatch(maxOf(expectedReports, 1))
    private val failure = AtomicInteger(NOT_YET)
    private val confirmed = AtomicInteger(0)

    /**
     * How many segments reported `RESULT_OK`. Read after [await].
     *
     * This is what separates "the send did not happen" from "part of it did"
     * (review F23). Incremented before [CountDownLatch.countDown], so every report
     * that contributed to releasing the latch is visible to the reader.
     */
    val confirmedParts: Int
        get() = confirmed.get()

    /** Called from the BroadcastReceiver with the platform's result code. */
    fun onResult(resultCode: Int) {
        if (resultCode != Activity.RESULT_OK) {
            failure.compareAndSet(NOT_YET, resultCode)
            if (expectedReports <= 1) {
                // A single-segment send has nothing left to report, so waiting out
                // the bound would only cost the caller ten seconds.
                latch.countDown()
                return
            }
            // Multipart (review F23): keep waiting for the remaining reports.
            // Answering on the first failure cannot distinguish "no segment went
            // out" — safe to retry — from "the first two did", where a retry
            // delivers them to the same recipient a second time. The wait is
            // bounded by the same [await] timeout as everything else.
            return
        }
        confirmed.incrementAndGet()
        latch.countDown()
    }

    /**
     * The outcome, or `null` when no conclusive report arrived within
     * [timeoutMs]. `null` means "unconfirmed" — see [describe], which reports it
     * as `queued_unconfirmed` rather than as a failure a caller would retry.
     */
    fun await(timeoutMs: Long): Int? {
        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val failed = failure.get()
        if (failed != NOT_YET) return failed
        return if (completed) Activity.RESULT_OK else null
    }

    companion object {
        private const val NOT_YET = Int.MIN_VALUE

        /** Every segment reported `RESULT_OK`. */
        const val STATUS_SENT = "sent"

        /** Handed to the radio, no report inside the window. Do not resend. */
        const val STATUS_QUEUED_UNCONFIRMED = "queued_unconfirmed"

        /**
         * Some segments were confirmed and one reported a failure. Do not resend:
         * the recipient already has the confirmed ones.
         */
        const val STATUS_PARTIALLY_SENT = "partially_sent"

        /** A report arrived and said the send failed, with nothing confirmed. A retry is safe. */
        const val STATUS_FAILED = "failed"

        /**
         * Turn the outcome into what the tool reports.
         *
         * Neither the recipient nor the body is passed in, so no message this
         * builds can echo them — AGENTS.md requires phone numbers, recipients
         * and message content to be stripped from tool responses, and the
         * segment count is the only send detail that is safe to surface.
         *
         * Four outcomes. The line between them is what the radio said, and how
         * much of the message got out:
         *  - [STATUS_SENT] — every segment reported `RESULT_OK`;
         *  - [STATUS_FAILED] — a report arrived, was not `RESULT_OK`, and **no**
         *    segment was confirmed, so the send definitively did not happen and a
         *    retry is safe;
         *  - [STATUS_PARTIALLY_SENT] — a report failed but other segments were
         *    confirmed (review F23). `success = true` and do-not-resend, for the
         *    same reason as below: the recipient already has those segments, and
         *    "failed" told the caller a retry was safe, so they got the opening of
         *    the message twice;
         *  - [STATUS_QUEUED_UNCONFIRMED] — no report inside the window. Reported
         *    as `success = true` (review F14): the message was handed to the
         *    radio, and `success = false` read to the caller as "try again", so
         *    the recipient got the same SMS twice whenever the first one was
         *    merely slow. The message says not to resend and why.
         *
         * @param confirmedParts how many segments reported `RESULT_OK` — read
         *   [SmsSendReport.confirmedParts] after [SmsSendReport.await]. Defaults to
         *   0 so the single-segment call sites keep their three-argument shape.
         */
        fun describe(
            reportedCode: Int?,
            parts: Int,
            timeoutMs: Long,
            confirmedParts: Int = 0,
        ): ActionResult {
            // A confirmed send delivered every segment; anything else reports what
            // the radio actually confirmed.
            val delivered = if (reportedCode == Activity.RESULT_OK) parts else confirmedParts
            return when {
                reportedCode == null -> ActionResult(
                    true,
                    "SMS was handed to the radio but no send report arrived within " +
                        "${timeoutMs}ms; delivery is unconfirmed" +
                        (if (parts > 1) " ($parts segments were in flight)" else "") +
                        ". Do NOT resend: the message may already have been " +
                        "delivered and a retry would duplicate it.",
                    outcome(STATUS_QUEUED_UNCONFIRMED, parts, delivered),
                )

                reportedCode == Activity.RESULT_OK -> ActionResult(
                    true,
                    if (parts > 1) "SMS sent ($parts parts)" else "SMS sent",
                    outcome(STATUS_SENT, parts, delivered),
                )

                // F23: some segments are already with the recipient, so this is not
                // the "definitely did not happen, retry is safe" case.
                delivered > 0 -> ActionResult(
                    true,
                    "SMS partially sent: $delivered of $parts segments were confirmed " +
                        "and the rest failed (${reason(reportedCode)}). Do NOT resend: " +
                        "the recipient already has the confirmed segments and a retry " +
                        "would duplicate them. Tell the user the message was only " +
                        "partly delivered.",
                    outcome(STATUS_PARTIALLY_SENT, parts, delivered),
                )

                else -> ActionResult(
                    false,
                    "SMS send failed: ${reason(reportedCode)}",
                    outcome(STATUS_FAILED, parts, delivered),
                )
            }
        }

        /** The machine-readable half of an outcome. Segment counts are not PII. */
        private fun outcome(status: String, parts: Int, delivered: Int): Map<String, Any> =
            mapOf("status" to status, "parts" to parts, "delivered" to delivered)

        /**
         * Human-readable radio reason. The `RESULT_SMS_*` names this used to be
         * written against were withdrawn from the public SDK in API 31; these are
         * the `RESULT_ERROR_*` / `RESULT_*` constants that replaced them. All are
         * `static final int`, so they inline and need no runtime API level.
         */
        private fun reason(code: Int): String = when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic radio failure"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off (airplane mode or no modem)"
            SmsManager.RESULT_ERROR_NULL_PDU -> "the platform produced no PDU"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "sending limit exceeded"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "blocked by the SIM's fixed-dialling list"
            SmsManager.RESULT_ENCODING_ERROR -> "message encoding error"
            SmsManager.RESULT_NETWORK_ERROR -> "network error"
            SmsManager.RESULT_MODEM_ERROR -> "modem error"
            SmsManager.RESULT_SYSTEM_ERROR -> "system error"
            SmsManager.RESULT_NO_MEMORY -> "out of memory"
            SmsManager.RESULT_SMS_BLOCKED_DURING_EMERGENCY -> "blocked during an emergency"
            else -> "radio result $code"
        }
    }
}
