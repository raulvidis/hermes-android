package com.hermesandroid.bridge.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Small Camera2/MediaRecorder adapter used by the foreground noise watcher. */
internal class CameraVideoRecorder(private val context: Context) {
    private val thread = HandlerThread("CradataNoiseCamera").apply { start() }
    private val handler = Handler(thread.looper)
    // Camera callbacks must not share the handler that waits for them.
    private val callbackThread = HandlerThread("CradataNoiseCameraCallbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    @SuppressLint("MissingPermission")
    fun record(outputFile: File, durationSeconds: Int): Result<Unit> {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(SecurityException("Camera permission is not granted"))
        }
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return Result.failure(IllegalStateException("Camera service is unavailable"))
        val cameraId = chooseBackCamera(manager)
            ?: return Result.failure(IllegalStateException("Back camera is unavailable"))

        val result = AtomicReference<Result<Unit>>(
            Result.failure(IllegalStateException("Camera did not start")),
        )
        val done = CountDownLatch(1)
        handler.post {
            val recorder = MediaRecorder()
            var camera: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var recordingStarted = false
            try {
                val size = chooseVideoSize(manager.getCameraCharacteristics(cameraId))
                outputFile.parentFile?.mkdirs()
                recorder.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(outputFile.absolutePath)
                    setVideoSize(size.width, size.height)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoEncodingBitRate(2_000_000)
                    setVideoFrameRate(30)
                    prepare()
                }
                val openLatch = CountDownLatch(1)
                var openError: Throwable? = null
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        openLatch.countDown()
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        openError = IllegalStateException("Camera disconnected")
                        openLatch.countDown()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        openError = IllegalStateException("Camera error $error")
                        openLatch.countDown()
                    }
                }, callbackHandler)
                if (!openLatch.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Camera open timed out")
                }
                openError?.let { throw it }
                val opened = camera ?: throw IllegalStateException("Camera did not open")
                val sessionLatch = CountDownLatch(1)
                var sessionError: Throwable? = null
                opened.createCaptureSession(
                    listOf(recorder.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            session = value
                            sessionLatch.countDown()
                        }

                        override fun onConfigureFailed(value: CameraCaptureSession) {
                            sessionError = IllegalStateException("Camera capture session failed")
                            sessionLatch.countDown()
                        }
                    },
                    callbackHandler,
                )
                if (!sessionLatch.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Camera session timed out")
                }
                sessionError?.let { throw it }
                val configured = session ?: throw IllegalStateException("Camera session unavailable")
                val request = opened.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(recorder.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                configured.setRepeatingRequest(request, null, handler)
                recorder.start()
                recordingStarted = true
                Thread.sleep(durationSeconds.coerceIn(1, 30) * 1_000L)
                recorder.stop()
                result.set(Result.success(Unit))
            } catch (error: Throwable) {
                if (recordingStarted) runCatching { recorder.stop() }
                outputFile.delete()
                result.set(Result.failure(error))
            } finally {
                runCatching { session?.stopRepeating() }
                runCatching { session?.close() }
                runCatching { camera?.close() }
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
                done.countDown()
            }
        }
        if (!done.await(durationSeconds.coerceIn(1, 30) * 1_000L + 12_000L, TimeUnit.MILLISECONDS)) {
            return Result.failure(IllegalStateException("Camera recording timed out"))
        }
        return result.get()
    }

    fun close() {
        thread.quitSafely()
        callbackThread.quitSafely()
    }

    private fun chooseBackCamera(manager: CameraManager): String? =
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }

    private fun chooseVideoSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        )
        val candidates = map?.getOutputSizes(MediaRecorder::class.java).orEmpty()
        return candidates
            .filter { it.width <= 1280 && it.height <= 720 }
            .minByOrNull { kotlin.math.abs(it.width * it.height - 1280 * 720) }
            ?: candidates.firstOrNull()
            ?: Size(1280, 720)
    }
}
