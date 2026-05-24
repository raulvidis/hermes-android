package com.hermesandroid.bridge.executor

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Regression tests ensuring AccessibilityNodeInfo objects are always recycled,
 * preventing resource leaks in the long-running accessibility service.
 */
class ActionExecutorRecycleTest {

    private lateinit var mockService: BridgeAccessibilityService

    @Before
    fun setup() {
        mockService = mockk(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns mockService
        // WakeLockManager wakeForAction just executes the block in tests
        mockkStatic("com.hermesandroid.bridge.power.WakeLockManager")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `typeText recycles focused node on success`() = runTest {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns "old text"
        every { node.performAction(any(), any<Bundle>()) } returns true

        every { mockService.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns node

        // Use reflection to call typeText directly — it's inside wakeForAction which
        // is a suspend inline fun. We verify recycle was called on the node.
        // Since we can't easily invoke the suspend fun in a unit test without
        // Robolectric, we verify the contract: findFocus returns a node, and after
        // the action completes, recycle() must have been called.
        val result = ActionExecutor.typeText("hello")

        verify { node.recycle() }
        assertTrue(result.success)
    }

    @Test
    fun `typeText recycles focused node even when performAction fails`() = runTest {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns ""
        every { node.performAction(any(), any<Bundle>()) } returns false

        every { mockService.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns node

        val result = ActionExecutor.typeText("hello")

        verify { node.recycle() }
        assertFalse(result.success)
    }

    @Test
    fun `typeText handles null focused node gracefully`() = runTest {
        every { mockService.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns null

        val result = ActionExecutor.typeText("hello")

        // Should not throw, and returns failure
        assertFalse(result.success)
        assertEquals("No focused input found", result.message)
    }

    @Test
    fun `typeText with clearFirst recycles node`() = runTest {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns "existing"
        every { node.performAction(any(), any<Bundle>()) } returns true

        every { mockService.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns node

        val result = ActionExecutor.typeText("new text", clearFirst = true)

        verify { node.recycle() }
        assertTrue(result.success)
    }

    @Test
    fun `tapText recycles node after successful click`() = runTest {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isClickable } returns true
        every { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        mockkObject(ScreenReader)
        every { ScreenReader.findNodeByText("Submit", false) } returns node

        val result = ActionExecutor.tapText("Submit")

        verify { node.recycle() }
        assertTrue(result.success)
    }
}
