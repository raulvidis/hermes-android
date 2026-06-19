package com.hermesandroid.bridge.executor

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for System UI filtering in ScreenReader (issue #34).
 *
 * System UI (status bar, nav bar) wastes tokens and churns screen hashes. It must
 * be excluded by default, but still available via includeSystemUi=true.
 */
class ScreenReaderTest {

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

    private fun node(pkg: String?, className: String?, childCount: Int = 0): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.packageName } returns pkg
        every { n.className } returns className
        every { n.childCount } returns childCount
        return n
    }

    private fun window(root: AccessibilityNodeInfo): AccessibilityWindowInfo {
        val w = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { w.root } returns root
        return w
    }

    @Test
    fun `readCurrentScreen excludes System UI by default`() {
        val appRoot = node("com.example.app", "android.widget.FrameLayout")
        val sysRoot = node("com.android.systemui", "android.widget.FrameLayout")

        every { mockService.windows } returns listOf(window(appRoot), window(sysRoot))

        val result = ScreenReader.readCurrentScreen(includeBounds = false)

        assertEquals(1, result.size)
        assertEquals("com.example.app", result[0].packageName)
        result.forEach { assertFalse(it.packageName == "com.android.systemui") }
    }

    @Test
    fun `readCurrentScreen includes System UI when requested`() {
        val appRoot = node("com.example.app", "android.widget.FrameLayout")
        val sysRoot = node("com.android.systemui", "android.widget.FrameLayout")

        every { mockService.windows } returns listOf(window(appRoot), window(sysRoot))

        val result = ScreenReader.readCurrentScreen(includeBounds = false, includeSystemUi = true)

        assertEquals(2, result.size)
        val packages = result.map { it.packageName }
        assertTrue("app node present", packages.contains("com.example.app"))
        assertTrue("systemui node present", packages.contains("com.android.systemui"))
    }

    @Test
    fun `readCurrentScreen drops nested System UI subtrees`() {
        // App root with a systemui child (e.g. an overlay) should drop only the child subtree.
        val appRoot = node("com.example.app", "android.widget.FrameLayout", childCount = 1)
        val sysChild = node("com.android.systemui", "android.widget.ImageView")
        every { appRoot.getChild(0) } returns sysChild

        every { mockService.windows } returns listOf(window(appRoot))

        val result = ScreenReader.readCurrentScreen(includeBounds = false)

        assertEquals(1, result.size)
        assertEquals("com.example.app", result[0].packageName)
        assertTrue("systemui child must be filtered out", result[0].children.isEmpty())
    }

    @Test
    fun `readCurrentScreen with no service returns empty list`() {
        every { BridgeAccessibilityService.instance } returns null

        val result = ScreenReader.readCurrentScreen(includeBounds = false)

        assertTrue(result.isEmpty())
    }
}
