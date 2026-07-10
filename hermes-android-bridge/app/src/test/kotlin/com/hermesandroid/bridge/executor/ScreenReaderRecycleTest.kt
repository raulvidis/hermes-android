package com.hermesandroid.bridge.executor

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for AccessibilityNodeInfo recycling in findNodeByText.
 *
 * When a match is found deep in the tree, every intermediate ancestor node
 * obtained via getChild() on the path to the match must be recycled — only
 * the matched node itself is returned un-recycled (caller owns it). Leaking
 * ancestors on every /tap_text call exhausts the accessibility node pool.
 */
class ScreenReaderRecycleTest {

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

    private fun node(text: String? = null, childCount: Int = 0): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        every { n.childCount } returns childCount
        return n
    }

    private fun window(root: AccessibilityNodeInfo): AccessibilityWindowInfo {
        val w = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { w.root } returns root
        return w
    }

    @Test
    fun `findNodeByText recycles intermediate ancestors on the path to a deep match`() {
        // root -> ancestor -> match
        val match = node(text = "Target")
        val ancestor = node(childCount = 1)
        every { ancestor.getChild(0) } returns match
        val root = node(childCount = 1)
        every { root.getChild(0) } returns ancestor

        every { mockService.windows } returns listOf(window(root))

        val found = ScreenReader.findNodeByText("Target", exact = true)

        assertSame(match, found)
        verify(exactly = 1) { ancestor.recycle() }
        verify(exactly = 1) { root.recycle() }
        verify(exactly = 0) { match.recycle() }
    }

    @Test
    fun `findNodeByText does not recycle a directly matching child`() {
        // root -> match (child itself is the match; must survive for the caller)
        val match = node(text = "Target")
        val root = node(childCount = 1)
        every { root.getChild(0) } returns match

        every { mockService.windows } returns listOf(window(root))

        val found = ScreenReader.findNodeByText("Target", exact = true)

        assertSame(match, found)
        verify(exactly = 0) { match.recycle() }
        verify(exactly = 1) { root.recycle() }
    }

    @Test
    fun `findNodeByText recycles siblings explored before the match`() {
        // root -> [miss, ancestor -> match]
        val miss = node(text = "Other")
        val match = node(text = "Target")
        val ancestor = node(childCount = 1)
        every { ancestor.getChild(0) } returns match
        val root = node(childCount = 2)
        every { root.getChild(0) } returns miss
        every { root.getChild(1) } returns ancestor

        every { mockService.windows } returns listOf(window(root))

        val found = ScreenReader.findNodeByText("Target", exact = true)

        assertSame(match, found)
        verify(exactly = 1) { miss.recycle() }
        verify(exactly = 1) { ancestor.recycle() }
        verify(exactly = 0) { match.recycle() }
    }
}
