package com.hermesandroid.bridge.executor

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import com.hermesandroid.bridge.model.ActionResult
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSmsManager

/**
 * Review L2: `android_send_sms` reported success for messages never sent.
 *
 * `sendTextMessage(to, null, body, null, null)` passed null for both sentIntent
 * and deliveryIntent, so no send status was ever observed and `sendSms` returned
 * `ActionResult(true, "SMS sent")` unconditionally. Multi-part messages were
 * never divided either, so a body over the single-segment limit failed at the
 * radio layer with nobody listening. The agent sent a 300-character SMS, was told
 * it was delivered, and told the user so — and because the recipient and body are
 * stripped from the response by design, nothing in the tool output contradicted it.
 *
 * Two fixtures, because the platform will not cooperate in one:
 * - [ShadowSmsManager] records what a *real* SmsManager was asked to send, which
 *   is how the sentIntent (the actual bug) is observed. `divideMessage` is
 *   unusable here — it calls `getISmsServiceOrThrow()` and Robolectric has no
 *   ISms binder, so it throws "Sms is not supported" and `sendSms` correctly
 *   falls back to a single segment.
 * - a mocked SmsManager supplies the segmentation, which is what exercises the
 *   single-vs-multipart choice and the one-sentIntent-per-segment rule. Segmenting
 *   is the platform's job; choosing the right send call for the result is ours.
 *
 * The radio's own result code cannot be injected: `BroadcastReceiver.setResultCode`
 * needs a `PendingResult` that only a real ordered broadcast creates, and
 * `ShadowBroadcastReceiver` exposes no way to fabricate one. So the success
 * mapping (`RESULT_OK` -> "SMS sent") is pinned one layer down in
 * [SmsSendReportTest], and here the delivered-report case asserts the plumbing:
 * a report that is not `RESULT_OK` must reach the caller as `success: false`.
 *
 * Review F14 changed what an *absent* report means: the message was handed to the
 * radio, so "no report inside the window" is `success: true` with status
 * `queued_unconfirmed` and an explicit do-not-resend, not a failure the agent is
 * invited to retry. The assertions below follow that contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorSendSmsTest {

    private lateinit var context: Context
    private lateinit var mockService: BridgeAccessibilityService
    private lateinit var realSmsManager: SmsManager
    private lateinit var shadowSms: ShadowSmsManager

    /** true = the registered receiver is fired, as the platform would fire it. */
    private var deliverReport: Boolean = false

    private val recipient = "+15551234567"
    private val body = "meet me at the usual place"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        realSmsManager = context.getSystemService(SmsManager::class.java)
        shadowSms = Shadow.extract<ShadowSmsManager>(realSmsManager)
        ShadowSmsManager.reset()

        mockService = mockk(relaxed = true)
        // hasSelfPermission is a private extension on ActionExecutor delegating to
        // Context.checkSelfPermission, so that is the seam to stub.
        every {
            mockService.checkSelfPermission(android.Manifest.permission.SEND_SMS)
        } returns PackageManager.PERMISSION_GRANTED
        every { mockService.packageName } returns "com.hermesandroid.bridge"
        // A real SmsManager by default, so ShadowSmsManager records what sendSms
        // asked it to send. A relaxed mock cannot answer getSystemService(Class)
        // at all — it returns a mock of the declared type and the cast blows up.
        // stubSmsManager() overrides this where the test needs to control
        // segmentation.
        every { mockService.getSystemService(SmsManager::class.java) } returns realSmsManager
        // Both overloads: production picks the flagged one on API 33+ and the
        // plain one below it. An unstubbed relaxed mock returns null silently,
        // which would look exactly like "the platform never reported".
        every { mockService.registerReceiver(any(), any<IntentFilter>()) } answers {
            if (deliverReport) fireReport(firstArg())
            null
        }
        every { mockService.registerReceiver(any(), any<IntentFilter>(), any<Int>()) } answers {
            if (deliverReport) fireReport(firstArg())
            null
        }

        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns mockService

        ActionExecutor.smsSendReportTimeoutMs = 250L
    }

    @After
    fun tearDown() {
        ActionExecutor.smsSendReportTimeoutMs = 10_000L
        unmockkAll()
        ShadowSmsManager.reset()
    }

    private fun fireReport(receiver: BroadcastReceiver) {
        // No setResultCode: it needs a PendingResult only a real ordered broadcast
        // creates. Whatever code the platform default is, it is not RESULT_OK, so
        // this exercises the "a report arrived and it was not success" path.
        receiver.onReceive(context, null)
    }

    /** A SmsManager whose segmentation the test controls. */
    private fun stubSmsManager(vararg segments: String): SmsManager {
        val smsManager = mockk<SmsManager>(relaxed = true)
        every { smsManager.divideMessage(any()) } returns ArrayList(segments.toList())
        every { mockService.getSystemService(SmsManager::class.java) } returns smsManager
        return smsManager
    }

    // ── The bug itself: a sentIntent must be supplied ─────────────────────────

    @Test
    fun `a single-segment send supplies a sentIntent so the outcome is observable`() {
        val result = ActionExecutor.sendSms(recipient, body)

        val params = shadowSms.lastSentTextMessageParams
        assertNotNull("sendTextMessage was never called", params)
        // This is the whole of L2: it used to be null, so nothing was observed and
        // sendSms claimed success regardless.
        assertNotNull("a sentIntent must be supplied", params.sentIntent)
        assertEquals(recipient, params.destinationAddress)
        assertEquals(
            "with no report the outcome must be the unconfirmed one, not a claimed send",
            "queued_unconfirmed",
            statusOf(result),
        )
    }

    @Test
    fun `no send report within the bound is reported as unconfirmed`() {
        val result = ActionExecutor.sendSms(recipient, body)

        // F14: `success = false` here is what made the recipient get the message
        // twice — the radio has it, the report merely did not arrive in time, and
        // an agent that reads "failed" sends it again.
        assertTrue(
            "a missing report is not a definite failure: ${result.message}",
            result.success,
        )
        assertEquals("queued_unconfirmed", statusOf(result))
        assertTrue(
            "must say delivery is unconfirmed: ${result.message}",
            result.message.contains("unconfirmed", ignoreCase = true),
        )
        assertTrue(
            "and must forbid the retry that would duplicate it: ${result.message}",
            result.message.contains("do not resend", ignoreCase = true),
        )
    }

    @Test
    fun `a report that is not success reaches the caller as a failure`() {
        deliverReport = true

        val result = ActionExecutor.sendSms(recipient, body)

        assertFalse(
            "a delivered non-OK report must not be reported as sent: ${result.message}",
            result.success,
        )
        assertTrue(
            "the message must name a radio outcome: ${result.message}",
            result.message.contains("SMS send failed", ignoreCase = true),
        )
    }

    // ── Segmentation ─────────────────────────────────────────────────────────

    @Test
    fun `a multi-segment body goes through sendMultipartTextMessage`() {
        val smsManager = stubSmsManager("part-one ", "part-two ", "part-three")

        ActionExecutor.sendSms(recipient, "x".repeat(400))

        val parts = slot<ArrayList<String>>()
        val sentIntents = slot<ArrayList<android.app.PendingIntent>>()
        verify(exactly = 1) {
            smsManager.sendMultipartTextMessage(recipient, null, capture(parts), capture(sentIntents), null)
        }
        verify(exactly = 0) {
            smsManager.sendTextMessage(any(), any(), any(), any(), any())
        }
        assertEquals(3, parts.captured.size)
        assertEquals(
            "every segment needs its own send report",
            parts.captured.size,
            sentIntents.captured.size,
        )
        assertTrue(
            "each segment's sentIntent must be distinct",
            sentIntents.captured.toSet().size == sentIntents.captured.size,
        )
    }

    @Test
    fun `a single-segment body goes through sendTextMessage with one sentIntent`() {
        val smsManager = stubSmsManager("hello")

        ActionExecutor.sendSms(recipient, "hello")

        val sentIntent = slot<android.app.PendingIntent>()
        verify(exactly = 1) {
            smsManager.sendTextMessage(recipient, null, "hello", capture(sentIntent), null)
        }
        verify(exactly = 0) {
            smsManager.sendMultipartTextMessage(any(), any(), any(), any(), any())
        }
        assertNotNull(sentIntent.captured)
    }

    @Test
    fun `a body is still sent when the platform cannot segment it`() {
        // divideMessage throws "Sms is not supported" under Robolectric, which is
        // the real case this covers: a platform that cannot segment must not stop
        // the send, and must not be mistaken for a confirmed one.
        val smsManager = mockk<SmsManager>(relaxed = true)
        every { smsManager.divideMessage(any()) } throws UnsupportedOperationException("Sms is not supported")
        every { mockService.getSystemService(SmsManager::class.java) } returns smsManager

        val result = ActionExecutor.sendSms(recipient, body)

        verify(exactly = 1) { smsManager.sendTextMessage(recipient, null, body, any(), null) }
        assertEquals(
            "an unconfirmed send is reported as unconfirmed, never as a claimed success",
            "queued_unconfirmed",
            statusOf(result),
        )
    }

    // ── Report attribution (review F15) ─────────────────────────────

    /**
     * Every send used to share one broadcast action and requestCodes `0..n-1`, so
     * the reports of two overlapping `sendSms` calls were delivered to *both*
     * receivers — a registered receiver gets every broadcast matching its filter —
     * and one call's segment tally was advanced by another call's radio result. A
     * late report from a send that had already timed out landed the same way if a
     * newer send was in flight. The action is now per call, which fixes the
     * attribution and the `PendingIntent` identity collision at once: two calls'
     * segment-0 intents no longer `filterEquals` each other, so `FLAG_UPDATE_CURRENT`
     * cannot hand one call the other's PendingIntent.
     */
    @Test
    fun `two overlapping sends listen on different report actions`() {
        val actions = mutableListOf<String>()
        every { mockService.registerReceiver(any(), any<IntentFilter>(), any<Int>()) } answers {
            actions += secondArg<IntentFilter>().getAction(0)
            null
        }

        ActionExecutor.sendSms(recipient, body)
        ActionExecutor.sendSms(recipient, body)

        assertEquals(2, actions.size)
        assertNotEquals(
            "a shared action lets one call's report count toward another's",
            actions[0],
            actions[1],
        )
        for (action in actions) {
            assertTrue(
                "the action must still name this bridge: $action",
                action.startsWith("com.hermesandroid.bridge.action.SMS_SENT_REPORT."),
            )
            // A UUID suffix is what makes it unique per call rather than per
            // process, and what keeps two calls' requestCodes from colliding.
            assertEquals("the per-call suffix must be a UUID: $action", 36, action.substringAfterLast('.').length)
        }
    }

    @Test
    fun `a report belonging to another call is not counted toward this one`() {
        every { mockService.registerReceiver(any(), any<IntentFilter>(), any<Int>()) } answers {
            val receiver = firstArg<BroadcastReceiver>()
            // A foreign report with a non-OK code. Under the shared action this
            // was indistinguishable from this call's own, so a send that had
            // never been confirmed came back as a definite radio failure.
            receiver.onReceive(
                context,
                Intent("com.hermesandroid.bridge.action.SMS_SENT_REPORT.00000000-0000-0000-0000-000000000000"),
            )
            null
        }

        val result = ActionExecutor.sendSms(recipient, body)

        assertTrue(
            "a foreign report must not turn an unconfirmed send into a failure: ${result.message}",
            result.success,
        )
        assertEquals("queued_unconfirmed", statusOf(result))
    }

    @Test
    fun `a report on this call's own action is still counted`() {
        // The other half: filtering must not deafen the receiver to the report it
        // is actually waiting for.
        every { mockService.registerReceiver(any(), any<IntentFilter>(), any<Int>()) } answers {
            val receiver = firstArg<BroadcastReceiver>()
            receiver.onReceive(context, Intent(secondArg<IntentFilter>().getAction(0)))
            null
        }

        val result = ActionExecutor.sendSms(recipient, body)

        assertFalse("this call's own report must reach the outcome", result.success)
        assertEquals("failed", statusOf(result))
    }

    @Test
    fun `the receiver is unregistered once the call has its answer`() {
        // Already true before F15 and pinned here because it is the other half of
        // attribution: a receiver left registered would keep counting reports
        // belonging to a call that finished, including a late one from a send that
        // timed out.
        val receiver = slot<BroadcastReceiver>()
        every {
            mockService.registerReceiver(capture(receiver), any<IntentFilter>(), any<Int>())
        } returns null

        ActionExecutor.sendSms(recipient, body)

        verify(exactly = 1) { mockService.unregisterReceiver(receiver.captured) }
    }

    // ── No receiver means no report can ever arrive (review F27) ────────────

    /**
     * Registration can fail — a `SecurityException` on a hardened ROM, an
     * `IllegalArgumentException` from a context that cannot take a receiver — and
     * `sendSms` correctly refused to claim success without a report. But it then
     * waited out the *whole* bound for a report that by construction could not
     * arrive, and answered with the timeout wording ("no send report arrived within
     * 10000ms"), which says the radio was given the chance to confirm and did not.
     * Ten seconds of a caller's 30 s budget spent learning nothing, plus a message
     * that misdescribes what happened.
     */
    @Test
    fun `a send with no receiver answers at once instead of waiting out the bound`() {
        every {
            mockService.registerReceiver(any(), any<IntentFilter>(), any<Int>())
        } throws SecurityException("registration refused")
        every {
            mockService.registerReceiver(any(), any<IntentFilter>())
        } throws SecurityException("registration refused")
        ActionExecutor.smsSendReportTimeoutMs = 5_000L

        val started = System.nanoTime()
        val result = ActionExecutor.sendSms(recipient, body)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertTrue(
            "waited ${elapsedMs}ms for a report that could never arrive",
            elapsedMs < 1_000L,
        )
        assertTrue(result.success)
        assertEquals("queued_unconfirmed", statusOf(result))
    }

    @Test
    fun `the message says the report was unobservable, not that it timed out`() {
        every {
            mockService.registerReceiver(any(), any<IntentFilter>(), any<Int>())
        } throws SecurityException("registration refused")
        ActionExecutor.smsSendReportTimeoutMs = 5_000L

        val result = ActionExecutor.sendSms(recipient, body)

        assertFalse(
            "the timeout wording claims we waited for a report: ${result.message}",
            result.message.contains("no send report arrived within"),
        )
        assertTrue(
            "the message must say why nothing was observed: ${result.message}",
            result.message.contains("cannot be observed", ignoreCase = true),
        )
        assertTrue(
            "and must still forbid the retry: ${result.message}",
            result.message.contains("do not resend", ignoreCase = true),
        )
    }

    @Test
    fun `the message is still sent when no report can be observed`() {
        // An unobservable report is not a reason to withhold the send: the caller
        // asked for an SMS, and refusing would be the one answer that is definitely
        // wrong.
        every {
            mockService.registerReceiver(any(), any<IntentFilter>(), any<Int>())
        } throws SecurityException("registration refused")

        ActionExecutor.sendSms(recipient, body)

        assertNotNull(
            "the message must still reach the radio",
            shadowSms.lastSentTextMessageParams,
        )
        verify(exactly = 0) { mockService.unregisterReceiver(any<BroadcastReceiver>()) }
    }

    // ── PII ──────────────────────────────────────────────────────────────────

    @Test
    fun `no outcome echoes the recipient or the body`() {
        // AGENTS.md: strip phone numbers, recipients and message content from tool
        // responses. The existing convention ("SMS sent", no recipient) has to
        // survive the richer reporting L2 adds.
        stubSmsManager("part-one", "part-two")
        val outcomes = mutableListOf(ActionExecutor.sendSms(recipient, body))

        deliverReport = true
        outcomes += ActionExecutor.sendSms(recipient, body)
        deliverReport = false
        outcomes += ActionExecutor.sendSms(recipient, body)

        every { mockService.checkSelfPermission(android.Manifest.permission.SEND_SMS) } returns
            PackageManager.PERMISSION_DENIED
        outcomes += ActionExecutor.sendSms(recipient, body)

        for (outcome in outcomes) {
            assertFalse("recipient leaked into '${outcome.message}'", outcome.message.contains(recipient))
            assertFalse("recipient digits leaked into '${outcome.message}'", outcome.message.contains("5551234567"))
            assertFalse("body leaked into '${outcome.message}'", outcome.message.contains(body))
            assertFalse("body fragment leaked into '${outcome.message}'", outcome.message.contains("usual place"))
        }
    }

    @Test
    fun `a missing SEND_SMS permission is still refused before anything is sent`() {
        every {
            mockService.checkSelfPermission(android.Manifest.permission.SEND_SMS)
        } returns PackageManager.PERMISSION_DENIED

        val result = ActionExecutor.sendSms(recipient, body)

        assertFalse(result.success)
        assertTrue(result.message.contains("SEND_SMS permission not granted"))
        verify(exactly = 0) { mockService.registerReceiver(any(), any<IntentFilter>(), any<Int>()) }
    }

    @Test
    fun `the success wording for a confirmed send stays PII-free`() {
        // Pinned here as well as in SmsSendReportTest, because this is the string
        // the agent relays to the user.
        val confirmed = SmsSendReport.describe(Activity.RESULT_OK, parts = 1, timeoutMs = 10_000L)
        val confirmedMultipart = SmsSendReport.describe(Activity.RESULT_OK, parts = 3, timeoutMs = 10_000L)

        assertTrue(confirmed.success)
        assertEquals("SMS sent", confirmed.message)
        assertTrue(confirmedMultipart.success)
        assertEquals("SMS sent (3 parts)", confirmedMultipart.message)
    }

    /** The machine-readable outcome F14 added, or a failure naming the result. */
    private fun statusOf(result: ActionResult): String {
        val data = result.data as? Map<*, *>
        assertNotNull("the outcome carries no status: $result", data)
        return data!!["status"] as String
    }
}
