package com.hermesandroid.bridge.executor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
 * Characterization tests for [ActionExecutor] clipboard actions, captured
 * before the executor split. They pin the observable contract, including the
 * detail that an empty clipboard is a *success* with empty-string data (not a
 * failure and not null).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorClipboardTest {

    private lateinit var mockService: BridgeAccessibilityService
    private lateinit var mockClipboard: ClipboardManager

    @Before
    fun setup() {
        mockService = mockk(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns mockService
        mockClipboard = mockk(relaxed = true)
        every { mockService.getSystemService(Context.CLIPBOARD_SERVICE) } returns mockClipboard
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `clipboardRead reports empty clipboard with empty-string data`() {
        every { mockClipboard.hasPrimaryClip() } returns false

        val result = ActionExecutor.clipboardRead()

        assertTrue(result.success)
        assertEquals("Clipboard is empty", result.message)
        assertEquals("", result.data)
    }

    @Test
    fun `clipboardRead reports empty when primary clip is null`() {
        every { mockClipboard.hasPrimaryClip() } returns true
        every { mockClipboard.primaryClip } returns null

        val result = ActionExecutor.clipboardRead()

        assertTrue(result.success)
        assertEquals("Clipboard is empty", result.message)
        assertEquals("", result.data)
    }

    @Test
    fun `clipboardRead reports empty when clip has no items`() {
        every { mockClipboard.hasPrimaryClip() } returns true
        every { mockClipboard.primaryClip } returns mockk(relaxed = true) // itemCount 0

        val result = ActionExecutor.clipboardRead()

        assertTrue(result.success)
        assertEquals("Clipboard is empty", result.message)
        assertEquals("", result.data)
    }

    @Test
    fun `clipboardRead returns the clip text`() {
        every { mockClipboard.hasPrimaryClip() } returns true
        every { mockClipboard.primaryClip } returns ClipData.newPlainText("label", "hello world")

        val result = ActionExecutor.clipboardRead()

        assertTrue(result.success)
        assertEquals("Clipboard read", result.message)
        assertEquals("hello world", result.data)
    }

    @Test
    fun `clipboardRead fails without the accessibility service`() {
        every { BridgeAccessibilityService.instance } returns null

        val result = ActionExecutor.clipboardRead()

        assertFalse(result.success)
        assertEquals("Accessibility service not running", result.message)
    }

    @Test
    fun `clipboardWrite sets a plain-text clip with the given text`() {
        val result = ActionExecutor.clipboardWrite("hi there")

        assertTrue(result.success)
        assertEquals("Copied to clipboard", result.message)
        val clip = slot<ClipData>()
        verify(exactly = 1) { mockClipboard.setPrimaryClip(capture(clip)) }
        assertEquals("hi there", clip.captured.getItemAt(0).text.toString())
    }
}
