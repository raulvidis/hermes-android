package com.hermesandroid.bridge.media

import android.content.Context
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
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handlerThread = HandlerThread("ScreenRecorder").apply { start() }
    private val handler = Handler(handlerThread.looper)

    fun hasPermission(): Boolean = projection != null

    fun setProjection(p: MediaProjection) {
        projection?.stop()
        projection = p
    }

    fun record(durationMs: Long = 5000): Map<String, Any?> {
        val service = BridgeAccessibilityService.instance
            ?: return mapOf("success" to false, "message" to "Accessibility service not running")
        val proj = projection
            ?: return mapOf("success" to false, "message" to "No MediaProjection. Tap 'Grant Screen Recording' in the app first.")

        return try {
            val outputFile = File(service.cacheDir, "screen_record_${System.currentTimeMillis()}.mp4")
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

            val vd = proj.createVirtualDisplay(
                "ScreenRecorder", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mr.surface, null
            )
            virtualDisplay = vd

            mr.start()

            // Block the handler thread for the recording duration
            Thread.sleep(durationMs)

            mr.stop()
            mr.release()
            vd.release()
            recorder = null
            virtualDisplay = null

            val bytes = outputFile.readBytes()
            val base64Video = Base64.encodeToString(bytes, Base64.NO_WRAP)
            outputFile.delete()

            mapOf(
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
            try { recorder?.release() } catch (_: Exception) {}
            try { virtualDisplay?.release() } catch (_: Exception) {}
            recorder = null
            virtualDisplay = null
            mapOf("success" to false, "message" to "Recording failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
