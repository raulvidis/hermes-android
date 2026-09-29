package com.hermesandroid.bridge.event

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DROID-4 regression tests: the event-streaming switch must gate collection.
 *
 * `/events/stream` (`android_event_stream`) was a report, not a control:
 * `BridgeAccessibilityService.onAccessibilityEvent` buffered every event
 * regardless, `setStreaming(false)` cleared the buffer once, and it immediately
 * refilled. A user who turned streaming off believing the phone had stopped
 * capturing their interaction history had not stopped anything — any caller
 * holding the pairing code could still read `VIEW_TEXT_CHANGED` text from every
 * app via `GET /events`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventStoreStreamingGateTest {

    @Before
    fun setup() {
        EventStore.setStreaming(false)
        EventStore.clear()
    }

    @After
    fun tearDown() {
        EventStore.setStreaming(false)
        EventStore.clear()
        unmockkAll()
    }

    private fun typedEvent(text: String): AccessibilityEvent {
        val e = mockk<AccessibilityEvent>(relaxed = true)
        every { e.eventType } returns AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        every { e.packageName } returns "com.example.bank"
        every { e.className } returns "android.widget.EditText"
        // Non-nullable: AccessibilityEvent.getText() is a platform type mockk
        // will not accept null for.
        val chars = mutableListOf<CharSequence>()
        chars.add(text)
        every { e.text } returns chars
        every { e.eventTime } returns 1_700_000_000_000L
        val src = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { src.isPassword } returns false
        every { e.source } returns src
        return e
    }

    @Test
    fun `nothing is collected while streaming is disabled`() {
        assertEquals(false, EventStore.streamingEnabled)

        EventStore.add(typedEvent("typed while disabled"))

        assertTrue(EventStore.getAll(50).isEmpty())
        assertTrue(EventStore.getSince(0L, 50).isEmpty())
    }

    @Test
    fun `the source node is not even read while streaming is disabled`() {
        val event = typedEvent("typed while disabled")

        EventStore.add(event)

        verify(exactly = 0) { event.source }
    }

    @Test
    fun `events are collected once streaming is enabled`() {
        EventStore.setStreaming(true)

        EventStore.add(typedEvent("typed while enabled"))

        val stored = EventStore.getAll(50)
        assertEquals(1, stored.size)
        assertEquals("typed while enabled", stored.single().text)
    }

    @Test
    fun `disabling clears the buffer and stops further collection`() {
        EventStore.setStreaming(true)
        EventStore.add(typedEvent("first"))
        assertEquals(1, EventStore.getAll(50).size)

        EventStore.setStreaming(false)

        assertTrue("disabling must clear what was collected", EventStore.getAll(50).isEmpty())
        EventStore.add(typedEvent("second"))
        assertTrue("and must not refill", EventStore.getAll(50).isEmpty())
    }

    @Test
    fun `the disabled state is the default`() {
        // Set in setup(); asserted here so a future default flip is a test
        // failure rather than a silent privacy regression.
        assertEquals(false, EventStore.streamingEnabled)
    }
}
