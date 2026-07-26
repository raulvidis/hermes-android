package com.hermesandroid.bridge.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Size
import android.view.Surface
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Background camera video capture (Camera2 + MediaRecorder).
 *
 * Requirements:
 * - CAMERA (+ RECORD_AUDIO if withAudio)
 * - Accessibility service running (for FGS host)
 * - Foreground service type camera|microphone
 *
 * Android will show a green camera indicator in the status bar. Silent
 * unrestricted camera access without FGS/indicator is not available on modern Android.
 */
object CameraRecorder {
    private const val MAX_DURATION_MS = 60_000L

    // Recording orchestration blocks while it waits for Camera2 callbacks. Those
    // callbacks MUST therefore run on a different looper, or Samsung will wait
    // forever and openCamera() appears to "time out".
    private val handlerThread = HandlerThread("CameraRecorderWorker").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val callbackThread = HandlerThread("CameraRecorderCallbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    fun hasCameraPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasMicPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun record(
        durationMs: Long = 5000,
        camera: String = "back",
        withAudio: Boolean = true,
    ): Map<String, Any?> {
        val safeDuration = durationMs.coerceIn(500L, MAX_DURATION_MS)
        val service = BridgeAccessibilityService.instance
            ?: return mapOf("success" to false, "message" to "Accessibility service not running")
        if (!hasCameraPermission(service)) {
            return mapOf(
                "success" to false,
                "message" to "CAMERA permission not granted. Enable Camera in Hermes Bridge permissions.",
            )
        }
        val audio = withAudio && hasMicPermission(service)

        val latch = CountDownLatch(1)
        val resultHolder = arrayOf<Map<String, Any?>?>(null)

        handler.post {
            var outputFile: File? = null
            var cameraDevice: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var recorder: MediaRecorder? = null
            var dummyTexture: SurfaceTexture? = null
            try {
                service.startForeground(
                    includeCamera = true,
                    includeMicrophone = audio,
                )

                val cameraManager = service.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = pickCameraId(cameraManager, camera)
                    ?: throw IllegalStateException("No matching camera for '$camera'")

                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw IllegalStateException("Camera has no stream config")
                val videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))

                outputFile = File(service.cacheDir, "camera_record_${System.currentTimeMillis()}.mp4")
                val mr = createRecorder(service)
                recorder = mr
                configureRecorder(mr, service, outputFile, videoSize, audio, camera)
                mr.prepare()

                val recorderSurface = mr.surface
                // Camera2 often wants a preview surface too; use a tiny SurfaceTexture.
                dummyTexture = SurfaceTexture(0).apply {
                    setDefaultBufferSize(videoSize.width, videoSize.height)
                }
                val previewSurface = Surface(dummyTexture)

                val openLatch = CountDownLatch(1)
                val deviceRef = AtomicReference<CameraDevice?>()
                val openError = AtomicReference<String?>()

                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            deviceRef.set(camera)
                            openLatch.countDown()
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            openError.compareAndSet(null, "Camera disconnected")
                            openLatch.countDown()
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            openError.compareAndSet(null, "Camera open error $error")
                            openLatch.countDown()
                        }
                    },
                    callbackHandler,
                )

                if (!openLatch.await(8, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Camera open timed out")
                }
                openError.get()?.let { throw IllegalStateException(it) }
                cameraDevice = deviceRef.get() ?: throw IllegalStateException("Camera device null")

                val sessionLatch = CountDownLatch(1)
                val sessionRef = AtomicReference<CameraCaptureSession?>()
                val sessionError = AtomicReference<String?>()

                cameraDevice.createCaptureSession(
                    listOf(recorderSurface, previewSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            sessionRef.set(s)
                            sessionLatch.countDown()
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            sessionError.set("Camera session configure failed")
                            sessionLatch.countDown()
                        }
                    },
                    callbackHandler,
                )

                if (!sessionLatch.await(8, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Camera session timed out")
                }
                sessionError.get()?.let { throw IllegalStateException(it) }
                session = sessionRef.get() ?: throw IllegalStateException("Session null")

                val request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(recorderSurface)
                    addTarget(previewSurface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                session.setRepeatingRequest(request, null, callbackHandler)

                mr.start()
                Thread.sleep(safeDuration)

                try {
                    mr.stop()
                } catch (_: RuntimeException) {
                }
                try {
                    session.stopRepeating()
                } catch (_: Exception) {
                }
                session.close()
                session = null
                cameraDevice.close()
                cameraDevice = null
                mr.release()
                recorder = null
                previewSurface.release()
                dummyTexture?.release()
                dummyTexture = null

                val bytes = outputFile.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                outputFile.delete()

                resultHolder[0] = mapOf(
                    "success" to true,
                    "message" to "Recorded camera ${safeDuration}ms (${camera}, audio=$audio)",
                    "data" to mapOf(
                        "video" to b64,
                        "width" to videoSize.width,
                        "height" to videoSize.height,
                        "durationMs" to safeDuration,
                        "sizeBytes" to bytes.size,
                        "mimeType" to "video/mp4",
                        "camera" to camera,
                        "withAudio" to audio,
                    ),
                )
            } catch (e: SecurityException) {
                cleanup(cameraDevice, session, recorder, dummyTexture)
                outputFile?.delete()
                resultHolder[0] = mapOf(
                    "success" to false,
                    "message" to "Camera security error: ${e.message}",
                )
            } catch (e: Exception) {
                cleanup(cameraDevice, session, recorder, dummyTexture)
                outputFile?.delete()
                resultHolder[0] = mapOf(
                    "success" to false,
                    "message" to "Camera recording failed: ${e.javaClass.simpleName}: ${e.message}",
                )
            } finally {
                latch.countDown()
            }
        }

        latch.await(safeDuration + 20_000L, TimeUnit.MILLISECONDS)
        return resultHolder[0] ?: mapOf("success" to false, "message" to "Camera recording timed out")
    }

    private fun pickCameraId(manager: CameraManager, preferred: String): String? {
        val wantFront = preferred.equals("front", ignoreCase = true) ||
            preferred.equals("selfie", ignoreCase = true)
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (fallback == null) fallback = id
            if (wantFront && facing == CameraCharacteristics.LENS_FACING_FRONT) return id
            if (!wantFront && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return fallback
    }

    private fun chooseVideoSize(choices: Array<Size>?): Size {
        if (choices.isNullOrEmpty()) return Size(1280, 720)
        // Prefer 720p-ish, else largest under 1080p
        val preferred = choices.firstOrNull { it.width == 1280 && it.height == 720 }
        if (preferred != null) return preferred
        return choices
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: choices.minBy { it.width.toLong() * it.height.toLong() }
    }

    private fun createRecorder(context: Context): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun configureRecorder(
        mr: MediaRecorder,
        context: Context,
        outputFile: File,
        size: Size,
        withAudio: Boolean,
        camera: String,
    ) {
        if (withAudio) {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setOutputFile(outputFile.absolutePath)
        mr.setVideoEncodingBitRate(3_000_000)
        mr.setVideoFrameRate(30)
        mr.setVideoSize(size.width, size.height)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (withAudio) {
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128_000)
            mr.setAudioSamplingRate(44_100)
        }
        // Samsung front and rear sensors have opposite portrait orientation.
        // A fixed 90° works for rear but flips front-camera video upside down.
        val metrics = context.resources.displayMetrics
        if (metrics.heightPixels > metrics.widthPixels) {
            val front = camera.equals("front", ignoreCase = true) ||
                camera.equals("selfie", ignoreCase = true)
            mr.setOrientationHint(if (front) 270 else 90)
        }
    }

    private fun cleanup(
        cameraDevice: CameraDevice?,
        session: CameraCaptureSession?,
        recorder: MediaRecorder?,
        texture: SurfaceTexture?,
    ) {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        try {
            texture?.release()
        } catch (_: Exception) {
        }
    }
}
