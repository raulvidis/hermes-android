package com.hermesandroid.bridge.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.util.DisplayMetrics
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * State-machine tests for POST /screen_record.
 *
 * record() is a fire-and-block call: it moves the whole capture onto a
 * dedicated HandlerThread, sleeps for the (clamped) duration, and reports one
 * map. The behaviours worth pinning are the ones a client can observe:
 *
 * - The two preconditions are checked *before* any capture work: no
 *   accessibility service and no projection grant must fail fast and must not
 *   start a foreground service or burn a permission token.
 * - A rejected or expired token (SecurityException / IllegalStateException)
 *   clears the stored grant, because on Android 14+ a MediaProjection token is
 *   single-use — retrying with it would fail forever. Any *other* failure keeps
 *   the grant, so a transient error does not force the user to re-consent.
 * - The duration cap is enforced, and the temp .mp4 is deleted on both paths.
 *
 * MediaProjection has no Robolectric shadow, so the projection, the virtual
 * display and the MediaRecorder are mocked. MediaRecorder is intercepted with
 * mockkConstructor and its setOutputFile stub writes the bytes a real encoder
 * would have produced — that is what lets the success path be asserted at all
 * (record() base64-encodes the file it was told to write).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenRecorderTest {

    private lateinit var cacheDir: File
    private lateinit var service: BridgeAccessibilityService
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var projection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay
    private val projectionCallback = slot<MediaProjection.Callback>()

    @Before
    fun setup() {
        cacheDir = java.nio.file.Files.createTempDirectory("screen-recorder-test").toFile()

        virtualDisplay = mockk(relaxed = true)
        projection = mockk(relaxed = true)
        projectionManager = mockk(relaxed = true)
        service = mockk(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        // Robolectric has no MediaProjection shadow and its ShadowMediaRecorder
        // runs a state machine irrelevant here, so every collaborator call the
        // recorder makes is stubbed explicitly.
        mockkConstructor(MediaRecorder::class)

        stubAll()
        warmUp()
    }

    /** Every stub the tests rely on. Re-applied after the warm-up clears mocks. */
    private fun stubAll() {
        every { projection.createVirtualDisplay(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            virtualDisplay
        every { projection.registerCallback(capture(projectionCallback), any()) } just runs
        every { projectionManager.getMediaProjection(any(), any()) } returns projection

        every { service.cacheDir } returns cacheDir
        every { service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) } returns projectionManager
        every { service.startForeground(any()) } just runs
        // DisplayMetrics has public fields, which no mock can stub: use a real one.
        every { service.resources.displayMetrics } returns DisplayMetrics().apply {
            widthPixels = WIDTH
            heightPixels = HEIGHT
            densityDpi = DENSITY_DPI
        }
        every { BridgeAccessibilityService.instance } returns service

        every { anyConstructed<MediaRecorder>().setVideoSource(any()) } just runs
        every { anyConstructed<MediaRecorder>().setOutputFormat(any()) } just runs
        every { anyConstructed<MediaRecorder>().setOutputFile(any<String>()) } answers {
            File(firstArg<String>()).writeBytes(FAKE_VIDEO_BYTES)
        }
        every { anyConstructed<MediaRecorder>().setVideoSize(any(), any()) } just runs
        every { anyConstructed<MediaRecorder>().setVideoEncoder(any()) } just runs
        every { anyConstructed<MediaRecorder>().setVideoEncodingBitRate(any()) } just runs
        every { anyConstructed<MediaRecorder>().setVideoFrameRate(any()) } just runs
        every { anyConstructed<MediaRecorder>().prepare() } just runs
        every { anyConstructed<MediaRecorder>().start() } just runs
        every { anyConstructed<MediaRecorder>().stop() } just runs
        every { anyConstructed<MediaRecorder>().release() } just runs
        every { anyConstructed<MediaRecorder>().surface } returns mockk(relaxed = true)
    }

    /**
     * Pay the one-off instrumentation cost before record() is on the clock.
     *
     * The first touch of Resources/MediaRecorder inside a fresh Robolectric
     * classloader costs seconds of class loading plus mockk retransformation.
     * record() only waits durationMs + 10s for its handler block, so without
     * this warm-up whichever test method happens to run first can have that
     * cost land inside the latch window and be reported as "Recording timed
     * out". Measured here: 6.3s cold, ~1ms warm.
     */
    private fun warmUp() {
        runCatching {
            val metrics = service.resources.displayMetrics
            MediaRecorder(service).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(File(cacheDir, "warm-up.mp4").absolutePath)
                setVideoSize(metrics.widthPixels, metrics.heightPixels)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(1)
                setVideoFrameRate(1)
                prepare()
                surface
                start()
                stop()
                release()
            }
        }
        File(cacheDir, "warm-up.mp4").delete()
        // clearAllMocks() drops the warm-up's recorded calls so verify(exactly
        // = n) counts only the test's own recording. It drops the stubs too,
        // hence the re-application.
        clearAllMocks()
        stubAll()
    }

    @After
    fun tearDown() {
        clearGrant()
        ScreenRecorder.maxDurationMs = PRODUCTION_CAP_MS
        unmockkAll()
        cacheDir.deleteRecursively()
    }

    // --- helpers -------------------------------------------------------------

    private fun grantPermission(action: String = "hermes.TEST_GRANT") {
        ScreenRecorder.setProjectionPermission(Activity.RESULT_OK, Intent().setAction(action))
    }

    /**
     * Drop the stored projection grant.
     *
     * ScreenRecorder exposes no public revoke — clearProjectionPermission() is
     * private and release() would quitSafely() the shared HandlerThread, after
     * which every later record() in this JVM would block on a dead looper. So
     * the two grant fields are reset reflectively instead.
     */
    private fun clearGrant() {
        listOf("projectionData", "projectionResultCode").forEach { name ->
            ScreenRecorder::class.java.getDeclaredField(name)
                .apply { isAccessible = true }
                .set(ScreenRecorder, null)
        }
    }

    private fun Map<String, Any?>.success(): Boolean = this["success"] as Boolean

    private fun Map<String, Any?>.message(): String = this["message"] as String

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.data(): Map<String, Any?> = this["data"] as Map<String, Any?>

    // --- permission gate -----------------------------------------------------

    @Test
    fun `no permission before a grant`() {
        assertFalse(ScreenRecorder.hasPermission())
    }

    @Test
    fun `a grant is remembered until it is dropped`() {
        grantPermission()

        assertTrue(ScreenRecorder.hasPermission())
    }

    @Test
    fun `record fails fast when the accessibility service is not running`() {
        grantPermission()
        every { BridgeAccessibilityService.instance } returns null

        val result = ScreenRecorder.record(50)

        assertFalse(result.success())
        assertEquals("Accessibility service not running", result.message())
        // Must not have started a foreground service or spent the token.
        verify(exactly = 0) { service.startForeground(any()) }
        verify(exactly = 0) { projectionManager.getMediaProjection(any(), any()) }
        assertTrue("the grant must survive a precondition failure", ScreenRecorder.hasPermission())
    }

    @Test
    fun `record fails fast without a projection grant`() {
        val result = ScreenRecorder.record(50)

        assertFalse(result.success())
        assertEquals(
            "No MediaProjection. Grant Screen Recording in the app before each capture on Android 16.",
            result.message(),
        )
        verify(exactly = 0) { service.startForeground(any()) }
        verify(exactly = 0) { projectionManager.getMediaProjection(any(), any()) }
    }

    @Test
    fun `the granted intent is replayed by value, not by reference`() {
        // setProjectionPermission copies the caller's intent. Without the copy a
        // later mutation of MainActivity's onActivityResult intent would change
        // what gets replayed to the platform minutes later.
        val granted = Intent().setAction("hermes.ORIGINAL")
        ScreenRecorder.setProjectionPermission(Activity.RESULT_OK, granted)
        val replayed = slot<Intent>()
        every { projectionManager.getMediaProjection(any(), capture(replayed)) } returns projection

        granted.action = "hermes.MUTATED"
        granted.putExtra("injected", true)
        ScreenRecorder.record(50)

        val used = replayed.captured
        assertNotSame("the recorder must not hold the caller's intent", granted, used)
        assertEquals("hermes.ORIGINAL", used.action)
        assertFalse("later extras must not leak into the replay", used.hasExtra("injected"))
    }

    // --- success path --------------------------------------------------------

    @Test
    fun `a successful recording returns base64 video and the display geometry`() {
        grantPermission()

        val result = ScreenRecorder.record(50)

        assertTrue(result.message(), result.success())
        assertEquals("Recorded 50ms", result.message())
        val data = result.data()
        assertEquals(WIDTH, data["width"])
        assertEquals(HEIGHT, data["height"])
        assertEquals(50L, data["durationMs"])
        assertEquals("video/mp4", data["mimeType"])
        assertEquals(FAKE_VIDEO_BYTES.size, data["sizeBytes"])
        assertArrayEquals(
            FAKE_VIDEO_BYTES,
            Base64.decode(data["video"] as String, Base64.NO_WRAP),
        )
    }

    @Test
    fun `a successful recording runs in the foreground with the media projection type`() {
        grantPermission()

        val result = ScreenRecorder.record(50)

        assertTrue(result.message(), result.success())
        verify(exactly = 1) { service.startForeground(includeMediaProjection = true) }
    }

    @Test
    fun `the recording is encoded at the display size and frame budget`() {
        grantPermission()

        val result = ScreenRecorder.record(50)

        assertTrue(result.message(), result.success())
        verify(exactly = 1) { anyConstructed<MediaRecorder>().setVideoSize(WIDTH, HEIGHT) }
        verify(exactly = 1) { anyConstructed<MediaRecorder>().setVideoFrameRate(30) }
        verify(exactly = 1) { anyConstructed<MediaRecorder>().setVideoEncodingBitRate(2_000_000) }
        verify(exactly = 1) {
            projection.createVirtualDisplay(
                "ScreenRecorder", WIDTH, HEIGHT, DENSITY_DPI, any(), any(), null, any(),
            )
        }
    }

    @Test
    fun `a successful recording deletes its temporary file`() {
        grantPermission()

        val result = ScreenRecorder.record(50)

        assertTrue(result.message(), result.success())
        assertTrue(
            "the .mp4 must not survive the base64 encode",
            cacheDir.listFiles().orEmpty().none { it.name.startsWith("screen_record_") },
        )
    }

    @Test
    fun `the projection is unregistered and stopped after a recording`() {
        grantPermission()

        val result = ScreenRecorder.record(50)

        assertTrue(result.message(), result.success())
        verify(exactly = 1) { projection.unregisterCallback(any()) }
        verify(exactly = 1) { projection.stop() }
        verify(exactly = 1) { virtualDisplay.release() }
        verify(exactly = 1) { anyConstructed<MediaRecorder>().stop() }
        verify(exactly = 1) { anyConstructed<MediaRecorder>().release() }
    }

    @Test
    fun `the recorded duration is clamped to the cap`() {
        // The cap is the only thing standing between a malformed durationMs and
        // a recording that fills the device's storage. CommandDispatcher clamps
        // too, so this is the recorder's own second line of defence.
        ScreenRecorder.maxDurationMs = 50L
        grantPermission()

        val result = ScreenRecorder.record(999_999L)

        assertTrue(result.message(), result.success())
        assertEquals("Recorded 50ms", result.message())
        assertEquals(50L, result.data()["durationMs"])
    }

    @Test
    fun `a duration under the cap is used as given`() {
        ScreenRecorder.maxDurationMs = 5_000L
        grantPermission()

        val result = ScreenRecorder.record(120)

        assertTrue(result.message(), result.success())
        assertEquals(120L, result.data()["durationMs"])
    }

    @Test
    fun `the grant survives a successful recording`() {
        // Characterisation. On Android 14+ a projection token is single-use, so
        // the next record() with this same grant fails at getMediaProjection and
        // only *then* drops it (see the SecurityException test below). Pinned so
        // that behaviour is a decision rather than an accident.
        grantPermission()

        val result = ScreenRecorder.record(50)

        assertTrue(result.message(), result.success())
        assertTrue(ScreenRecorder.hasPermission())
    }

    @Test
    fun `concurrent record calls serialise on the handler thread and both succeed`() {
        // Two /screen_record requests landing at once must not interleave
        // MediaRecorder.start()/stop() or corrupt each other's output file.
        grantPermission()
        val results = arrayOfNulls<Map<String, Any?>>(2)
        val finished = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        (0..1).forEach { index ->
            Thread {
                try {
                    results[index] = ScreenRecorder.record(60)
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true; start() }
        }

        assertTrue("recordings did not finish", finished.await(60, TimeUnit.SECONDS))

        assertNull("record threw: ${failure.get()}", failure.get())
        results.forEachIndexed { index, result ->
            assertTrue("recording $index failed: ${result?.message()}", result!!.success())
            assertEquals(
                "recording $index must have encoded a whole file",
                FAKE_VIDEO_BYTES.size,
                result.data()["sizeBytes"],
            )
        }
        assertTrue(
            "no temp file may survive",
            cacheDir.listFiles().orEmpty().none { it.name.startsWith("screen_record_") },
        )
    }

    @Test
    fun `a platform stop callback after the recording is harmless`() {
        // The system revoking the projection (user pulled down the "stop
        // casting" notification) lands as Callback.onStop -> cleanupRecording.
        grantPermission()
        val first = ScreenRecorder.record(50)
        assertTrue(first.message(), first.success())

        projectionCallback.captured.onStop()

        val second = ScreenRecorder.record(50)
        assertTrue("a late onStop must not wedge the recorder: ${second.message()}", second.success())
    }

    // --- error paths ---------------------------------------------------------

    @Test
    fun `a rejected projection token fails and drops the grant`() {
        grantPermission()
        every { projectionManager.getMediaProjection(any(), any()) } returns null

        val result = ScreenRecorder.record(50)

        assertFalse(result.success())
        assertTrue(result.message(), result.message().contains("Recording failed: IllegalStateException"))
        assertTrue(result.message(), result.message().contains("token was not accepted"))
        assertFalse(
            "a token the platform refused must not be replayed again",
            ScreenRecorder.hasPermission(),
        )
    }

    @Test
    fun `a SecurityException from the platform fails and drops the grant`() {
        grantPermission()
        every { projectionManager.getMediaProjection(any(), any()) } throws
            SecurityException("MediaProjection token already used")

        val result = ScreenRecorder.record(50)

        assertFalse(result.success())
        assertTrue(result.message(), result.message().contains("Recording failed: SecurityException"))
        assertFalse(ScreenRecorder.hasPermission())
    }

    @Test
    fun `an IllegalStateException while starting fails and drops the grant`() {
        grantPermission()
        every { anyConstructed<MediaRecorder>().start() } throws
            IllegalStateException("start failed: encoder unavailable")

        val result = ScreenRecorder.record(50)

        assertFalse(result.success())
        assertTrue(result.message(), result.message().contains("Recording failed: IllegalStateException"))
        assertFalse(ScreenRecorder.hasPermission())
        verify(exactly = 1) { anyConstructed<MediaRecorder>().release() }
        verify(exactly = 1) { virtualDisplay.release() }
        assertTrue(
            "the partial .mp4 must be deleted",
            cacheDir.listFiles().orEmpty().none { it.name.startsWith("screen_record_") },
        )
    }

    @Test
    fun `an unexpected failure keeps the grant`() {
        // Only a refused/expired token clears the grant. A transient failure
        // must not force the user back through the consent dialog.
        grantPermission()
        every { service.startForeground(any()) } throws RuntimeException("foreground service denied")

        val result = ScreenRecorder.record(50)

        assertFalse(result.success())
        assertTrue(result.message(), result.message().contains("Recording failed: RuntimeException"))
        assertTrue(ScreenRecorder.hasPermission())
    }

    @Test
    fun `a failure to create the virtual display is reported and cleaned up`() {
        grantPermission()
        every {
            projection.createVirtualDisplay(any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("no free display")

        val result = ScreenRecorder.record(50)

        assertFalse(result.success())
        assertTrue(result.message(), result.message().contains("Recording failed: RuntimeException"))
        assertTrue("an unrelated failure keeps the grant", ScreenRecorder.hasPermission())
        verify(exactly = 1) { anyConstructed<MediaRecorder>().release() }
        verify(exactly = 1) { projection.stop() }
    }

    @Test
    @Ignore(
        "BUG: a MediaRecorder whose configuration or prepare() throws is never " +
            "released. record() assigns `recorder = mr` only after the apply{} " +
            "block returns, so cleanupRecording() in the catch sees a null field " +
            "and the native recorder (encoder + camera/projection client) leaks. " +
            "Reachable on a real device whenever prepare() fails — codec " +
            "unavailable, storage full, invalid surface — and the next capture " +
            "then fails with the recorder still held.",
    )
    fun `the recorder is released when prepare fails`() {
        grantPermission()
        every { anyConstructed<MediaRecorder>().prepare() } throws
            IllegalStateException("codec unavailable")

        val result = ScreenRecorder.record(50)

        assertFalse(result.success())
        assertTrue(result.message(), result.message().contains("Recording failed: IllegalStateException"))
        verify(exactly = 1) { anyConstructed<MediaRecorder>().release() }
        assertTrue(
            "the output file must still be deleted",
            cacheDir.listFiles().orEmpty().none { it.name.startsWith("screen_record_") },
        )
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val DENSITY_DPI = 480
        const val PRODUCTION_CAP_MS = 30_000L

        /** What a real H.264 encoder would have left in the output file. */
        val FAKE_VIDEO_BYTES: ByteArray = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x6D, 0x70, 0x34, 0x32, 0x01, 0x02, 0x03, 0x04,
        )
    }
}
