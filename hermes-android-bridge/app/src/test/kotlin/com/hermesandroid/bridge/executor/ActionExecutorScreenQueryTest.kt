package com.hermesandroid.bridge.executor

import com.hermesandroid.bridge.bridgeTest
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.model.computeHash
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterization tests for [ActionExecutor] screen-query actions
 * (findNodes / diffScreen / screenHash / waitForElement / describeNode /
 * readWidgets), captured before the executor split.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorScreenQueryTest {

    private lateinit var mockService: BridgeAccessibilityService

    @Before
    fun setup() {
        mockService = mockk(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns mockService
        mockkObject(ScreenReader)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun node(id: String, text: String?, children: List<ScreenNode> = emptyList()) =
        ScreenNode(
            nodeId = id,
            text = text,
            contentDescription = null,
            className = "android.widget.TextView",
            packageName = "com.app",
            clickable = false,
            focusable = false,
            scrollable = false,
            editable = false,
            checked = null,
            bounds = null,
            children = children
        )

    @Test
    fun `findNodes reports no matches`() {
        every { ScreenReader.searchNodes(any(), any(), any(), any()) } returns emptyList()

        val result = ActionExecutor.findNodes(text = "Missing")

        assertFalse(result.success)
        assertEquals("No matching nodes found", result.message)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `findNodes returns matching nodes with a count`() {
        val matches = listOf(
            mapOf("nodeId" to "n1", "text" to "OK"),
            mapOf("nodeId" to "n2", "text" to "Okay")
        )
        every { ScreenReader.searchNodes("OK", null, null, 20) } returns matches

        val result = ActionExecutor.findNodes(text = "OK")

        assertTrue(result.success)
        assertEquals("Found 2 nodes", result.message)
        val data = result.data as Map<String, Any?>
        assertEquals(2, data["count"])
        assertEquals(matches, data["nodes"])
    }

    @Test
    fun `diffScreen reports no screen content`() {
        every { ScreenReader.readCurrentScreen(false) } returns emptyList()

        val result = ActionExecutor.diffScreen("old")

        assertFalse(result.success)
        assertEquals("No screen content", result.message)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `diffScreen reports no changes for an identical hash`() {
        val nodes = listOf(node("n1", "Hello"))
        every { ScreenReader.readCurrentScreen(false) } returns nodes
        val hash = nodes.joinToString("|") { it.computeHash() }

        val result = ActionExecutor.diffScreen(hash)

        assertTrue(result.success)
        assertEquals("No changes detected", result.message)
        val data = result.data as Map<String, Any?>
        assertEquals(false, data["changed"])
        assertEquals(hash, data["hash"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `diffScreen reports changes with the node count`() {
        val nodes = listOf(node("n1", "Hello", listOf(node("n2", "World"))))
        every { ScreenReader.readCurrentScreen(false) } returns nodes

        val result = ActionExecutor.diffScreen("stale-hash")

        assertTrue(result.success)
        assertEquals("Screen changed", result.message)
        val data = result.data as Map<String, Any?>
        assertEquals(true, data["changed"])
        assertEquals(2, data["nodeCount"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `screenHash returns the joined hash and total node count`() {
        val nodes = listOf(node("n1", "Hello", listOf(node("n2", "World"))))
        every { ScreenReader.readCurrentScreen(false) } returns nodes

        val result = ActionExecutor.screenHash()

        assertTrue(result.success)
        assertEquals("Screen hash", result.message)
        val data = result.data as Map<String, Any?>
        assertEquals(nodes.joinToString("|") { it.computeHash() }, data["hash"])
        assertEquals(2, data["nodeCount"])
    }

    @Test
    fun `waitForElement returns the matching node`() = bridgeTest {
        val target = node("n1", "Hello")
        every { ScreenReader.readCurrentScreen(false) } returns listOf(target)

        val result = ActionExecutor.waitForElement(text = "Hello")

        assertTrue(result.success)
        assertEquals("Element found", result.message)
        assertSame(target, result.data)
    }

    @Test
    fun `waitForElement times out when nothing matches`() = bridgeTest {
        every { ScreenReader.readCurrentScreen(false) } returns listOf(node("n1", "Hello"))

        val result = ActionExecutor.waitForElement(text = "ZZZ", timeoutMs = 1000)

        assertFalse(result.success)
        assertEquals("Timeout waiting for element (text=ZZZ, class=null)", result.message)
    }

    @Test
    fun `describeNode reports a missing node`() {
        // Relaxed mock: no windows → findNodeById finds nothing.
        val result = ActionExecutor.describeNode("whatever")

        assertFalse(result.success)
        assertEquals("Node not found: whatever", result.message)
    }

    @Test
    fun `readWidgets reports an empty screen`() = bridgeTest {
        every { mockService.windows } returns emptyList()

        val result = ActionExecutor.readWidgets()

        assertTrue(result.success)
        assertEquals("Found 0 widget elements", result.message)
    }
}
