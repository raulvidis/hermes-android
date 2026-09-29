package com.hermesandroid.bridge.event

import android.view.accessibility.AccessibilityEvent
import com.hermesandroid.bridge.security.SensitiveText

data class AccessibilityEventData(
    val eventType: String,
    val packageName: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val sourceNodeId: String?,
    val timestamp: Long
)

object EventStore {
    private val events = ArrayDeque<AccessibilityEventData>()
    private val lock = Any()
    @Volatile var maxCapacity: Int = 200
    @Volatile var streamingEnabled: Boolean = false

    fun add(event: AccessibilityEvent) {
        // DROID-4: the streaming switch must gate *collection*, not just the
        // `/events` report. Buffering while "disabled" meant any authenticated
        // caller could still read what the user typed and clicked across every
        // app — setStreaming(false) cleared the buffer once and it immediately
        // refilled. Returning here also skips the event.source IPC entirely.
        if (!streamingEnabled) return

        // PII-1: read the source node once (the caller owns it and must recycle it)
        // and use the same handle to detect a secret field. AccessibilityEvent's
        // own getText() is a different code path from node text and is not
        // password-marked, so the source node is the only reliable signal.
        var secretField = false
        val sourceNodeId = event.source?.let { src ->
            try {
                secretField = SensitiveText.isSecret(src)
                val r = android.graphics.Rect()
                src.getBoundsInScreen(r)
                "${src.packageName ?: "?"}_${src.className ?: "?"}_${r.left}_${r.top}_${r.right}_${r.bottom}"
            } finally { src.recycle() }
        }

        val entry = AccessibilityEventData(
            eventType = when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
                AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "VIEW_TEXT_SELECTION_CHANGED"
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
                else -> "OTHER_${event.eventType}"
            },
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            // A secret field keeps its event (so the agent can see that something
            // was typed there) but never what was typed.
            text = event.text?.joinToString(", ")?.ifBlank { null }?.let { raw ->
                if (secretField) SensitiveText.REDACTED else raw
            },
            contentDescription = if (secretField) null else event.contentDescription?.toString(),
            sourceNodeId = sourceNodeId,
            timestamp = event.eventTime
        )

        synchronized(lock) {
            // Read the volatile knob once: a concurrent change must not make the
            // eviction loop below disagree with this guard.
            val capacity = maxCapacity
            // A non-positive cap means "retain nothing", so drop the event.
            // Without this guard the loop below would call removeLast() on an
            // empty deque and throw NoSuchElementException out of
            // onAccessibilityEvent.
            if (capacity <= 0) return
            // ArrayDeque: head=newest (addFirst), tail=oldest (removeLast).
            // removeLast drops the oldest entry to make room for the new one.
            // `while`, not `if`, so lowering maxCapacity trims an oversized
            // backlog down to the new cap on the next add. capacity >= 1 here,
            // so the deque is never empty when removeLast() runs.
            while (events.size >= capacity) {
                events.removeLast()
            }
            events.addFirst(entry)
        }
    }

    fun getAll(limit: Int = 50): List<AccessibilityEventData> {
        synchronized(lock) {
            return events.take(limit)
        }
    }

    fun getSince(sinceTimestamp: Long, limit: Int = 50): List<AccessibilityEventData> {
        synchronized(lock) {
            return events.filter { it.timestamp > sinceTimestamp }.take(limit)
        }
    }

    fun clear() {
        synchronized(lock) {
            events.clear()
        }
    }

    fun setStreaming(enabled: Boolean) {
        streamingEnabled = enabled
        if (!enabled) clear()
    }

    fun toMap(event: AccessibilityEventData): Map<String, Any?> {
        return mapOf(
            "eventType" to event.eventType,
            "packageName" to event.packageName,
            "className" to event.className,
            "text" to event.text,
            "contentDescription" to event.contentDescription,
            "sourceNodeId" to event.sourceNodeId,
            "timestamp" to event.timestamp
        )
    }
}
