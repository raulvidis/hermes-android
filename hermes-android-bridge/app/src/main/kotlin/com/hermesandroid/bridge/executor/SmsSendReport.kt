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
 * the message is only confirmed when every part reports, and a single failure
 * wins immediately rather than waiting out the timeout for the rest.
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

    /** Called from the BroadcastReceiver with the platform's result code. */
    fun onResult(resultCode: Int) {
        if (resultCode != Activity.RESULT_OK) {
            // One bad segment is the answer; stop waiting for the others so a
            // failure costs no more than the time it took to arrive.
            failure.compareAndSet(NOT_YET, resultCode)
            while (latch.count > 0) latch.countDown()
            return
        }
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

        /** A report arrived and said the send failed. A retry is safe. */
        const val STATUS_FAILED = "failed"

        /**
         * Turn the outcome into what the tool reports.
         *
         * Neither the recipient nor the body is passed in, so no message this
         * builds can echo them — AGENTS.md requires phone numbers, recipients
         * and message content to be stripped from tool responses, and the
         * segment count is the only send detail that is safe to surface.
         *
         * Three outcomes, and the line between them is whether the radio *said*
         * something:
         *  - [STATUS_SENT] — every segment reported `RESULT_OK`;
         *  - [STATUS_FAILED] — a report arrived and was not `RESULT_OK`, so the
         *    send definitively did not happen and a retry is safe;
         *  - [STATUS_QUEUED_UNCONFIRMED] — no report inside the window. Reported
         *    as `success = true` (review F14): the message was handed to the
         *    radio, and `success = false` read to the caller as "try again", so
         *    the recipient got the same SMS twice whenever the first one was
         *    merely slow. The message says not to resend and why.
         */
        fun describe(reportedCode: Int?, parts: Int, timeoutMs: Long): ActionResult =
            when (reportedCode) {
                null -> ActionResult(
                    true,
                    "SMS was handed to the radio but no send report arrived within " +
                        "${timeoutMs}ms; delivery is unconfirmed" +
                        (if (parts > 1) " ($parts segments were in flight)" else "") +
                        ". Do NOT resend: the message may already have been " +
                        "delivered and a retry would duplicate it.",
                    mapOf("status" to STATUS_QUEUED_UNCONFIRMED, "parts" to parts),
                )

                Activity.RESULT_OK ->
                    ActionResult(
                        true,
                        if (parts > 1) "SMS sent ($parts parts)" else "SMS sent",
                        mapOf("status" to STATUS_SENT, "parts" to parts),
                    )

                else -> ActionResult(
                    false,
                    "SMS send failed: ${reason(reportedCode)}",
                    mapOf("status" to STATUS_FAILED, "parts" to parts),
                )
            }

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
