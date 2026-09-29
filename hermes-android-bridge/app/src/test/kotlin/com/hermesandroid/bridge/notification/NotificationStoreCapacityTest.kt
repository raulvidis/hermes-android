package com.hermesandroid.bridge.notification

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review F11: dismissed notifications shared the live buffer.
 *
 * `markRemoved` retains entries in the same `ArrayDeque` that `add` evicts from
 * at `maxCapacity` (raised to 300 by `BridgeNotificationListener`). Since the
 * default read filters them out, retained dismissed entries consumed capacity
 * that only the opt-in `include_removed` path can see — and `add` evicted with
 * `removeLast()`, i.e. the *oldest* entry, which on a busy day is the long-lived
 * `isOngoing` one (music player, navigation, the bridge's own mic notification).
 *
 * So 300 notifications arriving and being dismissed evicted a notification the
 * user could still see in the shade, and `android_notifications()` reported a
 * shade that was missing it. Before dismissal retention was added, the buffer
 * held live notifications only, so this is a regression introduced by keeping
 * removed entries rather than deleting them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationStoreCapacityTest {

    private var savedCapacity: Int = NotificationStore.maxCapacity

    private fun entry(key: String, timestamp: Long, isOngoing: Boolean = false) = NotificationEntry(
        key = key,
        packageName = "com.example.app",
        title = "title-$key",
        text = "text-$key",
        subText = null,
        bigText = null,
        summaryText = null,
        category = null,
        timestamp = timestamp,
        isOngoing = isOngoing,
        isClearable = true,
    )

    private fun keys(includeRemoved: Boolean = false) =
        NotificationStore.getAll(100, includeRemoved = includeRemoved).map { it.key }

    @Before
    fun cleanStore() {
        NotificationStore.clear()
    }

    @After
    fun restoreCapacity() {
        NotificationStore.maxCapacity = savedCapacity
        NotificationStore.clear()
    }

    @Test
    fun `a live notification survives a flood of dismissed ones`() {
        NotificationStore.maxCapacity = 4
        // The oldest entry is the one a user can still see — an ongoing
        // notification posted that morning.
        NotificationStore.add(entry("ongoing", 1L, isOngoing = true))
        NotificationStore.add(entry("d1", 2L))
        NotificationStore.add(entry("d2", 3L))
        NotificationStore.add(entry("d3", 4L))
        NotificationStore.markRemoved("d1")
        NotificationStore.markRemoved("d2")
        NotificationStore.markRemoved("d3")

        NotificationStore.add(entry("d4", 5L))

        val live = NotificationStore.getActive(100).map { it.key }
        assertTrue(
            "the ongoing notification was evicted by dismissed ones; live = $live",
            live.contains("ongoing"),
        )
        assertEquals(4, keys(includeRemoved = true).size)
    }

    @Test
    fun `the oldest dismissed entry is evicted before a newer live one`() {
        NotificationStore.maxCapacity = 4
        NotificationStore.add(entry("live", 1L))
        NotificationStore.add(entry("r1", 2L))
        NotificationStore.add(entry("r2", 3L))
        NotificationStore.add(entry("r3", 4L))
        // r1 is the oldest dismissed entry; `live` is older still but still up.
        NotificationStore.markRemoved("r1")
        NotificationStore.markRemoved("r2")

        NotificationStore.add(entry("n", 5L))

        assertEquals(listOf("n", "r3", "r2", "live"), keys(includeRemoved = true))
        assertEquals(listOf("n", "r3", "live"), keys())
    }

    @Test
    fun `an all-live buffer still evicts the oldest and stays bounded`() {
        // The other half of the contract: preferring dismissed entries for
        // eviction must not let the buffer grow, and with nothing dismissed the
        // original oldest-first behaviour has to stand.
        NotificationStore.maxCapacity = 3
        NotificationStore.add(entry("a", 1L))
        NotificationStore.add(entry("b", 2L))
        NotificationStore.add(entry("c", 3L))

        NotificationStore.add(entry("d", 4L))

        assertEquals(listOf("d", "c", "b"), keys(includeRemoved = true))
    }

    @Test
    fun `churn never evicts a live entry while a dismissed one is available`() {
        NotificationStore.maxCapacity = 5

        repeat(60) { index ->
            val liveBefore = NotificationStore.getActive(100).map { it.key }.toSet()
            val dismissedPresent = NotificationStore.getAll(100, includeRemoved = true)
                .any { it.removedAt != null }

            NotificationStore.add(entry("n$index", index.toLong(), isOngoing = index == 0))
            if (index % 2 == 0 && index != 0) NotificationStore.markRemoved("n$index")

            if (dismissedPresent) {
                val liveAfter = NotificationStore.getActive(100).map { it.key }.toSet()
                assertTrue(
                    "at index $index a live entry was evicted while a dismissed one was " +
                        "available: lost ${liveBefore - liveAfter}",
                    liveAfter.containsAll(liveBefore),
                )
            }
        }

        // Bounded either way. Once nothing is dismissed, evicting the oldest live
        // entry is the original — and correct — behaviour; a fixed-size buffer
        // cannot keep a morning's ongoing notification forever.
        assertEquals(5, keys(includeRemoved = true).size)
        assertEquals(5, NotificationStore.maxCapacity)
    }
}
