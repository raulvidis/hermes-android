package com.hermesandroid.bridge.server

import android.content.Context
import android.content.pm.PackageManager
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.hermesandroid.bridge.bridgeTest
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.model.DeviceCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
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
 * Review L2: `/send_sms` accepted a blank `to`/`body`, defaulted both to "", and
 * handed them to `ActionExecutor.sendSms`. AOSP's `SmsManager.sendTextMessage`
 * throws `IllegalArgumentException` for an empty destination, `sendSms` caught
 * only `SecurityException`, and the exception escaped `CommandDispatcher` — over
 * the relay it became a 500 `{"error": …}`, over direct HTTP a non-JSON Ktor 500.
 *
 * `/open_app` already answered 400 for a missing `package`, so the pattern
 * existed; `/send_sms` just did not use it. These pin the 400 and, just as
 * importantly, that the rejection message is a fixed string: AOSP's own
 * exception text quotes the destination address, which is exactly what the PII
 * convention forbids echoing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommandDispatcherSendSmsTest {

    @Before
    fun setup() {
        setTelephony(true)
    }

    @After
    fun tearDown() {
        // DeviceCapabilities is a process-wide singleton with a private setter;
        // leaving it true would change what later test classes see.
        setTelephony(false)
        unmockkAll()
    }

    private fun setTelephony(present: Boolean) {
        val context = mockk<Context>()
        val packageManager = mockk<PackageManager>()
        every { context.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) } returns present
        DeviceCapabilities.init(context)
    }

    private suspend fun sendSms(body: JsonObject): Pair<Any, Int> =
        CommandDispatcher.dispatch("POST", "/send_sms", JsonObject(), body, true)

    private fun smsBody(to: Any?, body: Any?): JsonObject = JsonObject().apply {
        when (to) {
            null -> Unit
            is JsonNull -> add("to", to)
            else -> addProperty("to", to as String)
        }
        when (body) {
            null -> Unit
            is JsonNull -> add("body", body)
            else -> addProperty("body", body as String)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fields(rawResult: Any): Map<String, Any> = rawResult as Map<String, Any>

    @Test
    fun `a missing recipient is a 400`() = bridgeTest {
        val (result, status) = sendSms(smsBody(null, "hello"))

        assertEquals(400, status)
        assertEquals("Missing or blank 'to'", fields(result)["error"])
    }

    @Test
    fun `a blank recipient is a 400`() = bridgeTest {
        val (result, status) = sendSms(smsBody("   ", "hello"))

        assertEquals(400, status)
        assertEquals("Missing or blank 'to'", fields(result)["error"])
    }

    @Test
    fun `a missing body is a 400`() = bridgeTest {
        val (result, status) = sendSms(smsBody("+15551234567", null))

        assertEquals(400, status)
        assertEquals("Missing or blank 'body'", fields(result)["error"])
    }

    @Test
    fun `a blank body is a 400`() = bridgeTest {
        val (_, status) = sendSms(smsBody("+15551234567", ""))

        assertEquals(400, status)
    }

    @Test
    fun `an empty body object is a 400`() = bridgeTest {
        // The exact request the review found reachable: POST /send_sms with {}.
        val (_, status) = sendSms(JsonObject())

        assertEquals(400, status)
    }

    @Test
    fun `a JSON null is a 400, not a 500`() = bridgeTest {
        val (_, status) = sendSms(smsBody(JsonNull.INSTANCE, JsonNull.INSTANCE))

        assertEquals(400, status)
    }

    @Test
    fun `the rejection never quotes the recipient`() = bridgeTest {
        val (result, status) = sendSms(smsBody("+15551234567", null))

        assertEquals(400, status)
        val rendered = result.toString()
        assertFalse("the address leaked into the error: $rendered", rendered.contains("5551234567"))
    }

    @Test
    fun `the capability gate still comes first and stays a 200`() = bridgeTest {
        setTelephony(false)

        val (result, status) = sendSms(JsonObject())

        // Unchanged contract: a device without telephony reports success:false at
        // 200, not a 400 — the request was well formed, the device cannot do it.
        assertEquals(200, status)
        val fields = fields(result)
        assertEquals(false, fields["success"])
        assertTrue((fields["error"] as String).contains("not available on this device"))
    }

    @Test
    fun `a valid request reaches the executor`() = bridgeTest {
        val (result, status) = sendSms(smsBody("+15551234567", "hello"))

        // No accessibility service in a unit test, so the executor's own guard
        // answers — the point is that validation let the request through instead
        // of 400ing, and that it did so without throwing. The executor returns an
        // ActionResult, not a Map, so this one is not run through fields().
        assertEquals(200, status)
        val action = result as ActionResult
        assertFalse(action.success)
        assertTrue(action.message.contains("Accessibility service not running"))
    }
}
