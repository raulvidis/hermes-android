package com.hermesandroid.bridge.executor

import android.content.Intent
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterization tests for [ActionExecutor.sendBroadcast] and its safety
 * blocklist, captured before the executor split. The blocklist pins the
 * security-relevant half of the contract: dangerous system broadcasts must be
 * rejected before anything is dispatched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorSendBroadcastTest {

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
    fun `sendBroadcast rejects an empty action`() {
        val result = ActionExecutor.sendBroadcast("")

        assertFalse(result.success)
        assertEquals("Broadcast action is empty", result.message)
        verify(exactly = 0) { mockService.sendBroadcast(any()) }
    }

    @Test
    fun `sendBroadcast blocks known-dangerous system broadcasts`() {
        val result = ActionExecutor.sendBroadcast("android.intent.action.PACKAGE_REMOVED")

        assertFalse(result.success)
        assertEquals(
            "Broadcast action 'android.intent.action.PACKAGE_REMOVED' is blocked for safety",
            result.message
        )
        verify(exactly = 0) { mockService.sendBroadcast(any()) }
    }

    @Test
    fun `sendBroadcast blocks factory reset`() {
        val result = ActionExecutor.sendBroadcast("android.intent.action.MASTER_CLEAR")

        assertFalse(result.success)
        assertTrue(result.message.contains("blocked for safety"))
        verify(exactly = 0) { mockService.sendBroadcast(any()) }
    }

    @Test
    fun `sendBroadcast allows custom namespace actions and forwards them`() {
        val result = ActionExecutor.sendBroadcast(
            "com.example.SYNC_NOW",
            mapOf("key" to "value")
        )

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Broadcast sent: com.example.SYNC_NOW", result.message)
        val intent = slot<Intent>()
        verify(exactly = 1) { mockService.sendBroadcast(capture(intent)) }
        assertEquals("com.example.SYNC_NOW", intent.captured.action)
        assertEquals("value", intent.captured.getStringExtra("key"))
    }

    @Test
    fun `sendBroadcast blocks reboot and shutdown requests`() {
        for (action in listOf(
            "android.intent.action.REBOOT",
            "com.android.internal.intent.action.REQUEST_SHUTDOWN"
        )) {
            val result = ActionExecutor.sendBroadcast(action)
            assertFalse(action, result.success)
            assertTrue(result.message.contains("blocked for safety"))
        }
        verify(exactly = 0) { mockService.sendBroadcast(any()) }
    }

    @Test
    fun `sendBroadcast turns any exception into an error result`() {
        // Only SecurityException used to be caught; anything else escaped to
        // the transport as a 500 instead of an ActionResult.
        every { mockService.sendBroadcast(any()) } throws IllegalArgumentException("bad extras")

        val result = ActionExecutor.sendBroadcast("com.example.SYNC_NOW")

        assertFalse(result.success)
        assertEquals("Broadcast failed: bad extras", result.message)
    }
}
