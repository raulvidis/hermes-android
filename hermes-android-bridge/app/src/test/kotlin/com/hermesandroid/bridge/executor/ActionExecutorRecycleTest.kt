package com.hermesandroid.bridge.executor

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests ensuring AccessibilityNodeInfo objects are always recycled,
 * preventing resource leaks in the long-running accessibility service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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

    /**
     * Regression test for findNodeById root-recycle leak (medium severity).
     *
     * When the matching node is a CHILD (not the root), the root at the match
     * index was never recycled — only subsequent roots via subList. The sibling
     * ScreenReader.findNodeByText already handled this with `if (result !== root) root.recycle()`.
     * findNodeById omitted the same guard, leaking ancestor AccessibilityNodeInfo
     * objects over the long-running service lifetime.
     *
     * This test exercises findNodeById via the public describeNode(nodeId) entry
     * point with a 2-window setup where window 0 has no match and window 1's root
     * contains a matching child node. The root of window 1 must be recycled.
     */
    @Test
    fun `describeNode recycles match-index root when found node is a child`() {
        // Window 0: root with no matching child.
        val root0 = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root0.packageName } returns "com.app"
        every { root0.className } returns "android.widget.FrameLayout"
        every { root0.childCount } returns 0
        every { root0.getBoundsInScreen(any()) } answers { /* no-op */ }

        // Window 1: root whose child matches the target id.
        val root1 = mockk<AccessibilityNodeInfo>(relaxed = true)
        val child1 = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root1.packageName } returns "com.app"
        every { root1.className } returns "android.widget.FrameLayout"
        every { root1.childCount } returns 1
        every { root1.getChild(0) } returns child1
        every { root1.getBoundsInScreen(any()) } answers { /* no-op */ }

        // Child has the matching id and no children of its own.
        every { child1.packageName } returns "com.app"
        every { child1.className } returns "android.widget.Button"
        every { child1.childCount } returns 0
        every { child1.getBoundsInScreen(any()) } answers {
            val r = it.invocation.args[0] as Rect
            r.set(0, 0, 100, 100)
        }

        // Root0 and root1 bounds: give them distinct non-matching bounds so their
        // computed ids differ from the child target.
        every { root0.getBoundsInScreen(any()) } answers {
            val r = it.invocation.args[0] as Rect
            r.set(10, 10, 200, 200)
        }
        every { root1.getBoundsInScreen(any()) } answers {
            val r = it.invocation.args[0] as Rect
            r.set(20, 20, 300, 300)
        }

        val win0 = mockk<AccessibilityWindowInfo>(relaxed = true)
        val win1 = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { win0.root } returns root0
        every { win1.root } returns root1
        every { mockService.windows } returns listOf(win0, win1)

        // Target id matches the child's computed id:
        // "${packageName}_${className}_${path}_${left}_${top}_${right}_${bottom}"
        // child1 is at path "1_0" (window 1, child 0), bounds (0,0,100,100).
        val targetId = "com.app_android.widget.Button_1_0_0_0_100_100"

        val result = ActionExecutor.describeNode(targetId)

        assertTrue("describeNode should succeed — got: ${result.message}", result.success)
        // root0 was searched first and had no match → recycled.
        verify { root0.recycle() }
        // root1 is at the match index; with the old bug it would never be recycled
        // because `found` (the child) !== root1 and the subList only covers windows
        // AFTER the match index. After the fix, root1 MUST be recycled.
        verify { root1.recycle() }
        // The matched child is recycled by describeNode after use.
        verify { child1.recycle() }
        // Windows are recycled by findNodeById.
        verify { win0.recycle() }
        verify { win1.recycle() }
    }
}
