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
    fun `getSince with includeRemoved surfaces a dismissal newer than since`() {
        // Posted long before the poller's last check, dismissed after it.
        // A post-time-only filter would hide the dismissal from the poller.
        NotificationStore.add(entry("old-post", 1000L))
        NotificationStore.markRemoved("old-post")
        val since = 5000L // after the post, before the (wall-clock) removal

        val withRemoved = NotificationStore.getSince(since, 50, includeRemoved = true).map { it.key }
        assertEquals(listOf("old-post"), withRemoved)

        // The default read still excludes removed entries entirely.
        assertTrue(NotificationStore.getSince(since, 50).isEmpty())
    }

    @Test
    fun `getSince with includeRemoved skips entries whose post and removal both predate since`() {
        NotificationStore.add(entry("stale", 1000L))
        NotificationStore.markRemoved("stale")
        val since = System.currentTimeMillis() + 60_000L

        assertTrue(NotificationStore.getSince(since, 50, includeRemoved = true).isEmpty())
    }

    @Test
    fun `getSince default read still filters on post time`() {
        NotificationStore.add(entry("before", 1000L))
        NotificationStore.add(entry("after", 3000L))

        assertEquals(listOf("after"), NotificationStore.getSince(2000L, 50).map { it.key })
    }

    @Test
    fun `re-posting a key replaces the earlier entry`() {
        NotificationStore.add(entry("chat", 1000L))
        NotificationStore.add(entry("other", 1500L))
        NotificationStore.add(entry("chat", 2000L).copy(text = "updated"))

        val all = NotificationStore.getAll(50, includeRemoved = true)
        assertEquals(listOf("chat", "other"), all.map { it.key })
        assertEquals("updated", all.first().text)
        assertEquals(2000L, all.first().timestamp)
    }

    @Test
    fun `dismissing an updated notification leaves no active copy`() {
        // The reported bug: an update stored a second copy, markRemoved
        // flagged only the newest, and the stale copy stayed in the shade.
        NotificationStore.add(entry("chat", 1000L))
        NotificationStore.add(entry("chat", 2000L))
        NotificationStore.markRemoved("chat")

        assertTrue(NotificationStore.getAll(50).none { it.key == "chat" })
    }

    @Test
    fun `re-posting a removed key yields a single active entry`() {
        NotificationStore.add(entry("chat", 1000L))
        NotificationStore.markRemoved("chat")
        NotificationStore.add(entry("chat", 2000L))

        val all = NotificationStore.getAll(50, includeRemoved = true)
        assertEquals(1, all.size)
        assertNull(all.single().removedAt)
    }

    @Test
    fun `markRemoved keeps the original removal time on a repeat call`() {
        NotificationStore.add(entry("a", 1000L))
        NotificationStore.markRemoved("a")
        val first = NotificationStore.getAll(50, includeRemoved = true).single().removedAt
        Thread.sleep(5)
        NotificationStore.markRemoved("a")
        val second = NotificationStore.getAll(50, includeRemoved = true).single().removedAt

        assertEquals(first, second)
    }

    @Test
    fun `replacing a key does not evict an unrelated entry at capacity`() {
        val previous = NotificationStore.maxCapacity
        try {
            NotificationStore.maxCapacity = 2
            NotificationStore.add(entry("a", 1000L))
            NotificationStore.add(entry("b", 2000L))
            NotificationStore.add(entry("a", 3000L)) // update, not a new key

            assertEquals(listOf("a", "b"), NotificationStore.getAll(50).map { it.key })
        } finally {
            NotificationStore.maxCapacity = previous
        }
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
