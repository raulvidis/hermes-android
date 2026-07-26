package com.hermesandroid.bridge.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import java.io.File

/**
 * Background microphone capture via MediaRecorder.
 * Requires RECORD_AUDIO runtime permission + foreground service (microphone type).
 * Android shows a status-bar mic indicator while recording — cannot be hidden.
 */
object MicRecorder {
    private const val MAX_DURATION_MS = 120_000L

    private val handlerThread = HandlerThread("MicRecorder").apply { start() }
    private val handler = Handler(handlerThread.looper)

    @Volatile private var recorder: MediaRecorder? = null

    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun record(durationMs: Long = 5000): Map<String, Any?> {
        val safeDuration = durationMs.coerceIn(500L, MAX_DURATION_MS)
        val service = BridgeAccessibilityService.instance
            ?: return mapOf("success" to false, "message" to "Accessibility service not running")
        if (!hasPermission(service)) {
            return mapOf(
                "success" to false,
                "message" to "RECORD_AUDIO permission not granted. Enable Microphone in Hermes Bridge permissions.",
            )
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        val resultHolder = arrayOf<Map<String, Any?>?>(null)

        handler.post {
            var outputFile: File? = null
            try {
                service.startForeground(includeMicrophone = true)
                outputFile = File(service.cacheDir, "mic_record_${System.currentTimeMillis()}.m4a")

                val mr = createRecorder(service).apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                }
                recorder = mr
                mr.start()
                Thread.sleep(safeDuration)
                try {
                    mr.stop()
                } catch (_: RuntimeException) {
                    // stop can throw if no data was captured
                }
                mr.release()
                recorder = null

                val bytes = outputFile.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                outputFile.delete()

                resultHolder[0] = mapOf(
                    "success" to true,
                    "message" to "Recorded mic ${safeDuration}ms",
                    "data" to mapOf(
                        "audio" to b64,
                        "durationMs" to safeDuration,
                        "sizeBytes" to bytes.size,
                        "mimeType" to "audio/mp4",
                        "extension" to "m4a",
                    ),
                )
            } catch (e: Exception) {
                cleanup()
                outputFile?.delete()
                resultHolder[0] = mapOf(
                    "success" to false,
                    "message" to "Mic recording failed: ${e.javaClass.simpleName}: ${e.message}",
                )
            } finally {
                latch.countDown()
            }
        }

        latch.await(safeDuration + 15_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        return resultHolder[0] ?: mapOf("success" to false, "message" to "Mic recording timed out")
    }

    private fun createRecorder(context: Context): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun cleanup() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
    }
}
