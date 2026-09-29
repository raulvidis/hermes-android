package com.hermesandroid.bridge.server

import android.view.accessibility.AccessibilityEvent
import com.google.gson.JsonObject
import com.hermesandroid.bridge.bridgeTest
import com.hermesandroid.bridge.event.EventStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DROID-4 regression tests for `GET /events`.
 *
 * The endpoint used to read the buffer and merely *report* `streaming`; it never
 * checked it. With collection now gated at the source, this is the second half:
 * the API cannot contradict the switch, whatever is in the buffer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommandDispatcherEventsGateTest {

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

    @Suppress("UNCHECKED_CAST")
    private fun events(rawResult: Any): Map<String, Any> = rawResult as Map<String, Any>

    private fun storeOne(text: String) {
        val e = mockk<AccessibilityEvent>(relaxed = true)
        every { e.eventType } returns AccessibilityEvent.TYPE_VIEW_CLICKED
        every { e.packageName } returns "com.example.app"
        every { e.className } returns "android.widget.Button"
        val chars = mutableListOf<CharSequence>()
        chars.add(text)
        every { e.text } returns chars
        every { e.eventTime } returns 1_700_000_000_000L
        every { e.source } returns null
        EventStore.add(e)
    }

    @Test
    fun `events are empty while streaming is disabled`() = bridgeTest {
        val (rawResult, status) = CommandDispatcher.dispatch(
            "GET", "/events", JsonObject(), JsonObject(), true
        )

        assertEquals(200, status)
        val result = events(rawResult)
        assertEquals(false, result["streaming"])
        assertEquals(0, result["count"])
        assertEquals(emptyList<Any>(), result["events"])
    }

    @Test
    fun `a stale buffer is not served after streaming is switched off`() = bridgeTest {
        EventStore.setStreaming(true)
        storeOne("clicked while enabled")
        EventStore.streamingEnabled = false

        val (rawResult, _) = CommandDispatcher.dispatch(
            "GET", "/events", JsonObject(), JsonObject(), true
        )

        val result = events(rawResult)
        assertEquals(0, result["count"])
        assertEquals(emptyList<Any>(), result["events"])
    }

    @Test
    fun `events are served while streaming is enabled`() = bridgeTest {
        EventStore.setStreaming(true)
        storeOne("clicked while enabled")

        val (rawResult, status) = CommandDispatcher.dispatch(
            "GET", "/events", JsonObject(), JsonObject(), true
        )

        assertEquals(200, status)
        val result = events(rawResult)
        assertEquals(true, result["streaming"])
        assertEquals(1, result["count"])
        assertTrue(result["events"].toString().contains("clicked while enabled"))
    }

    @Test
    fun `the switch endpoint still reports what it set`() = bridgeTest {
        val body = JsonObject().apply { addProperty("enabled", true) }

        val (rawResult, status) = CommandDispatcher.dispatch(
            "POST", "/events/stream", JsonObject(), body, true
        )

        assertEquals(200, status)
        assertEquals(true, events(rawResult)["streaming"])
        assertEquals(true, EventStore.streamingEnabled)
    }
}
