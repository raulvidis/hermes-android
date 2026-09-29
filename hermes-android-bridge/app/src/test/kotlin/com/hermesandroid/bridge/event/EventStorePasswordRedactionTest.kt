package com.hermesandroid.bridge.event

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.security.SensitiveText
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
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
 * PII-1 regression tests for the `/events` buffer.
 *
 * `EventStore.add` took its text from `AccessibilityEvent.getText()`, a different
 * code path from node text, so `VIEW_TEXT_CHANGED` carried whatever the user (or
 * the agent) typed — including into password fields — and `GET /events` served it
 * to any authenticated caller. The source node is the only password signal
 * available on an event, so it is read once here and reused for the redaction
 * decision (and still recycled exactly once).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventStorePasswordRedactionTest {

    private companion object {
        const val SECRET = "hunter2-super-secret"
        const val APP = "com.example.bank"
        const val CLASS = "android.widget.EditText"
    }

    @Before
    fun setup() {
        // DROID-4: collection is gated on the streaming switch, so these tests
        // have to turn it on to store anything at all.
        EventStore.setStreaming(true)
        EventStore.clear()
    }

    @After
    fun tearDown() {
        EventStore.setStreaming(false)
        EventStore.clear()
        unmockkAll()
    }

    private fun event(
        // Non-nullable: AccessibilityEvent.getText() is a platform type that
        // mockk will not accept a null for. An empty list means "no text".
        text: MutableList<CharSequence>,
        contentDescription: String?,
        isPassword: Boolean,
        eventType: Int = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        withSource: Boolean = true
    ): Pair<AccessibilityEvent, AccessibilityNodeInfo?> {
        val e = mockk<AccessibilityEvent>(relaxed = true)
        every { e.eventType } returns eventType
        every { e.packageName } returns APP
        every { e.className } returns CLASS
        every { e.text } returns text
        every { e.contentDescription } returns contentDescription
        every { e.eventTime } returns 1_700_000_000_000L

        val src: AccessibilityNodeInfo? = if (withSource) {
            mockk<AccessibilityNodeInfo>(relaxed = true).also {
                every { it.isPassword } returns isPassword
                every { it.packageName } returns APP
                every { it.className } returns CLASS
            }
        } else null
        every { e.source } returns src
        return e to src
    }

    @Test
    fun `text typed into a password field is redacted`() {
        val (e, _) = event(mutableListOf<CharSequence>(SECRET), "Password", isPassword = true)

        EventStore.add(e)

        val stored = EventStore.getAll(10).single()
        assertEquals(SensitiveText.REDACTED, stored.text)
        assertNull("contentDescription must be dropped", stored.contentDescription)
    }

    @Test
    fun `the rest of the event is still useful`() {
        val (e, _) = event(mutableListOf<CharSequence>(SECRET), "Password", isPassword = true)

        EventStore.add(e)

        val stored = EventStore.getAll(10).single()
        assertEquals("VIEW_TEXT_CHANGED", stored.eventType)
        assertEquals(APP, stored.packageName)
        assertEquals(CLASS, stored.className)
        assertTrue("sourceNodeId must survive so the field can be addressed", stored.sourceNodeId != null)
        assertEquals(1_700_000_000_000L, stored.timestamp)
    }

    @Test
    fun `the secret never reaches the served map`() {
        val (e, _) = event(mutableListOf<CharSequence>(SECRET), "Password, 8 characters minimum", isPassword = true)

        EventStore.add(e)

        val stored = EventStore.getAll(10).single()
        val served = EventStore.toMap(stored)
        assertFalse(served.toString().contains(SECRET))
        assertFalse(served.toString().contains("8 characters minimum"))
        assertEquals(SensitiveText.REDACTED, served["text"])
    }

    @Test
    fun `an event with no text is not turned into a redaction marker`() {
        val (e, _) = event(mutableListOf(), null, isPassword = true)

        EventStore.add(e)

        assertNull(EventStore.getAll(10).single().text)
    }

    @Test
    fun `ordinary fields are untouched`() {
        val (e, _) = event(mutableListOf<CharSequence>("user@example.com"), "Email address", isPassword = false)

        EventStore.add(e)

        val stored = EventStore.getAll(10).single()
        assertEquals("user@example.com", stored.text)
        assertEquals("Email address", stored.contentDescription)
    }

    @Test
    fun `the source node is still recycled exactly once`() {
        val (e, src) = event(mutableListOf<CharSequence>(SECRET), null, isPassword = true)

        EventStore.add(e)

        verify(exactly = 1) { src?.recycle() }
    }

    @Test
    fun `an event without a source is stored without crashing`() {
        val (e, _) = event(mutableListOf<CharSequence>("plain"), null, isPassword = false, withSource = false)

        EventStore.add(e)

        assertEquals("plain", EventStore.getAll(10).single().text)
    }

    @Test
    fun `getSince redacts the same way`() {
        val (e, _) = event(mutableListOf<CharSequence>(SECRET), null, isPassword = true)

        EventStore.add(e)

        val served = EventStore.getSince(0L, 10).single()
        assertEquals(SensitiveText.REDACTED, served.text)
    }
}
