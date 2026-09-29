package com.hermesandroid.bridge.service

import android.app.Notification
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.hermesandroid.bridge.notification.NotificationStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring tests for the notification listener: everything it does that does not
 * need a real notification shade.
 *
 * The listener itself is thin — the framework hands it StatusBarNotifications
 * and it forwards them to NotificationStore — but three of its behaviours are
 * load-bearing and easy to break silently:
 *
 * - connect publishes `instance` and raises the store cap to 300, so a client
 *   polling every two minutes sees a real backlog rather than the last few;
 * - post must not store a notification with no readable content, because the
 *   store is served over the relay and an empty entry is pure noise;
 * - remove must *mark* rather than delete, or a poll that runs after the user
 *   cleared the shade never learns the notification existed.
 *
 * The service is built through Robolectric so onCreate() runs and the framework
 * worker thread exists, matching how Android actually binds the listener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BridgeNotificationListenerTest {

    private lateinit var listener: BridgeNotificationListener

    @Before
    fun setup() {
        resetStore()
        BridgeNotificationListener.instance?.onListenerDisconnected()
        listener = Robolectric.buildService(BridgeNotificationListener::class.java).create().get()
    }

    @After
    fun tearDown() {
        BridgeNotificationListener.instance?.onListenerDisconnected()
        resetStore()
    }

    /** The store is a process-wide object; leave nothing behind for later tests. */
    private fun resetStore() {
        NotificationStore.clear()
        NotificationStore.maxCapacity = 50
    }

    /**
     * A real StatusBarNotification with real extras: NotificationStore reads
     * `notification.extras` as a field, which no mock can stub.
     */
    private fun sbn(
        id: Int,
        pkg: String = "com.example.app",
        tag: String? = null,
        title: CharSequence? = "Title $id",
        text: CharSequence? = "Body $id",
        bigText: CharSequence? = null,
        subText: CharSequence? = null,
        summaryText: CharSequence? = null,
        category: String? = null,
        flags: Int = 0,
        postTime: Long = 1_000L + id,
        extrasPresent: Boolean = true,
    ): StatusBarNotification {
        val notification = Notification()
        notification.flags = flags
        notification.category = category
        if (extrasPresent) {
            notification.extras = Bundle().apply {
                title?.let { putCharSequence(Notification.EXTRA_TITLE, it) }
                text?.let { putCharSequence(Notification.EXTRA_TEXT, it) }
                bigText?.let { putCharSequence(Notification.EXTRA_BIG_TEXT, it) }
                subText?.let { putCharSequence(Notification.EXTRA_SUB_TEXT, it) }
                summaryText?.let { putCharSequence(Notification.EXTRA_SUMMARY_TEXT, it) }
            }
        } else {
            notification.extras = null
        }
        return StatusBarNotification(
            pkg,
            pkg,
            id,
            tag,
            Process.myUid(),
            Process.myPid(),
            0,
            notification,
            UserHandle.getUserHandleForUid(Process.myUid()),
            postTime,
        )
    }

    // --- lifecycle -----------------------------------------------------------

    @Test
    fun `no instance is published before the listener connects`() {
        assertNull(BridgeNotificationListener.instance)
    }

    @Test
    fun `connecting publishes the instance for other components`() {
        listener.onListenerConnected()

        assertSame(listener, BridgeNotificationListener.instance)
    }

    @Test
    fun `connecting raises the notification backlog cap to 300`() {
        NotificationStore.maxCapacity = 50

        listener.onListenerConnected()

        assertEquals(300, NotificationStore.maxCapacity)
    }

    @Test
    fun `disconnecting clears the instance`() {
        listener.onListenerConnected()

        listener.onListenerDisconnected()

        assertNull(BridgeNotificationListener.instance)
    }

    @Test
    fun `a reconnect after a disconnect republishes the new instance`() {
        listener.onListenerConnected()
        listener.onListenerDisconnected()
        val second = Robolectric.buildService(BridgeNotificationListener::class.java).create().get()

        second.onListenerConnected()

        assertSame(second, BridgeNotificationListener.instance)
    }

    // --- posted notifications ------------------------------------------------

    @Test
    fun `a posted notification lands in the store with its parsed fields`() {
        val posted = sbn(
            id = 7,
            pkg = "com.example.app",
            tag = "conversation",
            title = "Meeting",
            text = "In 10 minutes",
            subText = "via bridge",
            bigText = "In 10 minutes — bring the notes",
            summaryText = "1 more",
            category = Notification.CATEGORY_EVENT,
            postTime = 5_555L,
        )

        listener.onNotificationPosted(posted)

        val entry = NotificationStore.getAll(1).single()
        assertEquals(posted.key, entry.key)
        assertEquals("com.example.app", entry.packageName)
        assertEquals("Meeting", entry.title)
        assertEquals("In 10 minutes", entry.text)
        assertEquals("via bridge", entry.subText)
        assertEquals("In 10 minutes — bring the notes", entry.bigText)
        assertEquals("1 more", entry.summaryText)
        assertEquals(Notification.CATEGORY_EVENT, entry.category)
        assertEquals(5_555L, entry.timestamp)
        assertNull("a fresh notification must not look removed", entry.removedAt)
    }

    @Test
    fun `posted notifications are kept newest first`() {
        listener.onNotificationPosted(sbn(id = 1, postTime = 100L))
        listener.onNotificationPosted(sbn(id = 2, postTime = 200L))
        listener.onNotificationPosted(sbn(id = 3, postTime = 300L))

        assertEquals(
            listOf(300L, 200L, 100L),
            NotificationStore.getAll(10).map { it.timestamp },
        )
    }

    @Test
    fun `ongoing and clearable flags are read off the notification`() {
        listener.onNotificationPosted(sbn(id = 1, flags = Notification.FLAG_ONGOING_EVENT))
        listener.onNotificationPosted(sbn(id = 2, flags = 0))

        val ongoing = NotificationStore.getAll(10).first { it.timestamp == 1_001L }
        assertFalse("an ongoing notification is not user-clearable", ongoing.isClearable)
        assertTrue(ongoing.isOngoing)

        val clearable = NotificationStore.getAll(10).first { it.timestamp == 1_002L }
        assertTrue(clearable.isClearable)
        assertFalse(clearable.isOngoing)
    }

    @Test
    fun `a notification with no readable content is not stored`() {
        // No title, no text, no big text: nothing a client could act on, and the
        // store is served over the relay, so empty entries are pure noise.
        listener.onNotificationPosted(sbn(id = 1, title = null, text = null))

        assertTrue(NotificationStore.getAll(10).isEmpty())
    }

    @Test
    fun `blank content is treated as no content`() {
        listener.onNotificationPosted(sbn(id = 1, title = "   ", text = ""))

        assertTrue(NotificationStore.getAll(10).isEmpty())
    }

    @Test
    fun `a notification without extras is not stored`() {
        listener.onNotificationPosted(sbn(id = 1, extrasPresent = false))

        assertTrue(NotificationStore.getAll(10).isEmpty())
    }

    @Test
    fun `big text alone is enough to store a notification`() {
        listener.onNotificationPosted(sbn(id = 1, title = null, text = null, bigText = "Expanded body"))

        val entry = NotificationStore.getAll(1).single()
        assertEquals("Expanded body", entry.bigText)
    }

    @Test
    fun `post works without a prior connect`() {
        // The framework can deliver a post before onListenerConnected in a
        // rebound listener; buffering must not depend on the callback order.
        assertNull(BridgeNotificationListener.instance)

        listener.onNotificationPosted(sbn(id = 1))

        assertEquals(1, NotificationStore.getAll(10).size)
    }

    // --- removed notifications ----------------------------------------------

    @Test
    fun `a removed notification is marked, not dropped`() {
        val posted = sbn(id = 4)
        listener.onNotificationPosted(posted)

        listener.onNotificationRemoved(posted)

        assertTrue(
            "cleared notifications must leave the default read",
            NotificationStore.getAll(10).isEmpty(),
        )
        val retained = NotificationStore.getAll(10, includeRemoved = true)
        assertEquals(1, retained.size)
        assertEquals(posted.key, retained.single().key)
        assertTrue("the entry must carry removedAt", retained.single().removedAt != null)
    }

    @Test
    fun `removal of one notification leaves the others alone`() {
        val first = sbn(id = 1)
        val second = sbn(id = 2)
        listener.onNotificationPosted(first)
        listener.onNotificationPosted(second)

        listener.onNotificationRemoved(first)

        val keys = NotificationStore.getAll(10).map { it.key }
        assertEquals(listOf(second.key), keys)
        assertEquals(2, NotificationStore.getAll(10, includeRemoved = true).size)
    }

    @Test
    fun `removing an unknown notification is a no-op`() {
        listener.onNotificationPosted(sbn(id = 1))

        listener.onNotificationRemoved(sbn(id = 99))

        assertEquals(1, NotificationStore.getAll(10).size)
        assertEquals(1, NotificationStore.getAll(10, includeRemoved = true).size)
    }

    @Test
    fun `a reposted notification after removal is visible again`() {
        // Same key reposted (an updated notification): the markRemoved flag must
        // not hide the new posting from the default read.
        val posted = sbn(id = 5, text = "first")
        listener.onNotificationPosted(posted)
        listener.onNotificationRemoved(posted)
        assertTrue(NotificationStore.getAll(10).isEmpty())

        listener.onNotificationPosted(sbn(id = 5, text = "updated"))

        val visible = NotificationStore.getAll(10, includeRemoved = true)
        assertEquals(2, visible.size)
        assertEquals("updated", visible.first().text)
    }

    @Test
    fun `disconnect does not flush the retained backlog`() {
        // Characterisation: the store outlives the listener, so a client that
        // polls across a rebind still sees what arrived before it.
        listener.onNotificationPosted(sbn(id = 1))
        listener.onListenerConnected()

        listener.onListenerDisconnected()

        assertEquals(1, NotificationStore.getAll(10).size)
        assertNull(BridgeNotificationListener.instance)
    }
}
