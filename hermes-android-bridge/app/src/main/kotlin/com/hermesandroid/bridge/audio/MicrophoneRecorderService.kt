package com.hermesandroid.bridge.audio

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Records PCM16 microphone audio and publishes completed WAV files atomically. */
class MicrophoneRecorderService : Service() {

    companion object {
        internal const val MAX_DURATION_SECONDS = 30 * 60
        private const val TAG = "MicRecorderService"
        private const val CHANNEL_ID = "hermes_bridge_mic"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16_000

        internal const val BACKGROUND_START_MESSAGE =
            "Microphone can't start while the app is in the background on this Android version — " +
                "open Hermes Bridge or keep it in the foreground"

        internal const val SILENT_RECORDING_MESSAGE =
            "Recording was silent — microphone access was likely denied by the system " +
                "(is Hermes Bridge in the background?)"

        /**
         * Hard sample budget for one recording. duration=0 means "until
         * stopped" but is still capped at [MAX_DURATION_SECONDS] so a missing
         * stop command cannot fill a permanently powered device (#99).
         */
        internal fun sampleLimitFor(durationSec: Int): Long {
            val effectiveDuration = if (durationSec == 0) MAX_DURATION_SECONDS else durationSec
            return SAMPLE_RATE.toLong() * effectiveDuration.toLong()
        }

        /**
         * User-facing reason for a failed startForegroundService() call.
         * ForegroundServiceStartNotAllowedException (API 31+) is an
         * IllegalStateException; while-in-use denials surface as
         * SecurityException.
         */
        internal fun startFailureMessage(error: Throwable): String {
            val type = error.javaClass.simpleName
            return if (error is IllegalStateException || error is SecurityException) {
                "$BACKGROUND_START_MESSAGE ($type)"
            } else {
                "Could not start microphone service ($type)"
            }
        }

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

    /** Terminal result of one recording session, published only after cleanup. */
    private sealed class Outcome {
        class Ready(val file: File, val bytesWritten: Long) : Outcome()
        class Failed(val message: String) : Outcome()

        /** Nothing new to publish (e.g. cancelled with no audio; onDestroy already set ERROR). */
        object Unchanged : Outcome()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRecording = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var recorder: AudioRecord? = null
    private var recordingJob: Job? = null

    /** Session token owned by this instance, or 0 when it is not recording. */
    @Volatile
    private var activeToken = 0L

    @Volatile
    private var inForeground = false

    /** Latest delivered startId; only touched on the main thread. */
    private var lastStartId = 0
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every command arrives via startForegroundService(), so each one must
        // reach startForeground(). A failure is never allowed to propagate:
        // on the main thread it would crash the whole bridge process.
        if ((intent?.action ?: ACTION_START) == ACTION_STOP) {
            enterForeground(
                buildNotification(if (activeToken != 0L) "Finalizing recording…" else "Stopping microphone…"),
            )
            requestStop()
            return START_NOT_STICKY
        }

        if (!enterForeground(buildNotification("Preparing microphone…"))) {
            if (activeToken == 0L) {
                if (!MicrophoneRecordingState.isSessionClaimed()) {
                    MicrophoneRecordingState.markError(BACKGROUND_START_MESSAGE)
                }
                finishService(errorText = null)
            }
            return START_NOT_STICKY
        }
        startRecording(intent?.getIntExtra(EXTRA_DURATION, 0) ?: 0)
        return START_NOT_STICKY
    }

    private fun startRecording(durationSec: Int) {
        if (activeToken != 0L) {
            Log.w(TAG, "START ignored: a recording is already in progress")
            return
        }
        val token = MicrophoneRecordingState.tryClaimSession()
        if (token == 0L) {
            // tryReserveStart() refuses while a session is claimed, so this is
            // only reachable for a START that bypassed the HTTP reservation.
            Log.w(TAG, "START ignored: another recorder session still owns the microphone")
            return
        }
        activeToken = token
        isRecording.set(true)
        stopRequested.set(false)
        try {
            prepareAndLaunch(token, durationSec)
        } catch (error: Throwable) {
            if (activeToken == token) {
                failBeforeRecording(token, safeError("Could not start recording", error))
            }
        }
    }

    private fun prepareAndLaunch(token: Long, durationSec: Int) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failBeforeRecording(token, "Microphone permission is not granted")
            return
        }
        if (durationSec !in 0..MAX_DURATION_SECONDS) {
            failBeforeRecording(token, "Requested duration is outside the supported range")
            return
        }

        val pending = runCatching { MicrophoneRecordingFiles.createPending(this) }
            .getOrElse { error ->
                failBeforeRecording(token, safeError("Could not prepare recording", error))
                return
            }
        MicrophoneRecordingState.markStarting(pending.completedFile.name)

        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            failBeforeRecording(token, "No supported microphone buffer is available")
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
            failBeforeRecording(token, safeError("Could not initialize microphone", error))
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            failBeforeRecording(token, "Microphone is not available")
            return
        }

        recorder = audioRecord
        val job = scope.launch {
            var writer: WavFileWriter? = null
            var peak = 0
            var outcome: Outcome = Outcome.Unchanged
            try {
                val activeWriter = WavFileWriter(pending, SAMPLE_RATE)
                writer = activeWriter
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
                val sampleLimit = sampleLimitFor(durationSec)

                while (isActive && isRecording.get() && activeWriter.totalSamples < sampleLimit) {
                    val requested = minOf(samples.size.toLong(), sampleLimit - activeWriter.totalSamples).toInt()
                    val read = audioRecord.read(
                        samples,
                        0,
                        requested,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (read > 0) {
                        // Measured before gain: a system-silenced mic (e.g. a
                        // background FGS on Android 11+) delivers exact zeros.
                        peak = maxOf(peak, PcmSampleProcessor.peakAmplitude(samples, read))
                        PcmSampleProcessor.applyRecordingGain(samples, read)
                        activeWriter.write(samples, read)
                        MicrophoneRecordingState.markRecording(activeWriter.totalSamples * 2L)
                    } else if (!stopRequested.get()) {
                        throw IOException("AudioRecord read failed with code $read")
                    }
                }

                MicrophoneRecordingState.markFinalizing(activeWriter.totalSamples * 2L)
                updateNotification("Finalizing recording…")
                safelyStop(audioRecord)
                val completed = activeWriter.finish()
                MicrophoneRecordingFiles.enforceRetention(this@MicrophoneRecorderService)
                outcome = Outcome.Ready(completed, activeWriter.totalSamples * 2L)
            } catch (cancelled: CancellationException) {
                outcome = finalizeInterruptedWriter(writer) ?: Outcome.Unchanged
                throw cancelled
            } catch (error: Throwable) {
                // Keep whatever audio was captured; the session still reports ERROR.
                finalizeInterruptedWriter(writer)
                outcome = Outcome.Failed(safeError("Microphone recording failed", error))
            } finally {
                // All cleanup happens BEFORE the terminal state is published,
                // so a client that sees READY/ERROR and immediately sends
                // /mic_start finds the recorder fully released.
                safelyStop(audioRecord)
                runCatching { audioRecord.release() }
                if (recorder === audioRecord) recorder = null
                writer?.close()
                isRecording.set(false)
                stopRequested.set(false)
                recordingJob = null
                if (activeToken == token) activeToken = 0L
                val errorText = publishOutcome(token, outcome, peak)
                finishService(errorText)
            }
        }
        // Safety net: a job cancelled before its body ever ran never reaches
        // the finally block above. A stale token is a no-op.
        job.invokeOnCompletion { MicrophoneRecordingState.releaseSession(token) }
        recordingJob = job
    }

    /** Publishes [outcome] and releases the session. Returns error text for the notification, if any. */
    private fun publishOutcome(token: Long, outcome: Outcome, peak: Int): String? = when (outcome) {
        is Outcome.Ready -> {
            if (outcome.bytesWritten > 0L && peak == 0) {
                // The WAV is kept, but all-zero audio is almost always the
                // system silently denying mic access, not a quiet room.
                MicrophoneRecordingState.finishSessionError(token, SILENT_RECORDING_MESSAGE)
                SILENT_RECORDING_MESSAGE
            } else {
                updateNotification("Recording ready")
                MicrophoneRecordingState.finishSessionReady(token, outcome.file, outcome.bytesWritten)
                null
            }
        }
        is Outcome.Failed -> {
            MicrophoneRecordingState.finishSessionError(token, outcome.message)
            outcome.message
        }
        Outcome.Unchanged -> {
            MicrophoneRecordingState.releaseSession(token)
            null
        }
    }

    private fun finalizeInterruptedWriter(writer: WavFileWriter?): Outcome.Ready? {
        if (writer == null || writer.totalSamples == 0L) {
            writer?.abort()
            return null
        }
        return runCatching {
            MicrophoneRecordingState.markFinalizing(writer.totalSamples * 2L)
            val completed = writer.finish()
            MicrophoneRecordingFiles.enforceRetention(this)
            Outcome.Ready(completed, writer.totalSamples * 2L)
        }.getOrNull()
    }

    private fun requestStop() {
        if (activeToken == 0L) {
            // A STOP that arrives before the record loop starts (or after an
            // early failure) must not leave the state machine stuck in an
            // active phase, or every later /mic_start returns 409 until the
            // app restarts (#98). A session still finishing elsewhere will
            // publish its own terminal state, so leave that one alone.
            if (MicrophoneRecordingState.snapshot().isActive && !MicrophoneRecordingState.isSessionClaimed()) {
                MicrophoneRecordingState.markError("Recording stopped before it completed")
            }
            finishService(errorText = null)
            return
        }
        stopRequested.set(true)
        isRecording.set(false)
        MicrophoneRecordingState.markFinalizing(
            MicrophoneRecordingState.snapshot().bytesWritten,
        )
        safelyStop(recorder)
    }

    private fun failBeforeRecording(token: Long, message: String) {
        isRecording.set(false)
        stopRequested.set(false)
        if (activeToken == token) activeToken = 0L
        MicrophoneRecordingState.finishSessionError(token, message)
        finishService(message)
    }

    /**
     * Leaves the foreground and stops the service, on the main thread so it
     * is serialized with onStartCommand. It is a no-op if a new recording
     * started in the meantime, and stopSelfResult(lastStartId) refuses to stop
     * if a newer command is still pending delivery. A STOP has a newer
     * startId than its START, so a stopped recording still shuts down.
     * An [errorText] stays visible as a dismissible notification.
     */
    private fun finishService(errorText: String?) {
        mainHandler.post {
            if (destroyed || activeToken != 0L) return@post
            if (errorText != null && inForeground) {
                // DETACH keeps the (now dismissible) error notification after
                // the service stops. If the refresh was refused, remove the
                // stale ongoing one rather than leaving it stuck.
                val shown = enterForeground(buildNotification(errorText, ongoing = false))
                stopForeground(if (shown) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            inForeground = false
            stopSelfResult(lastStartId)
        }
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

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Bridge microphone")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    /**
     * Enters (or refreshes) the microphone foreground state. Returns false
     * instead of throwing when the system refuses, e.g. Android 14's
     * while-in-use rule for a mic FGS started from the background
     * (SecurityException) or Android 12+'s
     * ForegroundServiceStartNotAllowedException (an IllegalStateException).
     */
    private fun enterForeground(notification: Notification): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Microphone foreground start refused (${error.javaClass.simpleName})")
            false
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Microphone foreground start refused (${error.javaClass.simpleName})")
            false
        }

    private fun updateNotification(text: String) {
        // Updating the existing foreground-service notification this way does
        // not require the Android 13 POST_NOTIFICATIONS runtime permission.
        if (inForeground) enterForeground(buildNotification(text))
    }

    private fun safeError(prefix: String, error: Throwable): String =
        "$prefix (${error.javaClass.simpleName})"

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        stopRequested.set(true)
        isRecording.set(false)
        // The system can destroy the service before the record loop starts
        // (or mid-finalization) with no STOP command. Reset an active phase
        // so /mic_start does not stay blocked with 409 (#98).
        if (MicrophoneRecordingState.snapshot().isActive) {
            MicrophoneRecordingState.markError("Recorder service was destroyed before completion")
        }
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
