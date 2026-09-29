package com.hermesandroid.bridge.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
 * Characterization tests for the gesture actions of [ActionExecutor]
 * (tap / tapText / swipe / scroll / longPress / drag / pinch), captured
 * before the executor is split into domain units. They pin the observable
 * contract — result success flags, message strings, and gesture dispatch
 * counts — so the split can be verified as behaviour-preserving.
 *
 * Note: a relaxed service mock's `windows` returns an empty list, so any
 * nodeId lookup finds nothing — that is the "node not found" branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorGestureTest {

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

    /** Simulates the OS reporting the dispatched gesture as completed. */
    private fun completeGesture() {
        every { mockService.dispatchGesture(any(), any(), any()) } answers {
            val callback = secondArg<AccessibilityService.GestureResultCallback>()
            callback.onCompleted(firstArg<GestureDescription>())
            true
        }
    }

    /** Simulates the OS cancelling the dispatched gesture. */
    private fun cancelGesture() {
        every { mockService.dispatchGesture(any(), any(), any()) } answers {
            val callback = secondArg<AccessibilityService.GestureResultCallback>()
            callback.onCancelled(firstArg<GestureDescription>())
            true
        }
    }

    @Test
    fun `tap requires either coordinates or nodeId`() = bridgeTest {
        val result = ActionExecutor.tap()

        assertFalse(result.success)
        assertEquals("Provide either (x, y) or nodeId", result.message)
    }

    @Test
    fun `tap reports missing node`() = bridgeTest {
        val result = ActionExecutor.tap(nodeId = "nope")

        assertFalse(result.success)
        assertEquals("Node not found: nope", result.message)
    }

    @Test
    fun `tap at coordinates succeeds when gesture completes`() = bridgeTest {
        completeGesture()

        val result = ActionExecutor.tap(x = 10, y = 20)

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Tapped (10, 20)", result.message)
    }

    @Test
    fun `tap at coordinates reports cancelled gesture`() = bridgeTest {
        cancelGesture()

        val result = ActionExecutor.tap(x = 1, y = 2)

        assertFalse(result.success)
        assertEquals("Tap gesture cancelled", result.message)
    }

    @Test
    fun `tap on node performs a click action`() = bridgeTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.app"
        every { root.className } returns "android.widget.Button"
        every { root.childCount } returns 0
        every { root.getBoundsInScreen(any()) } answers {
            val r = it.invocation.args[0] as Rect
            r.set(0, 0, 100, 100)
        }
        every { root.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        val win = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { win.root } returns root
        every { mockService.windows } returns listOf(win)
        val nodeId = "com.app_android.widget.Button_0_0_0_100_100"

        val result = ActionExecutor.tap(nodeId = nodeId)

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Tapped node $nodeId", result.message)
        verify(exactly = 1) { root.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `tapText falls back to coordinate tap when nothing is clickable`() = bridgeTest {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isClickable } returns false
        every { node.parent } returns null
        every { node.getBoundsInScreen(any()) } answers {
            val r = it.invocation.args[0] as Rect
            r.set(0, 0, 100, 100)
        }
        mockkObject(ScreenReader)
        every { ScreenReader.findNodeByText("OK", false) } returns node
        completeGesture()

        val result = ActionExecutor.tapText("OK")

        assertTrue("expected success — got: ${result.message}", result.success)
        // Center of (0,0)-(100,100).
        assertEquals("Tapped (50, 50)", result.message)
        verify { node.recycle() }
    }

    @Test
    fun `swipe reports unknown direction`() = bridgeTest {
        val result = ActionExecutor.swipe("diagonal")

        assertFalse(result.success)
        assertEquals("Unknown direction: diagonal", result.message)
    }

    @Test
    fun `swipe up dispatches one gesture and defaults to medium distance`() = bridgeTest {
        val result = ActionExecutor.swipe("up")

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Swiped up (medium)", result.message)
        verify(exactly = 1) { mockService.dispatchGesture(any(), null, null) }
    }

    @Test
    fun `swipe reports the requested distance in its message`() = bridgeTest {
        val result = ActionExecutor.swipe("down", "short")

        assertEquals("Swiped down (short)", result.message)
    }

    @Test
    fun `scroll without nodeId falls back to a medium swipe`() = bridgeTest {
        val result = ActionExecutor.scroll("down")

        assertEquals("Swiped down (medium)", result.message)
    }

    @Test
    fun `scroll reports unknown direction`() = bridgeTest {
        val result = ActionExecutor.scroll("sideways")

        assertFalse(result.success)
        assertEquals("Unknown direction: sideways", result.message)
    }

    @Test
    fun `scroll reports missing node`() = bridgeTest {
        val result = ActionExecutor.scroll("down", nodeId = "nope")

        assertFalse(result.success)
        assertEquals("Node not found: nope", result.message)
    }

    @Test
    fun `scroll on node scrolls forward`() = bridgeTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.app"
        every { root.className } returns "android.widget.ListView"
        every { root.childCount } returns 0
        every { root.getBoundsInScreen(any()) } answers {
            val r = it.invocation.args[0] as Rect
            r.set(0, 0, 100, 100)
        }
        every { root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } returns true
        val win = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { win.root } returns root
        every { mockService.windows } returns listOf(win)
        val nodeId = "com.app_android.widget.ListView_0_0_0_100_100"

        val result = ActionExecutor.scroll("down", nodeId = nodeId)

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Scrolled down in node $nodeId", result.message)
        verify(exactly = 1) { root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
    }

    @Test
    fun `longPress requires either coordinates or nodeId`() = bridgeTest {
        val result = ActionExecutor.longPress()

        assertFalse(result.success)
        assertEquals("Provide either (x, y) or nodeId", result.message)
    }

    @Test
    fun `longPress reports missing node`() = bridgeTest {
        val result = ActionExecutor.longPress(nodeId = "nope")

        assertFalse(result.success)
        assertEquals("Node not found: nope", result.message)
    }

    @Test
    fun `longPress at coordinates reports duration in message`() = bridgeTest {
        completeGesture()

        val result = ActionExecutor.longPress(x = 10, y = 20, duration = 750)

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Long pressed (10, 20) 750ms", result.message)
    }

    @Test
    fun `drag reports start and end coordinates`() = bridgeTest {
        completeGesture()

        val result = ActionExecutor.drag(0, 0, 100, 100)

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Dragged (0,0) to (100,100)", result.message)
    }

    @Test
    fun `pinch zoom in dispatches a single two-stroke gesture`() = bridgeTest {
        completeGesture()

        // Center kept away from the screen edges: the framework rejects
        // StrokeDescription paths with negative bounds, and pinch at
        // x < 75 with the default 1.5 scale draws a path into negative
        // territory (pre-existing latent crash — documented, not fixed here).
        val result = ActionExecutor.pinch(500, 500)

        assertTrue("expected success — got: ${result.message}", result.success)
        assertEquals("Pinch zoom in at (500, 500) scale 1.5", result.message)
        // Two fingers move in ONE multi-stroke gesture, not two dispatches.
        verify(exactly = 1) { mockService.dispatchGesture(any(), any(), any()) }
        val gesture = slot<GestureDescription>()
        verify { mockService.dispatchGesture(capture(gesture), any(), any()) }
        assertEquals(2, gesture.captured.strokeCount)
    }
}
