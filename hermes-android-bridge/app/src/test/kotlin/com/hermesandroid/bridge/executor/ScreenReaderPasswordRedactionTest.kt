package com.hermesandroid.bridge.executor

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hermesandroid.bridge.security.SensitiveText
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PII-1 regression tests: password fields must never leave the device as text.
 *
 * `AccessibilityNodeInfo.isPassword` was not consulted anywhere in the bridge, so
 * a login screen driven by the agent (the documented use case) serialised the
 * user's password — or the OTP the IME had just auto-filled — into `/screen` and
 * `/find_nodes`, and from there into the transcript and the model provider.
 *
 * Contract: the field stays visible and addressable (so the agent can still tap
 * and type into it by node id), its text becomes "[redacted]", and its
 * contentDescription is dropped.
 */
class ScreenReaderPasswordRedactionTest {

    private lateinit var mockService: BridgeAccessibilityService

    private companion object {
        const val SECRET = "hunter2-super-secret"
        const val APP = "com.example.bank"
    }

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

    private fun node(
        className: String,
        text: String? = null,
        contentDescription: String? = null,
        isPassword: Boolean = false,
        childCount: Int = 0
    ): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.packageName } returns APP
        every { n.className } returns className
        every { n.text } returns text
        every { n.contentDescription } returns contentDescription
        every { n.isPassword } returns isPassword
        every { n.childCount } returns childCount
        return n
    }

    private fun window(root: AccessibilityNodeInfo): AccessibilityWindowInfo {
        val w = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { w.root } returns root
        return w
    }

    private fun passwordField() = node(
        className = "android.widget.EditText",
        text = SECRET,
        contentDescription = "Password, 8 characters minimum",
        isPassword = true
    )

    private fun loginScreen(): AccessibilityNodeInfo {
        val root = node("android.widget.FrameLayout", childCount = 2)
        every { root.getChild(0) } returns node(
            className = "android.widget.EditText",
            text = "user@example.com"
        )
        every { root.getChild(1) } returns passwordField()
        return root
    }

    @Test
    fun `readCurrentScreen redacts a password field but keeps the node`() {
        every { mockService.windows } returns listOf(window(loginScreen()))

        val result = ScreenReader.readCurrentScreen(includeBounds = false)

        assertEquals(1, result.size)
        val children = result[0].children
        assertEquals("the field must stay in the tree", 2, children.size)

        val secret = children[1]
        assertEquals(SensitiveText.REDACTED, secret.text)
        assertNull("contentDescription must be dropped", secret.contentDescription)
        assertEquals("android.widget.EditText", secret.className)
        assertTrue(secret.nodeId.isNotBlank())
    }

    @Test
    fun `readCurrentScreen never emits the secret anywhere in the tree`() {
        every { mockService.windows } returns listOf(window(loginScreen()))

        val result = ScreenReader.readCurrentScreen(includeBounds = true)

        assertFalse(result.toString().contains(SECRET))
    }

    @Test
    fun `readCurrentScreen leaves ordinary fields untouched`() {
        every { mockService.windows } returns listOf(window(loginScreen()))

        val result = ScreenReader.readCurrentScreen(includeBounds = false)

        val username = result[0].children[0]
        assertEquals("user@example.com", username.text)
        assertEquals("android.widget.EditText", username.className)
    }

    @Test
    fun `readCurrentScreen keeps an ordinary contentDescription`() {
        val root = node(
            className = "android.widget.ImageButton",
            contentDescription = "Show password",
            childCount = 0
        )
        every { mockService.windows } returns listOf(window(root))

        val result = ScreenReader.readCurrentScreen(includeBounds = false)

        assertEquals("Show password", result[0].contentDescription)
        assertNull(result[0].text)
    }

    @Test
    fun `searchNodes redacts the secret and flags the field`() {
        every { mockService.windows } returns listOf(window(loginScreen()))

        val results = ScreenReader.searchNodes(classNameFilter = "EditText")

        assertEquals(2, results.size)
        val secret = results.first { it["password"] == true }
        assertEquals(SensitiveText.REDACTED, secret["text"])
        assertNull(secret["contentDescription"])
        assertEquals("android.widget.EditText", secret["className"])
        assertFalse(results.toString().contains(SECRET))
    }

    @Test
    fun `searchNodes does not flag ordinary fields`() {
        every { mockService.windows } returns listOf(window(loginScreen()))

        val results = ScreenReader.searchNodes(classNameFilter = "EditText")

        val username = results.first { it["password"] == null }
        assertEquals("user@example.com", username["text"])
    }

    @Test
    fun `searchNodes cannot be used as an oracle for the secret`() {
        every { mockService.windows } returns listOf(window(loginScreen()))

        val bySecret = ScreenReader.searchNodes(textFilter = SECRET)
        val byFragment = ScreenReader.searchNodes(textFilter = "hunter2")

        assertTrue("exact secret must not match", bySecret.isEmpty())
        assertTrue("a fragment of the secret must not match", byFragment.isEmpty())
    }

    @Test
    fun `searchNodes still finds the field by class so it can be typed into`() {
        every { mockService.windows } returns listOf(window(loginScreen()))

        val results = ScreenReader.searchNodes(textFilter = SensitiveText.REDACTED)

        assertEquals(1, results.size)
        assertEquals(true, results[0]["password"])
    }

    @Test
    fun `findNodeByText does not match on a secret field's text`() {
        every { mockService.windows } returns listOf(window(loginScreen()))

        assertNull(ScreenReader.findNodeByText(SECRET))
        assertNull(ScreenReader.findNodeByText("hunter2"))
    }

    @Test
    fun `findNodeByText still matches ordinary text`() {
        every { mockService.windows } returns listOf(window(loginScreen()))

        val found = ScreenReader.findNodeByText("user@example.com")

        assertTrue(found != null)
        found?.recycle()
    }
}
