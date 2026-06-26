package com.hermesandroid.bridge.event

import android.view.accessibility.AccessibilityEvent

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
            text = event.text?.joinToString(", ")?.ifBlank { null },
            contentDescription = event.contentDescription?.toString(),
            sourceNodeId = event.source?.let { src ->
                try {
                    val r = android.graphics.Rect()
                    src.getBoundsInScreen(r)
                    "${src.packageName ?: "?"}_${src.className ?: "?"}_${r.left}_${r.top}_${r.right}_${r.bottom}"
                } finally { src.recycle() }
            },
            timestamp = event.eventTime
        )

        synchronized(lock) {
            if (events.size >= maxCapacity) {
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
