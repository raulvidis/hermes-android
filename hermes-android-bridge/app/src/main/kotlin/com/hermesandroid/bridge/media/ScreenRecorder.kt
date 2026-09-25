package com.hermesandroid.bridge.media

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import java.io.File

object ScreenRecorder {
    private const val NO_PERMISSION_MESSAGE =
        "No MediaProjection. Grant Screen Recording in the app before each capture on Android 16."
    private const val MAX_DURATION_MS = 30_000L

    /**
     * The user's MediaProjection consent (the result of the system capture
     * dialog). Held as one immutable object so a recording can clear exactly
     * the grant it consumed — never a newer one set while it was running.
     */
    private class ProjectionGrant(val resultCode: Int, val data: Intent)

    @Volatile private var grant: ProjectionGrant? = null
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    private val handlerThread = HandlerThread("ScreenRecorder").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /**
     * True only while a grant is held that [record] can still use. Grants are
     * dropped once consumed on Android 14+ (see [record]) and whenever the
     * platform rejects one, so this does not report a stale token as usable.
     */
    fun hasPermission(): Boolean = grant != null

    @Synchronized
    fun setProjectionPermission(resultCode: Int, data: Intent) {
        grant = ProjectionGrant(resultCode, Intent(data))
    }

    /** Clears [used] if it is still the current grant; a newer grant is kept. */
    @Synchronized
    private fun clearProjectionPermission(used: ProjectionGrant) {
        if (grant === used) grant = null
    }

    /**
     * Record the screen for [durationMs] milliseconds.
     * CRITICAL: Entire recording runs on the HandlerThread via handler.post().
     * MediaRecorder.start()/stop() and Thread.sleep() MUST be on the same thread
     * that created the VirtualDisplay callback handler — NOT on Dispatchers.IO.
     */
    fun record(durationMs: Long = 5000): Map<String, Any?> {
        val safeDuration = durationMs.coerceAtMost(MAX_DURATION_MS)
        val service = BridgeAccessibilityService.instance
            ?: return mapOf("success" to false, "message" to "Accessibility service not running")
        val usedGrant = grant
            ?: return mapOf("success" to false, "message" to NO_PERMISSION_MESSAGE)
        val resultCode = usedGrant.resultCode
        val resultData = usedGrant.data

        val latch = java.util.concurrent.CountDownLatch(1)
        val resultHolder = arrayOf<Map<String, Any?>?>(null)

        handler.post {
            var outputFile: File? = null
            var projection: MediaProjection? = null
            var projectionCallback: MediaProjection.Callback? = null
            try {
                service.startForeground(includeMediaProjection = true)
                val mpm = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(resultCode, resultData)
                    ?: throw IllegalStateException("MediaProjection permission token was not accepted")
                projectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        cleanupRecording()
                    }
                }
                projection.registerCallback(projectionCallback, handler)

                outputFile = File(service.cacheDir, "screen_record_${System.currentTimeMillis()}.mp4")
                val metrics = service.resources.displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                val mr = MediaRecorder(service).apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(outputFile.absolutePath)
                    setVideoSize(width, height)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoEncodingBitRate(2_000_000)
                    setVideoFrameRate(30)
                    prepare()
                }
                recorder = mr

                val vd = projection.createVirtualDisplay(
                    "ScreenRecorder", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mr.surface, null, handler
                )
                virtualDisplay = vd

                mr.start()

                // Safe to block here — we're on the dedicated HandlerThread
                Thread.sleep(safeDuration)

                mr.stop()
                mr.release()
                vd.release()
                recorder = null
                virtualDisplay = null

                val bytes = outputFile.readBytes()
                val base64Video = Base64.encodeToString(bytes, Base64.NO_WRAP)
                outputFile.delete()

                resultHolder[0] = mapOf(
                    "success" to true,
                    "message" to "Recorded ${safeDuration}ms",
                    "data" to mapOf(
                        "video" to base64Video,
                        "width" to width,
                        "height" to height,
                        "durationMs" to safeDuration,
                        "sizeBytes" to bytes.size,
                        "mimeType" to "video/mp4"
                    )
                )
            } catch (e: Exception) {
                cleanupRecording()
                outputFile?.delete()
                if (e is SecurityException || e is IllegalStateException) {
                    // The platform rejected the token itself — it is dead on
                    // every Android version.
                    clearProjectionPermission(usedGrant)
                }
                resultHolder[0] = mapOf("success" to false, "message" to "Recording failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try {
                    if (projectionCallback != null) projection?.unregisterCallback(projectionCallback)
                } catch (_: Exception) {}
                try { projection?.stop() } catch (_: Exception) {}
                // Android 14+ makes the consent token single-use: once
                // getMediaProjection() has handed out a projection, calling it
                // again with the same Intent throws SecurityException. So drop
                // the grant after it has actually been consumed — whether the
                // recording then succeeded or failed — and let hasPermission()
                // report that a fresh grant is needed.
                //
                // Below 14 the token is reusable across recordings. deae1fc
                // removed an unconditional clear here precisely so users on
                // those versions don't have to re-grant before every capture;
                // this keeps that behaviour and only clears where reuse is
                // impossible anyway.
                if (projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    clearProjectionPermission(usedGrant)
                }
                latch.countDown()
            }
        }

        // Wait for the handler thread to finish (with generous timeout)
        latch.await(safeDuration + 10000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return resultHolder[0] ?: mapOf("success" to false, "message" to "Recording timed out")
    }

    private fun cleanupRecording() {
        try { recorder?.release() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        recorder = null
        virtualDisplay = null
    }

    fun release() {
        cleanupRecording()
        handlerThread.quitSafely()
    }
}
