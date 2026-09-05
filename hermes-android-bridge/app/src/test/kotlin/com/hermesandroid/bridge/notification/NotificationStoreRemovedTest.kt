package com.hermesandroid.bridge.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for dismissed-notification handling (#100 follow-up).
 *
 * markRemoved() retains entries on-device (flagged with removedAt) so polling
 * clients can observe dismissal events — but /notifications serves them only
 * on explicit opt-in. Cleared notifications can hold PII the user has already
 * dealt with, so the default read must exclude them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationStoreRemovedTest {

    private fun entry(key: String, timestamp: Long) = NotificationEntry(
        key = key,
        packageName = "com.example.app",
        title = "title-$key",
        text = "text-$key",
        subText = null,
        bigText = null,
        summaryText = null,
        category = null,
        timestamp = timestamp,
        isOngoing = false,
        isClearable = true,
    )

    @Before
    fun cleanStore() {
        NotificationStore.clear()
    }

    @Test
    fun `removed notifications are excluded from reads by default`() {
        NotificationStore.add(entry("kept", 1000L))
        NotificationStore.add(entry("cleared", 2000L))
        NotificationStore.markRemoved("cleared")

        val keys = NotificationStore.getAll(50).map { it.key }
        assertEquals(listOf("kept"), keys)

        val sinceKeys = NotificationStore.getSince(0L, 50).map { it.key }
        assertEquals(listOf("kept"), sinceKeys)
    }

    @Test
    fun `removed notifications are served on explicit opt-in`() {
        NotificationStore.add(entry("kept", 1000L))
        NotificationStore.add(entry("cleared", 2000L))
        NotificationStore.markRemoved("cleared")

        val all = NotificationStore.getAll(50, includeRemoved = true)
        assertEquals(2, all.size)
        val removed = all.first { it.key == "cleared" }
        assertTrue("retained entry must carry removedAt", removed.removedAt != null)
        assertNull("active entry must not carry removedAt", all.first { it.key == "kept" }.removedAt)

        val since = NotificationStore.getSince(0L, 50, includeRemoved = true)
        assertEquals(2, since.size)
    }

    @Test
    fun `toMap only surfaces removedAt when present`() {
        NotificationStore.add(entry("plain", 1000L))
        val plainMap = NotificationStore.toMap(NotificationStore.getAll(1).first())
        assertTrue("removedAt must stay out of the default shape", !plainMap.containsKey("removedAt"))

        NotificationStore.markRemoved("plain")
        val removedMap = NotificationStore.toMap(
            NotificationStore.getAll(1, includeRemoved = true).first()
        )
        assertTrue("opt-in reads must surface removedAt", removedMap.containsKey("removedAt"))
    }
}
