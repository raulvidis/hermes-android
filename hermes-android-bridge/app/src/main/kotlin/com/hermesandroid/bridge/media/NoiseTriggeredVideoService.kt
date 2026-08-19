package com.hermesandroid.bridge.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.hermesandroid.bridge.audio.MicrophoneRecordingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

/**
 * Explicitly enabled ambient-noise watcher. It records only short video clips
 * after a loud RMS threshold is exceeded, with a cooldown and bounded storage.
 */
class NoiseTriggeredVideoService : Service() {
    companion object {
        const val DEFAULT_THRESHOLD_RMS = 1_800.0
        const val DEFAULT_CLIP_SECONDS = 10
        const val DEFAULT_COOLDOWN_SECONDS = 60
        const val MAX_CLIP_SECONDS = 30
        const val MAX_COOLDOWN_SECONDS = 3_600
        const val MIN_THRESHOLD_RMS = 300.0
        const val MAX_THRESHOLD_RMS = 30_000.0

        private const val ACTION_START = "com.hermesandroid.bridge.noise.START"
        private const val ACTION_STOP = "com.hermesandroid.bridge.noise.STOP"
        private const val ACTION_PAUSE_FOR_DIALOG =
            "com.hermesandroid.bridge.noise.PAUSE_FOR_DIALOG"
        private const val ACTION_RESUME_AFTER_DIALOG =
            "com.hermesandroid.bridge.noise.RESUME_AFTER_DIALOG"
        private const val EXTRA_THRESHOLD = "threshold_rms"
        private const val EXTRA_CLIP_SECONDS = "clip_seconds"
        private const val EXTRA_COOLDOWN_SECONDS = "cooldown_seconds"
        private const val CHANNEL_ID = "hermes_bridge_noise_video"
        private const val NOTIFICATION_ID = 1002
        private const val SAMPLE_RATE = 16_000
        private const val MIN_LOUD_MS = 120

        fun start(
            context: Context,
            thresholdRms: Double = DEFAULT_THRESHOLD_RMS,
            clipSeconds: Int = DEFAULT_CLIP_SECONDS,
            cooldownSeconds: Int = DEFAULT_COOLDOWN_SECONDS,
        ) {
            require(thresholdRms in MIN_THRESHOLD_RMS..MAX_THRESHOLD_RMS)
            require(clipSeconds in 1..MAX_CLIP_SECONDS)
            require(cooldownSeconds in 0..MAX_COOLDOWN_SECONDS)
            val intent = Intent(context, NoiseTriggeredVideoService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_THRESHOLD, thresholdRms)
                putExtra(EXTRA_CLIP_SECONDS, clipSeconds)
                putExtra(EXTRA_COOLDOWN_SECONDS, cooldownSeconds)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NoiseTriggeredVideoService::class.java).apply {
                    action = ACTION_STOP
                },
            )
        }

        /** Release the microphone while keeping the user's watcher preference active. */
        fun pauseForRobotDialog(context: Context) {
            if (!NoiseTriggerState.snapshot().active) return
            context.startService(
                Intent(context, NoiseTriggeredVideoService::class.java).apply {
                    action = ACTION_PAUSE_FOR_DIALOG
                },
            )
        }

        /** Resume listening after the robot microphone has been released. */
        fun resumeAfterRobotDialog(context: Context) {
            val state = NoiseTriggerState.snapshot()
            if (!state.active || (!state.pausingForDialog && !state.pausedForDialog)) return
            context.startService(
                Intent(context, NoiseTriggeredVideoService::class.java).apply {
                    action = ACTION_RESUME_AFTER_DIALOG
                },
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var resumeJob: Job? = null
    private var cameraRecorder: CameraVideoRecorder? = null
    private var thresholdRms = DEFAULT_THRESHOLD_RMS
    private var clipSeconds = DEFAULT_CLIP_SECONDS
    private var cooldownSeconds = DEFAULT_COOLDOWN_SECONDS
    private var lastTriggerElapsedMs = Long.MIN_VALUE

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat("Loud-noise video watcher is ready")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopWatcher()
            ACTION_PAUSE_FOR_DIALOG -> pauseWatcherForDialog()
            ACTION_RESUME_AFTER_DIALOG -> resumeWatcherAfterDialog()
            ACTION_START, null -> startWatcher(
                intent?.getDoubleExtra(EXTRA_THRESHOLD, DEFAULT_THRESHOLD_RMS)
                    ?: DEFAULT_THRESHOLD_RMS,
                intent?.getIntExtra(EXTRA_CLIP_SECONDS, DEFAULT_CLIP_SECONDS)
                    ?: DEFAULT_CLIP_SECONDS,
                intent?.getIntExtra(EXTRA_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS)
                    ?: DEFAULT_COOLDOWN_SECONDS,
            )
        }
        return START_NOT_STICKY
    }

    private fun startWatcher(threshold: Double, clip: Int, cooldown: Int) {
        resumeJob?.cancel()
        resumeJob = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            NoiseTriggerState.failed("Microphone and camera permissions are required")
            stopSelf()
            return
        }
        if (MicrophoneRecordingState.snapshot().isActive) {
            NoiseTriggerState.failed("The microphone is already in use")
            stopSelf()
            return
        }
        thresholdRms = threshold.coerceIn(MIN_THRESHOLD_RMS, MAX_THRESHOLD_RMS)
        clipSeconds = clip.coerceIn(1, MAX_CLIP_SECONDS)
        cooldownSeconds = cooldown.coerceIn(0, MAX_COOLDOWN_SECONDS)
        if (monitorJob?.isActive == true) return
        NoiseTriggerState.started(thresholdRms, clipSeconds, cooldownSeconds)
        cameraRecorder = CameraVideoRecorder(this)
        monitorJob = scope.launch {
            monitorMicrophone()
        }
        updateNotification("Listening for loud sounds · ${clipSeconds}s clips")
    }

    private fun pauseWatcherForDialog() {
        if (!NoiseTriggerState.snapshot().active) return
        resumeJob?.cancel()
        resumeJob = null
        NoiseTriggerState.pauseRequestedForDialog()
        val activeMonitor = monitorJob
        monitorJob = null
        scope.launch {
            activeMonitor?.cancelAndJoin()
            if (NoiseTriggerState.snapshot().active) {
                NoiseTriggerState.pausedForDialog()
                updateNotification("Paused while the robot dialog uses the microphone")
            }
        }
    }

    private fun resumeWatcherAfterDialog() {
        resumeJob?.cancel()
        resumeJob = scope.launch {
            while (NoiseTriggerState.snapshot().pausingForDialog) {
                delay(100)
            }
            while (
                NoiseTriggerState.snapshot().active &&
                NoiseTriggerState.snapshot().pausedForDialog &&
                MicrophoneRecordingState.snapshot().isActive
            ) {
                delay(250)
            }
            val state = NoiseTriggerState.snapshot()
            if (!state.active || !state.pausedForDialog || monitorJob?.isActive == true) {
                return@launch
            }
            NoiseTriggerState.resumedAfterDialog()
            monitorJob = scope.launch { monitorMicrophone() }
            updateNotification("Listening for loud sounds · ${clipSeconds}s clips")
        }
    }

    private suspend fun monitorMicrophone() {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            NoiseTriggerState.failed("Microphone buffer is unavailable")
            return
        }
        val audio = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimumBuffer * 4,
            )
        }.getOrElse {
            NoiseTriggerState.failed("Could not initialize microphone")
            return
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            NoiseTriggerState.failed("Microphone is not available")
            return
        }

        val samples = ShortArray((minimumBuffer / 2).coerceAtLeast(1024))
        var loudSamples = 0L
        try {
            audio.startRecording()
            while (currentCoroutineContext().isActive && monitorJob?.isActive != false) {
                val read = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                val rms = rootMeanSquare(samples, read)
                if (rms >= thresholdRms) {
                    loudSamples += read
                } else {
                    loudSamples = 0L
                }
                if (loudSamples >= SAMPLE_RATE * MIN_LOUD_MS / 1_000L) {
                    loudSamples = 0L
                    maybeRecordClip()
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: Throwable) {
            NoiseTriggerState.failed("Noise watcher stopped unexpectedly")
        } finally {
            runCatching {
                if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
            }
            audio.release()
        }
    }

    private suspend fun maybeRecordClip() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerElapsedMs < cooldownSeconds * 1_000L) return
        if (NoiseTriggerState.snapshot().recording) return
        lastTriggerElapsedMs = now
        NoiseTriggerState.triggered()
        updateNotification("Loud sound detected · recording ${clipSeconds}s video")
        val pending = runCatching { NoiseVideoFiles.createPending(this) }.getOrElse {
            NoiseTriggerState.failed("Could not prepare video storage")
            return
        }
        val result = cameraRecorder?.record(pending.pendingFile, clipSeconds)
        if (result?.isSuccess == true && pending.pendingFile.isFile && pending.pendingFile.length() > 0) {
            runCatching {
                pending.pendingFile.renameTo(pending.completedFile).also { renamed ->
                    if (!renamed) throw IllegalStateException("Could not finalize video")
                }
                NoiseVideoFiles.enforceRetention(this)
                NoiseTriggerState.completed(
                    pending.completedFile,
                    NoiseVideoFiles.listCompleted(this).size,
                )
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(pending.completedFile.absolutePath),
                    arrayOf("video/mp4"),
                    null,
                )
            }.onFailure {
                pending.pendingFile.delete()
                NoiseTriggerState.failed("Could not finalize video")
            }
        } else {
            pending.pendingFile.delete()
            NoiseTriggerState.failed(
                result?.exceptionOrNull()?.message ?: "Camera recording failed",
            )
        }
        updateNotification("Listening for loud sounds · ${clipSeconds}s clips")
    }

    private fun stopWatcher() {
        resumeJob?.cancel()
        resumeJob = null
        monitorJob?.cancel()
        monitorJob = null
        cameraRecorder?.close()
        cameraRecorder = null
        NoiseTriggerState.stopped()
        stopForegroundCompat()
        stopSelf()
    }

    private fun rootMeanSquare(samples: ShortArray, count: Int): Double {
        var sumSquares = 0.0
        for (index in 0 until count) {
            val sample = samples[index].toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / count)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Cradata loud-noise video watcher",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Visible indicator while the opt-in noise video watcher is active"
            },
        )
    }

    private fun startForegroundCompat(text: String) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Cradata noise watcher")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        startForegroundCompat(text)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        resumeJob?.cancel()
        monitorJob?.cancel()
        cameraRecorder?.close()
        scope.cancel()
        NoiseTriggerState.stopped()
        super.onDestroy()
    }
}
