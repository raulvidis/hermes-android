package com.hermesandroid.bridge.media

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import java.io.File

object ScreenRecorder {
    private const val NO_PERMISSION_MESSAGE =
        "No MediaProjection. Grant Screen Recording in the app before each capture on Android 16."

    private var projectionResultCode: Int? = null
    private var projectionData: Intent? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handlerThread = HandlerThread("ScreenRecorder").apply { start() }
    private val handler = Handler(handlerThread.looper)

    fun hasPermission(): Boolean = projectionData != null

    fun setProjectionPermission(resultCode: Int, data: Intent) {
        projectionResultCode = resultCode
        projectionData = Intent(data)
    }

    private fun clearProjectionPermission() {
        projectionResultCode = null
        projectionData = null
    }

    /**
     * Record the screen for [durationMs] milliseconds.
     * CRITICAL: Entire recording runs on the HandlerThread via handler.post().
     * MediaRecorder.start()/stop() and Thread.sleep() MUST be on the same thread
     * that created the VirtualDisplay callback handler — NOT on Dispatchers.IO.
     */
    fun record(durationMs: Long = 5000): Map<String, Any?> {
        val service = BridgeAccessibilityService.instance
            ?: return mapOf("success" to false, "message" to "Accessibility service not running")
        val resultCode = projectionResultCode
            ?: return mapOf("success" to false, "message" to NO_PERMISSION_MESSAGE)
        val resultData = projectionData
            ?: return mapOf("success" to false, "message" to NO_PERMISSION_MESSAGE)

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
                Thread.sleep(durationMs)

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
                    "message" to "Recorded ${durationMs}ms",
                    "data" to mapOf(
                        "video" to base64Video,
                        "width" to width,
                        "height" to height,
                        "durationMs" to durationMs,
                        "sizeBytes" to bytes.size,
                        "mimeType" to "video/mp4"
                    )
                )
            } catch (e: Exception) {
                cleanupRecording()
                outputFile?.delete()
                if (e is SecurityException || e is IllegalStateException) {
                    clearProjectionPermission()
                }
                resultHolder[0] = mapOf("success" to false, "message" to "Recording failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try {
                    if (projectionCallback != null) projection?.unregisterCallback(projectionCallback)
                } catch (_: Exception) {}
                try { projection?.stop() } catch (_: Exception) {}
                if (projection != null) clearProjectionPermission()
                latch.countDown()
            }
        }

        // Wait for the handler thread to finish (with generous timeout)
        latch.await(durationMs + 10000, java.util.concurrent.TimeUnit.MILLISECONDS)
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
