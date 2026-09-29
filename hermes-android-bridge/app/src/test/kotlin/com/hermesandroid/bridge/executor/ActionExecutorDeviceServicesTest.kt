package com.hermesandroid.bridge.executor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.telephony.SmsManager
import com.hermesandroid.bridge.bridgeTest
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterization tests for [ActionExecutor] device-service actions
 * (location / sendSms / makeCall / mediaControl / searchContacts / TTS),
 * captured before the executor split.
 *
 * These are the permission-gated, PII-bearing actions of the bridge: the
 * tests pin the exact permission-denied messages and the happy-path
 * interaction with the platform (SmsManager, dial intents, media broadcasts).
 *
 * The SMS cases pin only the facade-level shape the split has to preserve —
 * which send call is chosen for how many segments, that a `sentIntent` is
 * passed at all, and that the outcome is the machine-readable tally. The full
 * send-report contract (statuses, radio reason mapping, the F14/F23/F27/F30/
 * F36/F38 rules) lives in `ActionExecutorSendSmsTest` and `SmsSendReportTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorDeviceServicesTest {

    private lateinit var mockService: BridgeAccessibilityService

    @Before
    fun setup() {
        mockService = mockk(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns mockService
        // A relaxed mock answers 0 for checkSelfPermission — which equals
        // PERMISSION_GRANTED. Make the default explicit and safe.
        every { mockService.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED
    }

    @After
    fun tearDown() {
        ActionExecutor.smsSendReportTimeoutMs = 10_000L
        unmockkAll()
    }

    @Test
    fun `location reports a clear message when permission is missing`() {
        val result = ActionExecutor.location()

        assertFalse(result.success)
        assertEquals(
            "Location permission not granted. Grant it in Settings > Apps > Hermes Bridge > Permissions.",
            result.message
        )
    }

    @Test
    fun `location reports no fix when no provider has one`() {
        every { mockService.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        val lm = mockk<LocationManager>(relaxed = true) // getProviders(true) -> empty
        every { mockService.getSystemService(Context.LOCATION_SERVICE) } returns lm

        val result = ActionExecutor.location()

        assertFalse(result.success)
        assertEquals("No location available. Enable GPS/Location.", result.message)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `location returns the best known fix`() {
        every { mockService.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        val fix = Location("gps").apply {
            latitude = 37.5
            longitude = -122.1
            accuracy = 10f
            altitude = 5.0
            time = 1_700_000_000_000L
        }
        val lm = mockk<LocationManager>()
        every { lm.getProviders(true) } returns listOf("gps")
        every { lm.getLastKnownLocation("gps") } returns fix
        every { mockService.getSystemService(Context.LOCATION_SERVICE) } returns lm

        val result = ActionExecutor.location()

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Location", result.message)
        val data = result.data as Map<String, Any?>
        assertEquals(37.5, data["latitude"])
        assertEquals(-122.1, data["longitude"])
        assertEquals(10f, data["accuracy"])
        assertEquals(5.0, data["altitude"])
        assertEquals("gps", data["provider"])
        assertEquals(1_700_000_000_000L, data["timestamp"])
    }

    @Test
    fun `sendSms reports a clear message when permission is missing`() {
        val result = ActionExecutor.sendSms("5551234", "hello")

        assertFalse(result.success)
        assertEquals(
            "SEND_SMS permission not granted. Grant it in Settings > Apps > Hermes Bridge > Permissions.",
            result.message
        )
    }

    /**
     * A SmsManager whose segmentation the test controls, plus the two stubs the
     * send-report plumbing needs: a package name for the report `Intent`, and a
     * short bound so an absent radio report costs a quarter of a second rather
     * than the production ten.
     */
    private fun stubSms(segments: List<String>): SmsManager {
        every { mockService.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        every { mockService.packageName } returns "com.hermesandroid.bridge"
        val sms = mockk<SmsManager>(relaxed = true)
        every { sms.divideMessage(any()) } returns ArrayList(segments)
        every { mockService.getSystemService(SmsManager::class.java) } returns sms
        ActionExecutor.smsSendReportTimeoutMs = 250L
        return sms
    }

    @Test
    fun `sendSms sends a single segment with a sentIntent and reports the tally`() {
        val sms = stubSms(listOf("hello"))

        val result = ActionExecutor.sendSms("5551234", "hello")

        // F14: no report inside the bound is not a failure — the message is with
        // the radio, so the outcome is unconfirmed plus an explicit do-not-resend.
        assertTrue("expected success — got: ${result.message}", result.success)
        assertTrue(result.message.contains("Do NOT resend"))
        val sentIntent = slot<PendingIntent>()
        verify(exactly = 1) {
            sms.sendTextMessage("5551234", null, "hello", capture(sentIntent), null)
        }
        // Review L2 was exactly this: a null sentIntent meant no report was ever
        // observed, so every send claimed success.
        assertNotNull(sentIntent.captured)
        verify(exactly = 0) { sms.sendMultipartTextMessage(any(), any(), any(), any(), any()) }

        val data = result.data as Map<String, Any?>
        assertEquals(SmsSendReport.STATUS_QUEUED_UNCONFIRMED, data["status"])
        assertEquals(1, data["parts"])
        assertEquals(0, data["delivered"])
        // AGENTS.md PII rule: neither the recipient nor the body is echoed back.
        val rendered = "$data ${result.message}"
        assertFalse(rendered.contains("5551234"))
        assertFalse(rendered.contains("hello"))
    }

    @Test
    fun `sendSms sends a divided body as one multipart message`() {
        val sms = stubSms(listOf("part one", "part two"))

        val result = ActionExecutor.sendSms("5551234", "part onepart two")

        val parts = slot<ArrayList<String>>()
        val sentIntents = slot<ArrayList<PendingIntent>>()
        verify(exactly = 1) {
            sms.sendMultipartTextMessage("5551234", null, capture(parts), capture(sentIntents), null)
        }
        assertEquals(listOf("part one", "part two"), parts.captured)
        // One sentIntent per segment, so each part's report is distinct.
        assertEquals(2, sentIntents.captured.size)
        verify(exactly = 0) { sms.sendTextMessage(any(), any(), any(), any(), any()) }

        val data = result.data as Map<String, Any?>
        assertEquals(SmsSendReport.STATUS_QUEUED_UNCONFIRMED, data["status"])
        assertEquals(2, data["parts"])
    }

    @Test
    fun `makeCall opens the dialer without CALL_PHONE permission`() {
        val result = ActionExecutor.makeCall("5551234")

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals(
            "Opened dialer (grant CALL_PHONE permission to auto-dial)",
            result.message
        )
        val intent = slot<Intent>()
        verify(exactly = 1) { mockService.startActivity(capture(intent)) }
        assertEquals(Intent.ACTION_DIAL, intent.captured.action)
        assertEquals("tel:5551234", intent.captured.data.toString())
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            intent.captured.flags and Intent.FLAG_ACTIVITY_NEW_TASK
        )
    }

    @Test
    fun `makeCall places a direct call with CALL_PHONE permission`() {
        every { mockService.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED

        val result = ActionExecutor.makeCall("5551234")

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Calling", result.message)
        val intent = slot<Intent>()
        verify(exactly = 1) { mockService.startActivity(capture(intent)) }
        assertEquals(Intent.ACTION_CALL, intent.captured.action)
    }

    @Test
    fun `mediaControl rejects an unknown action`() {
        val result = ActionExecutor.mediaControl("frobnicate")

        assertFalse(result.success)
        assertEquals(
            "Unknown media action: frobnicate. Use play, pause, toggle, next, previous.",
            result.message
        )
    }

    @Test
    fun `mediaControl sends key-down and key-up broadcasts`() {
        val result = ActionExecutor.mediaControl("play")

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Media play sent", result.message)
        // One ordered broadcast per key event phase.
        verify(exactly = 2) { mockService.sendOrderedBroadcast(any(), any()) }
    }

    @Test
    fun `searchContacts reports a clear message when permission is missing`() {
        val result = ActionExecutor.searchContacts("jo")

        assertFalse(result.success)
        assertEquals(
            "READ_CONTACTS permission not granted. Grant it in Settings > Apps > Hermes Bridge > Permissions.",
            result.message
        )
    }

    @Test
    fun `speak reports TTS unavailable without the service`() = bridgeTest {
        every { BridgeAccessibilityService.instance } returns null

        val result = ActionExecutor.speak("hello")

        assertFalse(result.success)
        assertEquals("TTS not available", result.message)
    }

    @Test
    fun `stopSpeaking succeeds with no speech in flight`() {
        val result = ActionExecutor.stopSpeaking()

        assertTrue(result.success)
        assertEquals("Stopped speaking", result.message)
    }
}
