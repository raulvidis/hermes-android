package com.hermesandroid.bridge.event

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Contract tests for the accessibility event ring buffer behind GET /events.
 *
 * The store is the only place UI activity is retained on-device, so three
 * properties matter beyond "it does not crash":
 *
 * 1. Bounded memory. It is fed from onAccessibilityEvent, i.e. every scroll and
 *    keystroke on the device. Whatever maxCapacity says must hold, forever.
 * 2. Newest-first, stable ordering. Clients poll with a `since` cursor and
 *    treat the first entry as the latest event; reordering silently breaks the
 *    cursor contract.
 * 3. Thread safety. Adds arrive on the accessibility thread while /events reads
 *    arrive on Ktor's IO threads — a reader must never see a torn deque.
 *
 * Events are mocked rather than obtained via AccessibilityEvent.obtain(): the
 * store only reads six properties off the event, and Robolectric's event shadow
 * keeps a process-wide pool that would make the concurrency tests below depend
 * on shadow internals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventStoreTest {

    @Before
    fun resetStore() {
        EventStore.clear()
        EventStore.maxCapacity = DEFAULT_CAPACITY
        EventStore.streamingEnabled = false
    }

    @After
    fun tearDown() {
        // The store is a process-wide object: leave it as we found it so later
        // test classes do not inherit a small capacity or stale entries.
        EventStore.clear()
        EventStore.maxCapacity = DEFAULT_CAPACITY
        EventStore.streamingEnabled = false
        unmockkAll()
    }

    /**
     * An event with every field EventStore.add() reads stubbed explicitly.
     *
     * `relaxed = true` only covers properties the store does not touch; text and
     * source are stubbed because getText() is declared @NonNull in the platform
     * API and a relaxed mock would answer it with a child mock instead of a
     * list.
     */
    private fun event(
        eventType: Int = AccessibilityEvent.TYPE_VIEW_CLICKED,
        packageName: String? = "com.example.app",
        className: String? = "android.widget.Button",
        text: List<CharSequence> = emptyList(),
        contentDescription: String? = null,
        source: AccessibilityNodeInfo? = null,
        eventTime: Long = 1_000L,
    ): AccessibilityEvent {
        val e = mockk<AccessibilityEvent>(relaxed = true)
        every { e.eventType } returns eventType
        every { e.packageName } returns packageName
        every { e.className } returns className
        every { e.text } returns text.toMutableList()
        every { e.contentDescription } returns contentDescription
        every { e.source } returns source
        every { e.eventTime } returns eventTime
        return e
    }

    /** A source node reporting [bounds] in screen coordinates. */
    private fun sourceNode(
        packageName: String? = "com.example.app",
        className: String? = "android.widget.Button",
        bounds: Rect? = Rect(1, 2, 3, 4),
    ): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.packageName } returns packageName
        every { n.className } returns className
        if (bounds != null) {
            every { n.getBoundsInScreen(any()) } answers {
                firstArg<Rect>().set(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        } else {
            every { n.getBoundsInScreen(any()) } just runs
        }
        return n
    }

    /** Add [count] events with strictly increasing timestamps 1..count. */
    private fun addSequence(count: Int) {
        for (i in 1..count) {
            EventStore.add(event(eventTime = i.toLong(), text = listOf("e$i")))
        }
    }

    // --- field mapping -------------------------------------------------------

    @Test
    fun `every declared event type maps to its stable wire name`() {
        val expected = mapOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED to "VIEW_CLICKED",
            AccessibilityEvent.TYPE_VIEW_FOCUSED to "VIEW_FOCUSED",
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED to "VIEW_TEXT_CHANGED",
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED to "WINDOW_STATE_CHANGED",
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED to "WINDOW_CONTENT_CHANGED",
            AccessibilityEvent.TYPE_VIEW_SCROLLED to "VIEW_SCROLLED",
            AccessibilityEvent.TYPE_VIEW_SELECTED to "VIEW_SELECTED",
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED to "VIEW_TEXT_SELECTION_CHANGED",
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED to "NOTIFICATION_STATE_CHANGED",
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED to "VIEW_LONG_CLICKED",
        )

        for ((type, name) in expected) {
            EventStore.clear()
            EventStore.add(event(eventType = type))
            assertEquals(
                "event type $type",
                name,
                EventStore.getAll(1).single().eventType,
            )
        }
    }

    @Test
    fun `an undeclared event type is reported as OTHER with its numeric code`() {
        // New platform event types must reach the client as data, never be
        // dropped or crash the accessibility callback. 1 shl 30 is above every
        // TYPE_ constant declared in API 34 (the highest is 1 shl 26).
        val undeclared = 1 shl 30

        EventStore.add(event(eventType = undeclared))

        assertEquals("OTHER_$undeclared", EventStore.getAll(1).single().eventType)
    }

    @Test
    fun `package, class, description and timestamp are carried through`() {
        EventStore.add(
            event(
                packageName = "com.example.app",
                className = "android.widget.EditText",
                contentDescription = "Search field",
                eventTime = 123_456L,
            )
        )

        val stored = EventStore.getAll(1).single()
        assertEquals("com.example.app", stored.packageName)
        assertEquals("android.widget.EditText", stored.className)
        assertEquals("Search field", stored.contentDescription)
        assertEquals(123_456L, stored.timestamp)
    }

    @Test
    fun `missing package, class and description are stored as null`() {
        EventStore.add(event(packageName = null, className = null, contentDescription = null))

        val stored = EventStore.getAll(1).single()
        assertNull(stored.packageName)
        assertNull(stored.className)
        assertNull(stored.contentDescription)
    }

    @Test
    fun `multiple text chunks are joined into one comma separated string`() {
        EventStore.add(event(text = listOf("chunk one", "chunk two")))

        assertEquals("chunk one, chunk two", EventStore.getAll(1).single().text)
    }

    @Test
    fun `an empty text list is stored as null rather than an empty string`() {
        EventStore.add(event(text = emptyList()))

        assertNull(EventStore.getAll(1).single().text)
    }

    // --- source node id ------------------------------------------------------

    @Test
    fun `the source node id encodes package, class and screen bounds`() {
        EventStore.add(event(source = sourceNode()))

        assertEquals(
            "com.example.app_android.widget.Button_1_2_3_4",
            EventStore.getAll(1).single().sourceNodeId,
        )
    }

    @Test
    fun `the source node is recycled after its bounds are read`() {
        // AccessibilityNodeInfo instances are pooled by the platform; leaking
        // one per UI event exhausts the node pool quickly.
        val node = sourceNode()

        EventStore.add(event(source = node))

        verify(exactly = 1) { node.recycle() }
    }

    @Test
    fun `the source node is recycled even when reading bounds fails`() {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.getBoundsInScreen(any()) } throws RuntimeException("node died")

        try {
            EventStore.add(event(source = node))
        } catch (_: RuntimeException) {
            // add() does not guard the node read; the point here is the recycle.
        }

        verify(exactly = 1) { node.recycle() }
    }

    @Test
    fun `unknown package and class fall back to question marks in the node id`() {
        EventStore.add(event(source = sourceNode(packageName = null, className = null)))

        assertEquals("?_?_1_2_3_4", EventStore.getAll(1).single().sourceNodeId)
    }

    @Test
    fun `an event without a source has no node id`() {
        EventStore.add(event(source = null))

        assertNull(EventStore.getAll(1).single().sourceNodeId)
    }

    // --- ordering and limits -------------------------------------------------

    @Test
    fun `getAll returns newest event first`() {
        addSequence(3)

        assertEquals(listOf(3L, 2L, 1L), EventStore.getAll(50).map { it.timestamp })
    }

    @Test
    fun `getAll limit keeps only the newest entries`() {
        addSequence(10)

        val limited = EventStore.getAll(3)

        assertEquals(listOf(10L, 9L, 8L), limited.map { it.timestamp })
    }

    @Test
    fun `getAll with a limit larger than the store returns everything`() {
        addSequence(2)

        assertEquals(2, EventStore.getAll(500).size)
    }

    @Test
    fun `getAll on an empty store returns an empty list`() {
        assertTrue(EventStore.getAll(50).isEmpty())
    }

    @Test
    fun `getAll default limit is 50`() {
        addSequence(80)

        assertEquals(50, EventStore.getAll().size)
        assertEquals(80L, EventStore.getAll().first().timestamp)
    }

    // --- capacity and eviction ----------------------------------------------

    @Test
    fun `the oldest event is evicted once the capacity is reached`() {
        EventStore.maxCapacity = 3
        addSequence(5)

        val stored = EventStore.getAll(50)
        assertEquals(3, stored.size)
        assertEquals(listOf(5L, 4L, 3L), stored.map { it.timestamp })
    }

    @Test
    fun `the store never grows past its capacity`() {
        EventStore.maxCapacity = 10

        addSequence(250)

        assertEquals(10, EventStore.getAll(100).size)
    }

    @Test
    fun `capacity one keeps only the most recent event`() {
        EventStore.maxCapacity = 1
        addSequence(4)

        val stored = EventStore.getAll(50)
        assertEquals(1, stored.size)
        assertEquals(4L, stored.single().timestamp)
    }

    @Test
    fun `eviction drops the oldest entry, not the newest`() {
        // Regression guard on the ArrayDeque orientation: head = newest
        // (addFirst), tail = oldest (removeLast). Flipping either call would
        // turn the ring buffer into "keep the first N events ever seen".
        EventStore.maxCapacity = 2
        addSequence(4)

        assertEquals(listOf(4L, 3L), EventStore.getAll(50).map { it.timestamp })
    }

    @Test
    fun `lowering the capacity does not trim the retained backlog`() {
        // Characterisation, not an endorsement: eviction removes exactly one
        // entry per add, so a backlog above the new cap is kept indefinitely.
        // Bounded by the previous cap, so it is a staleness wart rather than a
        // leak — but the field name promises something stronger than this.
        EventStore.maxCapacity = 5
        addSequence(5)
        EventStore.maxCapacity = 2

        addSequence(3)

        assertEquals(5, EventStore.getAll(50).size)
    }

    @Test
    @Ignore(
        "BUG: EventStore.add() calls removeLast() on an empty ArrayDeque when " +
            "maxCapacity <= 0, so the first event after such an assignment throws " +
            "NoSuchElementException out of onAccessibilityEvent instead of being " +
            "dropped. Not reachable today (nothing in the app writes maxCapacity), " +
            "but the knob is public and the failure mode is a crash in a platform " +
            "callback.",
    )
    fun `a zero capacity drops events instead of throwing`() {
        EventStore.maxCapacity = 0

        EventStore.add(event())

        assertTrue(EventStore.getAll(50).isEmpty())
    }

    // --- since cursor --------------------------------------------------------

    @Test
    fun `getSince excludes the boundary timestamp`() {
        addSequence(5)

        // A client that polls with since = lastSeenTimestamp must not receive
        // that same event twice.
        assertEquals(listOf(5L, 4L), EventStore.getSince(3L, 50).map { it.timestamp })
    }

    @Test
    fun `getSince returns matching events newest first`() {
        addSequence(10)

        assertEquals(
            listOf(10L, 9L, 8L, 7L, 6L),
            EventStore.getSince(5L, 50).map { it.timestamp },
        )
    }

    @Test
    fun `getSince limit is applied after filtering`() {
        addSequence(10)

        assertEquals(listOf(10L, 9L), EventStore.getSince(5L, 2).map { it.timestamp })
    }

    @Test
    fun `getSince from zero returns the whole store`() {
        addSequence(3)

        assertEquals(3, EventStore.getSince(0L, 50).size)
    }

    @Test
    fun `getSince with a cursor newer than every event returns nothing`() {
        addSequence(3)

        assertTrue(EventStore.getSince(99L, 50).isEmpty())
    }

    @Test
    fun `getSince default limit is 50`() {
        addSequence(80)

        assertEquals(50, EventStore.getSince(0L).size)
    }

    // --- clear and streaming flag -------------------------------------------

    @Test
    fun `clear empties the store`() {
        addSequence(5)

        EventStore.clear()

        assertTrue(EventStore.getAll(50).isEmpty())
        assertTrue(EventStore.getSince(0L, 50).isEmpty())
    }

    @Test
    fun `setStreaming true flips the flag and keeps retained events`() {
        addSequence(2)

        EventStore.setStreaming(true)

        assertTrue(EventStore.streamingEnabled)
        assertEquals(2, EventStore.getAll(50).size)
    }

    @Test
    fun `setStreaming false flips the flag and drops retained events`() {
        addSequence(2)

        EventStore.setStreaming(false)

        assertEquals(false, EventStore.streamingEnabled)
        assertTrue("disabling the stream must not leave UI events on device", EventStore.getAll(50).isEmpty())
    }

    @Test
    fun `events are retained while the stream flag is off`() {
        // Characterisation: the flag gates what /events/stream reports, not what
        // onAccessibilityEvent buffers. Pinning it so a future "only record while
        // streaming" change is a deliberate decision rather than an accident.
        EventStore.setStreaming(false)

        addSequence(3)

        assertEquals(3, EventStore.getAll(50).size)
        assertEquals(false, EventStore.streamingEnabled)
    }

    // --- concurrency ---------------------------------------------------------

    @Test
    fun `concurrent adds stay within capacity and lose no entry to a torn write`() {
        EventStore.maxCapacity = 200
        val threads = 8
        val perThread = 50
        val stamps = AtomicInteger(0)
        // One shared event whose timestamp is unique per add: the deque contents
        // then double as a write log.
        val shared = mockk<AccessibilityEvent>(relaxed = true)
        every { shared.eventType } returns AccessibilityEvent.TYPE_VIEW_SCROLLED
        every { shared.packageName } returns "com.example.app"
        every { shared.className } returns "android.widget.ListView"
        every { shared.text } returns mutableListOf()
        every { shared.contentDescription } returns null
        every { shared.source } returns null
        every { shared.eventTime } answers { stamps.incrementAndGet().toLong() }

        val start = CountDownLatch(1)
        val finished = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>()
        val workers = (1..threads).map {
            Thread {
                try {
                    start.await()
                    repeat(perThread) { EventStore.add(shared) }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true }
        }
        workers.forEach { it.start() }
        start.countDown()

        assertTrue("workers did not finish", finished.await(60, TimeUnit.SECONDS))
        workers.forEach { it.join(5_000) }

        assertNull("concurrent add threw", failure.get())
        assertEquals(threads * perThread, stamps.get())
        val stored = EventStore.getAll(1_000)
        assertEquals("store must sit exactly at capacity", 200, stored.size)
        assertEquals(
            "a lost or duplicated write shows up as a repeated timestamp",
            200,
            stored.map { it.timestamp }.toSet().size,
        )
        assertTrue(
            "timestamps must come from the add sequence",
            stored.all { it.timestamp in 1..(threads * perThread) },
        )
    }

    @Test
    fun `reads concurrent with writes never see a torn deque`() {
        EventStore.maxCapacity = 50
        addSequence(50)
        val stamps = AtomicInteger(100)
        val shared = mockk<AccessibilityEvent>(relaxed = true)
        every { shared.eventType } returns AccessibilityEvent.TYPE_VIEW_CLICKED
        every { shared.packageName } returns "com.example.app"
        every { shared.className } returns null
        every { shared.text } returns mutableListOf()
        every { shared.contentDescription } returns null
        every { shared.source } returns null
        every { shared.eventTime } answers { stamps.incrementAndGet().toLong() }

        val stop = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                repeat(200) { EventStore.add(shared) }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                stop.countDown()
            }
        }.apply { isDaemon = true }
        val readers = (1..3).map {
            Thread {
                try {
                    while (stop.count > 0) {
                        val all = EventStore.getAll(20)
                        val since = EventStore.getSince(0L, 20)
                        // Snapshot invariants must hold mid-write.
                        check(all.size <= 50) { "getAll exceeded capacity: ${all.size}" }
                        check(since.size <= 50) { "getSince exceeded capacity: ${since.size}" }
                        check(all == all.sortedByDescending { it.timestamp }) { "getAll lost ordering" }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }.apply { isDaemon = true }
        }

        readers.forEach { it.start() }
        writer.start()
        assertTrue("writer did not finish", stop.await(60, TimeUnit.SECONDS))
        writer.join(5_000)
        readers.forEach { it.join(5_000) }

        assertNull("concurrent read/write threw: ${failure.get()}", failure.get())
        assertNotEquals(0, EventStore.getAll(50).size)
    }

    // --- wire shape ----------------------------------------------------------

    @Test
    fun `toMap exposes exactly the seven documented wire fields`() {
        addSequence(1)

        val map = EventStore.toMap(EventStore.getAll(1).single())

        assertEquals(
            setOf(
                "eventType",
                "packageName",
                "className",
                "text",
                "contentDescription",
                "sourceNodeId",
                "timestamp",
            ),
            map.keys,
        )
    }

    @Test
    fun `toMap carries the stored values including nulls`() {
        // Gson over the relay drops nulls, so a null field must at least be
        // present here for the HTTP transport to serialise it.
        EventStore.add(
            event(
                eventType = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                packageName = "com.example.app",
                className = null,
                text = listOf("typed"),
                contentDescription = null,
                source = null,
                eventTime = 7L,
            )
        )

        val map = EventStore.toMap(EventStore.getAll(1).single())

        assertEquals("VIEW_TEXT_CHANGED", map["eventType"])
        assertEquals("com.example.app", map["packageName"])
        assertNull(map["className"])
        assertEquals("typed", map["text"])
        assertNull(map["contentDescription"])
        assertNull(map["sourceNodeId"])
        assertEquals(7L, map["timestamp"])
    }

    private companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
