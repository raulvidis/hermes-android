package com.hermesandroid.bridge.executor

import android.app.Activity
import android.telephony.SmsManager
import com.hermesandroid.bridge.model.ActionResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * the message is only confirmed when every part reports. Every report counts the
 * latch down whether it succeeded or failed (review F29), so the wait ends as soon
 * as the last segment is heard from and only runs to the bound when a report never
 * arrives. Waiting for all of them is what makes the tally trustworthy: answering
 * on the first failure could not say whether the segments that already went out
 * did, and that is the difference between a retry that is safe and one that
 * duplicates part of the message (review F23).
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

    /**
     * Guards the three tally fields below as one unit (review F36).
     *
     * They were three `AtomicInteger`s, which is atomic per field and useless across
     * them: `await()` returns while the receiver is still registered, so a late
     * `RESULT_OK` could land between the caller's read of [confirmedParts] and its
     * read of [reportedParts]. `confirmed = 0, reported = parts` is precisely the
     * shape [describe] maps to [STATUS_FAILED] — "nothing went out, a retry is
     * safe" — for a message that was partly delivered. [snapshot] is therefore the
     * only supported way to read the tally, and it reads all of it under one lock.
     */
    private val lock = Any()
    private var failureCode: Int = NOT_YET
    private var confirmedCount: Int = 0
    private var reportedCount: Int = 0

    /**
     * The tally as one immutable snapshot.
     *
     * @param failureCode the first non-`RESULT_OK` code reported, or null
     * @param confirmedParts how many segments reported `RESULT_OK`
     * @param reportedParts how many segments reported at all
     * @param everySegmentReported whether the wait ended because the tally was
     *   complete rather than because the bound ran out
     */
    data class Tally(
        val failureCode: Int?,
        val confirmedParts: Int,
        val reportedParts: Int,
        val everySegmentReported: Boolean,
    ) {
        /**
         * What [describe] should treat as the radio's answer: the first failure if
         * there was one, `RESULT_OK` if every segment reported and none failed, and
         * null when the bound ran out with segments unheard from.
         */
        val outcomeCode: Int?
            get() = failureCode ?: if (everySegmentReported) Activity.RESULT_OK else null
    }

    /**
     * The tally right now, read atomically — the only way to read it (review F36).
     * Pass the result to [Companion.describe].
     *
     * @param latchCompleted what [CountDownLatch.await] returned, when the caller has
     *   just waited; without it, completeness is inferred from the count.
     */
    fun snapshot(latchCompleted: Boolean = false): Tally = synchronized(lock) {
        Tally(
            failureCode = failureCode.takeIf { it != NOT_YET },
            confirmedParts = confirmedCount,
            reportedParts = reportedCount,
            everySegmentReported = latchCompleted || reportedCount >= expectedReports,
        )
    }

    // Deliberately private (review F36): the only supported read is [snapshot].
    // Exposing the two counters separately is what let a caller read them at
    // different instants, and no amount of documentation stops the next call site
    // from doing it — making it not compile does. `confirmedParts` separates "the
    // send did not happen" from "part of it did" (F23) and `reportedParts` says
    // whether the tally is complete, which is what makes `failed` retry-safe at all
    // (F30); the two only mean anything together.

    /** Called from the BroadcastReceiver with the platform's result code. */
    fun onResult(resultCode: Int) {
        synchronized(lock) {
            if (resultCode != Activity.RESULT_OK) {
                // The first failure code is the one reported; later ones still count
                // toward the tally, so [Tally.reportedParts] says whether it is
                // complete.
                if (failureCode == NOT_YET) failureCode = resultCode
            } else {
                confirmedCount++
            }
            reportedCount++
        }
        // Every report counts the latch down, success or failure (review F29). The
        // latch means "every segment has reported", which is the point the tally is
        // complete — and completeness is what separates a retry that is safe from
        // one that duplicates part of the message (F23, F30). A failure used to
        // leave its count outstanding, so a multipart send that had heard from every
        // segment the radio was ever going to report still sat out the whole bound.
        // A single-segment send is unchanged: one report, one count down, immediate.
        latch.countDown()
    }

    /**
     * The radio's answer, or `null` when the bound ran out with no failure recorded.
     *
     * `null` does **not** mean nothing was heard (review F38): segments may have
     * reported `RESULT_OK` and simply not all of them. It means "unconfirmed" — see
     * [describe], which reports it as `queued_unconfirmed` rather than as a failure a
     * caller would retry, and which needs the tally to say how much was confirmed.
     *
     * A failure code is returned as soon as the latch is released, which is when
     * every segment has reported *or* the bound ran out. Use [awaitTally] instead of
     * this: it returns the completeness flag and the counts alongside the code, read
     * as one snapshot, and without them a caller cannot tell those two apart.
     */
    fun await(timeoutMs: Long): Int? = awaitTally(timeoutMs).outcomeCode

    /**
     * [await], but returning the whole tally read as one snapshot at the instant the
     * wait ended. This is what a caller must use: [await] alone tells it the radio's
     * code and nothing about how much of the message got out, and the counters are
     * private precisely so they cannot be read one at a time (review F36).
     */
    fun awaitTally(timeoutMs: Long): Tally =
        snapshot(latch.await(timeoutMs, TimeUnit.MILLISECONDS))

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

        /**
         * Every segment reported, none succeeded. The only retry-safe answer — see
         * [STATUS_QUEUED_UNCONFIRMED] for the incomplete version of this (review F30).
         */
        const val STATUS_FAILED = "failed"

        /**
         * Turn the outcome into what the tool reports.
         *
         * Neither the recipient nor the body is passed in, so no message this
         * builds can echo them — AGENTS.md requires phone numbers, recipients
         * and message content to be stripped from tool responses, and the
         * segment count is the only send detail that is safe to surface.
         *
         * Four outcomes. Two questions decide between them: what did the radio say,
         * and is the tally complete?
         *  - [STATUS_SENT] — every segment reported `RESULT_OK`;
         *  - [STATUS_PARTIALLY_SENT] — a report failed but other segments were
         *    confirmed (review F23). `success = true` and do-not-resend: the
         *    recipient already has those segments, and "failed" told the caller a
         *    retry was safe, so they got the opening of the message twice;
         *  - [STATUS_FAILED] — a report failed, nothing was confirmed, **and every
         *    segment reported** (review F30). That last clause is what makes it the
         *    only retry-safe answer: with all segments accounted for and none
         *    successful, the message definitively did not go out.
         *  - [STATUS_QUEUED_UNCONFIRMED] — the bound ran out with something still
         *    unknown: no report at all (review F14), some segments confirmed and the
         *    rest silent (review F38), or a failure with segments still unheard from
         *    (review F30). All are `success = true` with do-not-resend wording,
         *    because `success = false` reads to the caller as "try again" and the
         *    recipient then gets the same SMS twice. Each variant says what *is*
         *    known — how many segments confirmed, or the radio's reason and how much
         *    of the tally is missing — so the message never contradicts the
         *    `delivered` count beside it.
         *
         * @param confirmedParts how many segments reported `RESULT_OK` — read
         *   [SmsSendReport.confirmedParts] after [SmsSendReport.await].
         * @param reportedParts how many segments reported at all — read
         *   [SmsSendReport.reportedParts] after [SmsSendReport.await]. Both default
         *   to 0, the *safe* direction: an unstated tally can never produce
         *   [STATUS_FAILED], so a caller that forgets to pass it degrades to
         *   do-not-resend rather than to a duplicate.
         */
        fun describe(
            reportedCode: Int?,
            parts: Int,
            timeoutMs: Long,
            confirmedParts: Int = 0,
            reportedParts: Int = 0,
        ): ActionResult {
            // A confirmed send delivered every segment; anything else reports what
            // the radio actually confirmed.
            val delivered = if (reportedCode == Activity.RESULT_OK) parts else confirmedParts
            return when {
                // F38: `null` means the bound ran out with no failure recorded —
                // not that nothing was heard. Segments may have confirmed, and the
                // message has to agree with the `delivered` count in the same
                // response, or the agent reads "no report arrived" as "nothing
                // happened" and resends.
                reportedCode == null && delivered > 0 -> ActionResult(
                    true,
                    "SMS was handed to the radio; $delivered of $parts segments were " +
                        "confirmed within ${timeoutMs}ms and the rest never reported. " +
                        "Delivery is unconfirmed. Do NOT resend: the confirmed " +
                        "segments are already with the recipient and a retry would " +
                        "duplicate them.",
                    outcome(STATUS_QUEUED_UNCONFIRMED, parts, delivered),
                )

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

                // F30: the only shape in which "the send did not happen" is actually
                // known — every segment reported, and not one of them succeeded.
                reportedParts >= parts -> ActionResult(
                    false,
                    "SMS send failed: ${reason(reportedCode)}",
                    outcome(STATUS_FAILED, parts, delivered),
                )

                // A failure plus segments that never answered. Nothing confirms those
                // went out and nothing says they did not, so this is the unconfirmed
                // case again: `failed` here would tell the caller a retry is safe, and
                // a retry duplicates whatever the silent segments delivered.
                else -> ActionResult(
                    true,
                    "SMS was handed to the radio; $reportedParts of $parts segments " +
                        "reported and at least one failed (${reason(reportedCode)}), " +
                        "the rest never reported. Delivery is unconfirmed. Do NOT " +
                        "resend: a segment that never reported may still have gone out " +
                        "and a retry would duplicate it.",
                    outcome(STATUS_QUEUED_UNCONFIRMED, parts, delivered),
                )
            }
        }

        /**
         * [describe] for a tally read as one snapshot — the race-free form (review
         * F36). Equivalent to passing the snapshot's three fields separately, except
         * that they are guaranteed to describe the same instant.
         */
        fun describe(tally: Tally, parts: Int, timeoutMs: Long): ActionResult =
            describe(
                reportedCode = tally.outcomeCode,
                parts = parts,
                timeoutMs = timeoutMs,
                confirmedParts = tally.confirmedParts,
                reportedParts = tally.reportedParts,
            )

        /** The machine-readable half of an outcome. Segment counts are not PII. */
        private fun outcome(status: String, parts: Int, delivered: Int): Map<String, Any> =
            mapOf("status" to status, "parts" to parts, "delivered" to delivered)

        /**
         * The outcome when the send went out but no report could ever be observed
         * (review F27): the receiver could not be registered, so no `sentIntent` has
         * anywhere to land.
         *
         * Distinct from the timeout wording on purpose. "No send report arrived
         * within 10000ms" says the radio was given the chance to confirm and did
         * not; here nothing was listening, and waiting out the bound would spend
         * ten seconds of the caller's budget learning nothing. Same status, so a
         * caller's do-not-resend rule is unchanged — the message is with the radio
         * either way.
         */
        fun describeUnobservable(parts: Int, reason: String): ActionResult =
            ActionResult(
                true,
                "SMS was handed to the radio but its send report cannot be observed " +
                    "on this device ($reason); delivery is unconfirmed" +
                    (if (parts > 1) " ($parts segments were in flight)" else "") +
                    ". Do NOT resend: the message may already have been delivered " +
                    "and a retry would duplicate it.",
                outcome(STATUS_QUEUED_UNCONFIRMED, parts, 0),
            )

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
