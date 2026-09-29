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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PII-1 regression tests for `/describe_node`.
 *
 * describeNode emitted `text`, `contentDescription` **and** `hintText` for every
 * node. For a password field all three are sensitive: the text is the secret, and
 * the hint/content description routinely carry its mask or format. The node must
 * still be describable — bounds, class and viewIdResourceName are what an agent
 * needs to act on it.
 *
 * Robolectric because `hintText` is only read when SDK_INT >= O.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionExecutorDescribeNodePasswordTest {

    private lateinit var mockService: BridgeAccessibilityService

    private companion object {
        const val SECRET = "hunter2-super-secret"
        const val APP = "com.example.bank"
        const val CLASS = "android.widget.EditText"

        /** Mirrors the stable id format used by ScreenReader/ActionExecutor. */
        const val NODE_ID = "${APP}_${CLASS}_0_0_0_0_0"
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

    @Suppress("UNCHECKED_CAST")
    private fun describe(
        isPassword: Boolean,
        text: String?,
        contentDescription: String?,
        hint: String?
    ): Map<String, Any?> {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.packageName } returns APP
        every { node.className } returns CLASS
        every { node.childCount } returns 0
        every { node.text } returns text
        every { node.contentDescription } returns contentDescription
        every { node.hintText } returns hint
        every { node.isPassword } returns isPassword
        every { node.viewIdResourceName } returns "$APP:id/password"
        every { node.isEditable } returns true

        val window = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { window.root } returns node
        every { mockService.windows } returns listOf(window)

        val result = ActionExecutor.describeNode(NODE_ID)

        assertTrue("describeNode should succeed: ${result.message}", result.success)
        return result.data as Map<String, Any?>
    }

    @Test
    fun `describeNode redacts a password field`() {
        val data = describe(
            isPassword = true,
            text = SECRET,
            contentDescription = "Password, 8 characters minimum",
            hint = "Enter your password"
        )

        assertEquals(SensitiveText.REDACTED, data["text"])
        assertNull("contentDescription must be dropped", data["contentDescription"])
        assertNull("hintText must be dropped", data["hintText"])
        assertEquals(true, data["password"])
        assertFalse("the secret must not appear anywhere", data.toString().contains(SECRET))
        assertFalse(data.toString().contains("8 characters minimum"))
    }

    @Test
    fun `describeNode keeps everything needed to act on a redacted field`() {
        val data = describe(isPassword = true, text = SECRET, contentDescription = null, hint = null)

        assertEquals(NODE_ID, data["nodeId"])
        assertEquals(CLASS, data["className"])
        assertEquals(APP, data["packageName"])
        assertEquals("$APP:id/password", data["viewIdResourceName"])
        assertEquals(true, data["editable"])
        assertTrue("bounds must survive so the field can be tapped", data["bounds"] != null)
    }

    @Test
    fun `describeNode leaves an ordinary field untouched`() {
        val data = describe(
            isPassword = false,
            text = "user@example.com",
            contentDescription = "Email address",
            hint = "Enter your email"
        )

        assertEquals("user@example.com", data["text"])
        assertEquals("Email address", data["contentDescription"])
        assertEquals("Enter your email", data["hintText"])
        assertEquals(false, data["password"])
    }

    @Test
    fun `describeNode reports password false rather than omitting the flag`() {
        val data = describe(isPassword = false, text = "ok", contentDescription = null, hint = null)

        assertTrue(data.containsKey("password"))
        assertEquals(false, data["password"])
    }
}
