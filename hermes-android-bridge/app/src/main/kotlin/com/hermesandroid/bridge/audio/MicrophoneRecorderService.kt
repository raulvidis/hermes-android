package com.hermesandroid.bridge.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Records PCM16 microphone audio and publishes completed WAV files atomically. */
class MicrophoneRecorderService : Service() {

    companion object {
        internal const val MAX_DURATION_SECONDS = 30 * 60
        private const val CHANNEL_ID = "hermes_bridge_mic"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16_000
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_DURATION = "duration"

        fun start(context: Context, durationSec: Int = 0) {
            require(durationSec in 0..MAX_DURATION_SECONDS) {
                "duration must be between 0 and $MAX_DURATION_SECONDS seconds"
            }
            startServiceCommand(context, ACTION_START, durationSec)
        }

        fun stop(context: Context) {
            startServiceCommand(context, ACTION_STOP, 0)
        }

        private fun startServiceCommand(context: Context, actionName: String, durationSec: Int) {
            val intent = Intent(context, MicrophoneRecorderService::class.java).apply {
                action = actionName
                putExtra(EXTRA_DURATION, durationSec)
            }
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRecording = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var recorder: AudioRecord? = null
    private var recordingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing microphone…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> requestStop()
            else -> startRecording(intent?.getIntExtra(EXTRA_DURATION, 0) ?: 0)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(durationSec: Int) {
        if (!isRecording.compareAndSet(false, true)) return
        stopRequested.set(false)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failBeforeRecording("Microphone permission is not granted")
            return
        }
        if (durationSec !in 0..MAX_DURATION_SECONDS) {
            failBeforeRecording("Requested duration is outside the supported range")
            return
        }

        val pending = runCatching { MicrophoneRecordingFiles.createPending(this) }
            .getOrElse { error ->
                failBeforeRecording(safeError("Could not prepare recording", error))
                return
            }
        MicrophoneRecordingState.markStarting(pending.completedFile.name)

        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            failBeforeRecording("No supported microphone buffer is available")
            return
        }

        val audioRecord = runCatching {
            AudioRecord(
                // VOICE_RECOGNITION enables the platform's speech-oriented
                // preprocessing. On-device tests on a Pixel 6 produced usable
                // levels here where the raw MIC source was effectively silent.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimumBuffer * 4,
            )
        }.getOrElse { error ->
            failBeforeRecording(safeError("Could not initialize microphone", error))
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            failBeforeRecording("Microphone is not available")
            return
        }

        recorder = audioRecord
        recordingJob = scope.launch {
            var writer: WavFileWriter? = null
            try {
                writer = WavFileWriter(pending, SAMPLE_RATE)
                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("Microphone did not enter recording state")
                }

                MicrophoneRecordingState.markRecording()
                updateNotification("Recording…")

                val samples = ShortArray((minimumBuffer / 2).coerceAtLeast(1024))
                // A missing stop command must not fill a permanently powered
                // device. duration=0 still means "until stopped", but only up
                // to the same documented Phase-1 safety ceiling.
                val effectiveDuration = if (durationSec == 0) MAX_DURATION_SECONDS else durationSec
                val sampleLimit = SAMPLE_RATE.toLong() * effectiveDuration.toLong()

                while (isActive && isRecording.get() && writer.totalSamples < sampleLimit) {
                    val requested = minOf(samples.size.toLong(), sampleLimit - writer.totalSamples).toInt()
                    val read = audioRecord.read(
                        samples,
                        0,
                        requested,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (read > 0) {
                        PcmSampleProcessor.applyRecordingGain(samples, read)
                        writer.write(samples, read)
                        MicrophoneRecordingState.markRecording(writer.totalSamples * 2L)
                    } else if (!stopRequested.get()) {
                        throw IOException("AudioRecord read failed with code $read")
                    }
                }

                MicrophoneRecordingState.markFinalizing(writer.totalSamples * 2L)
                updateNotification("Finalizing recording…")
                safelyStop(audioRecord)
                val completed = writer.finish()
                MicrophoneRecordingFiles.enforceRetention(this@MicrophoneRecorderService)
                MicrophoneRecordingState.markReady(completed, writer.totalSamples * 2L)
                updateNotification("Recording ready")
            } catch (cancelled: CancellationException) {
                finalizeInterruptedWriter(writer)
                throw cancelled
            } catch (error: Throwable) {
                val recovered = finalizeInterruptedWriter(writer)
                val message = safeError("Microphone recording failed", error)
                MicrophoneRecordingState.markError(message)
                updateNotification(if (recovered) "Recording stopped with an error" else message)
            } finally {
                safelyStop(audioRecord)
                runCatching { audioRecord.release() }
                if (recorder === audioRecord) recorder = null
                writer?.close()
                isRecording.set(false)
                stopRequested.set(false)
                recordingJob = null
                stopForegroundCompat()
                // A STOP command has a newer startId than the original START.
                // stopSelfResult(startId) would therefore leave this service
                // alive after finalization.
                stopSelf()
            }
        }
    }

    private fun finalizeInterruptedWriter(writer: WavFileWriter?): Boolean {
        if (writer == null || writer.totalSamples == 0L) {
            writer?.abort()
            return false
        }
        return runCatching {
            MicrophoneRecordingState.markFinalizing(writer.totalSamples * 2L)
            val completed = writer.finish()
            MicrophoneRecordingFiles.enforceRetention(this)
            MicrophoneRecordingState.markReady(completed, writer.totalSamples * 2L)
        }.isSuccess
    }

    private fun requestStop() {
        if (!isRecording.get()) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        stopRequested.set(true)
        isRecording.set(false)
        MicrophoneRecordingState.markFinalizing(
            MicrophoneRecordingState.snapshot().bytesWritten,
        )
        safelyStop(recorder)
    }

    private fun failBeforeRecording(message: String) {
        isRecording.set(false)
        stopRequested.set(false)
        MicrophoneRecordingState.markError(message)
        updateNotification(message)
        stopForegroundCompat()
        stopSelf()
    }

    private fun safelyStop(audioRecord: AudioRecord?) {
        if (audioRecord == null) return
        runCatching {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hermes Bridge microphone",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Visible indicator while Hermes Bridge records microphone audio"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Bridge microphone")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        // Updating the existing foreground-service notification this way does
        // not require the Android 13 POST_NOTIFICATIONS runtime permission.
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun safeError(prefix: String, error: Throwable): String =
        "$prefix (${error.javaClass.simpleName})"

    override fun onDestroy() {
        stopRequested.set(true)
        isRecording.set(false)
        safelyStop(recorder)
        recordingJob?.cancel()
        if (recordingJob == null) {
            runCatching { recorder?.release() }
            recorder = null
        }
        scope.cancel()
        super.onDestroy()
    }
}
