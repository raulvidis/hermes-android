package com.hermesandroid.bridge.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

data class NotificationEntry(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val bigText: String?,
    val summaryText: String?,
    val category: String?,
    val timestamp: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val removedAt: Long? = null
)

object NotificationStore {

    private val notifications = ArrayDeque<NotificationEntry>()
    private val lock = Any()
    @Volatile var maxCapacity: Int = 50

    /**
     * Stores [entry] as the newest notification. A notification re-posted with
     * the same key (an update — progress, new message in a thread, and so on)
     * replaces the earlier entry instead of sitting beside it, so each key
     * appears at most once and the ring buffer isn't filled with stale copies.
     */
    fun add(entry: NotificationEntry) {
        synchronized(lock) {
            notifications.removeAll { it.key == entry.key }
            while (notifications.isNotEmpty() && notifications.size >= maxCapacity) {
                notifications.removeLast()
            }
            notifications.addFirst(entry)
        }
    }

    fun getAll(limit: Int = 50, includeRemoved: Boolean = false): List<NotificationEntry> {
        synchronized(lock) {
            val source = if (includeRemoved) notifications
                else notifications.filter { it.removedAt == null }
            return source.take(limit)
        }
    }

    /**
     * Entries that changed after [sinceTimestamp].
     *
     * By default that means active entries posted after it. With
     * [includeRemoved], an entry also counts as changed when it was dismissed
     * after [sinceTimestamp] — a poller asking for removals must see a
     * notification posted before its last poll but cleared since, which a
     * post-time-only filter would miss.
     */
    fun getSince(sinceTimestamp: Long, limit: Int = 50, includeRemoved: Boolean = false): List<NotificationEntry> {
        synchronized(lock) {
            return notifications
                .filter { e ->
                    val removedAt = e.removedAt
                    if (includeRemoved) {
                        maxOf(e.timestamp, removedAt ?: Long.MIN_VALUE) > sinceTimestamp
                    } else {
                        removedAt == null && e.timestamp > sinceTimestamp
                    }
                }
                .take(limit)
        }
    }

    fun clear() {
        synchronized(lock) {
            notifications.clear()
        }
    }

    fun markRemoved(key: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            // Keep entries (flagged as removed) so the PA can log them AFTER
            // the user has read/cleared them — the old behaviour deleted them
            // instantly and the 2-min poll missed the event. add() keeps keys
            // unique, but flag every match anyway so no copy is left looking
            // active; an entry already flagged keeps its original removedAt.
            for (i in notifications.indices) {
                val e = notifications[i]
                if (e.key == key && e.removedAt == null) {
                    notifications[i] = e.copy(removedAt = now)
                }
            }
        }
    }

    fun parseNotification(sbn: StatusBarNotification): NotificationEntry? {
        val extras: Bundle? = sbn.notification?.extras
        if (extras == null) return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()

        if (title.isNullOrBlank() && text.isNullOrBlank() && bigText.isNullOrBlank()) {
            return null
        }

        return NotificationEntry(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = text,
            subText = subText,
            bigText = bigText,
            summaryText = summaryText,
            category = sbn.notification?.category,
            timestamp = sbn.postTime,
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable
        )
    }

    fun toMap(entry: NotificationEntry): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "key" to entry.key,
            "packageName" to entry.packageName,
            "title" to entry.title,
            "text" to entry.text,
            "subText" to entry.subText,
            "bigText" to entry.bigText,
            "summaryText" to entry.summaryText,
            "category" to entry.category,
            "timestamp" to entry.timestamp,
            "isOngoing" to entry.isOngoing,
            "isClearable" to entry.isClearable
        )
        // Only surfaced when removed entries were explicitly requested.
        if (entry.removedAt != null) {
            map["removedAt"] = entry.removedAt
        }
        return map
    }
}
