package com.hermesandroid.bridge.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque

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
    var removed: Boolean = false
)

object NotificationStore {

    private val notifications = ConcurrentLinkedDeque<NotificationEntry>()
    private val lock = Any()
    var maxCapacity: Int = 50

    fun add(entry: NotificationEntry) {
        synchronized(lock) {
            if (notifications.size >= maxCapacity) {
                notifications.removeLast()
            }
            notifications.addFirst(entry)
        }
    }

    fun getAll(limit: Int = 50): List<NotificationEntry> {
        synchronized(lock) {
            return notifications.filter { !it.removed }.take(limit)
        }
    }

    fun getSince(sinceTimestamp: Long, limit: Int = 50): List<NotificationEntry> {
        synchronized(lock) {
            return notifications.filter { !it.removed && it.timestamp > sinceTimestamp }.take(limit)
        }
    }

    fun clear() {
        synchronized(lock) {
            notifications.clear()
        }
    }

    fun markRemoved(key: String) {
        synchronized(lock) {
            notifications.find { it.key == key }?.removed = true
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
        return mapOf(
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
    }
}
