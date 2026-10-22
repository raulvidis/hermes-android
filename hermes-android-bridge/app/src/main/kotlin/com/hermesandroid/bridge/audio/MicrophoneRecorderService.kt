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

        /**
         * Hard sample budget for one recording. duration=0 means "until
         * stopped" but is still capped at [MAX_DURATION_SECONDS] so a missing
         * stop command cannot fill a permanently powered device (#99).
         */
        internal fun sampleLimitFor(durationSec: Int): Long {
            val effectiveDuration = if (durationSec == 0) MAX_DURATION_SECONDS else durationSec
            return SAMPLE_RATE.toLong() * effectiveDuration.toLong()
        }
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_DURATION = "duration"

        /**
         * What `/mic_status` reports when the service died while a recording was
         * still being finalised (review F16). Distinct from the STARTING/RECORDING
         * message above it: this one is published after the fact, from the abandoned
         * job's completion handler.
         */
        internal const val ABANDONED_RECORDING_MESSAGE =
            "Recorder service was destroyed before the recording completed"

        /** What `/mic_status` reports when the system refuses the microphone foreground service. */
        internal const val BACKGROUND_START_MESSAGE =
            "Microphone can't start while the app is in the background on this Android version — " +
                "open Hermes Bridge or keep it in the foreground"

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

    /**
     * The live `AudioRecord`. Internal rather than private so a test can hold one
     * and assert it is released: the case that leaks it is a job cancelled before
     * its body ran, which no real `AudioRecord` can be made to reproduce on demand
     * (review F22).
     */
    @Volatile
    internal var recorder: AudioRecord? = null

    /**
     * The record loop's handle. Internal rather than private so a test can put the
     * service in the state onDestroy has to reason about — a job that was
     * cancelled before its body ran — without racing a real `AudioRecord`.
     */
    internal var recordingJob: Job? = null

    /**
     * The recording this instance owns, i.e. the file `markStarting` published.
     * OnDestroy's completion handler compares it against the live phase so a late
     * handler cannot settle a newer recording (F16).
     */
    @Volatile
    internal var ownedFileName: String? = null

    /** Whether startForeground() last succeeded; notification refreshes are skipped otherwise. */
    @Volatile
    private var inForeground = false

    /**
     * Test seam replacing the platform startForeground() call, so a test can
     * reproduce the system refusing the microphone FGS. Null in production.
     */
    internal var foregroundStarter: ((Notification) -> Unit)? = null

    /** Latest delivered startId; only touched on the main thread. */
    private var lastStartId = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set by onDestroy; only touched on the main thread. */
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val action = intent?.action ?: ACTION_START
        val recording = isRecording.get()
        // Every command arrives via startForegroundService(), so each one must
        // reach startForeground(). A refusal (Android 14's while-in-use rule
        // for a microphone FGS started from the background) is never allowed
        // to propagate: on the main thread it would crash the whole bridge.
        val text = when {
            action == ACTION_STOP && recording -> "Finalizing recording…"
            action == ACTION_STOP -> "Stopping microphone…"
            recording -> "Recording…"
            else -> "Preparing microphone…"
        }
        val foreground = enterForeground(buildNotification(text))

        if (action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (!foreground) {
            if (!recording) {
                // The /mic_start reservation can never be fulfilled now.
                if (MicrophoneRecordingState.snapshot().isAwaitingRecorder) {
                    MicrophoneRecordingState.markError(BACKGROUND_START_MESSAGE)
                }
                stopForegroundCompat()
                stopSelfResult(lastStartId)
            }
            return START_NOT_STICKY
        }
        startRecording(intent?.getIntExtra(EXTRA_DURATION, 0) ?: 0)
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
        // Recorded before the job is launched so onDestroy's completion handler
        // can tell this instance's recording from a newer one (F16).
        ownedFileName = pending.completedFile.name

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
            var outcome: Outcome? = null
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
                val sampleLimit = sampleLimitFor(durationSec)

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
                outcome = Outcome.Ready(completed, writer.totalSamples * 2L)
            } catch (cancelled: CancellationException) {
                outcome = finalizeInterruptedWriter(writer)
                throw cancelled
            } catch (error: Throwable) {
                // Keep whatever audio was captured; the session still reports ERROR.
                finalizeInterruptedWriter(writer)
                outcome = Outcome.Failed(safeError("Microphone recording failed", error))
            } finally {
                // Release everything BEFORE the terminal state is published. A
                // client that sees READY/ERROR and immediately sends /mic_start
                // used to find isRecording still set (its START was silently
                // dropped and later reported as "destroyed before completion"),
                // or had its new recording killed by this job's stopSelf().
                safelyStop(audioRecord)
                runCatching { audioRecord.release() }
                if (recorder === audioRecord) recorder = null
                writer?.close()
                isRecording.set(false)
                stopRequested.set(false)
                recordingJob = null
                publishOutcome(outcome)
                finishService()
            }
        }
    }

    /** Terminal result of one recording, published only after cleanup. */
    private sealed class Outcome {
        class Ready(val file: File, val bytesWritten: Long) : Outcome()
        class Failed(val message: String) : Outcome()
    }

    private fun publishOutcome(outcome: Outcome?) {
        when (outcome) {
            is Outcome.Ready -> MicrophoneRecordingState.markReady(outcome.file, outcome.bytesWritten)
            is Outcome.Failed -> MicrophoneRecordingState.markError(outcome.message)
            // Cancelled with no audio: onDestroy, or the F16 completion handler
            // that runs after this, settles the phase.
            null -> Unit
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

    /**
     * Leaves the foreground and stops the service once a recording is over.
     * Runs on the main thread so it is serialized with onStartCommand: it is a
     * no-op if a new recording started meanwhile, and stopSelfResult(latest
     * startId) refuses while a newer command is still pending delivery. A STOP
     * has a newer startId than its START and is delivered first, so a stopped
     * recording still shuts the service down.
     */
    private fun finishService() {
        mainHandler.post {
            if (destroyed || isRecording.get()) return@post
            stopForegroundCompat()
            stopSelfResult(lastStartId)
        }
    }

    private fun requestStop() {
        if (!isRecording.get()) {
            // A STOP that arrives before the record loop starts (or after an
            // early failure) must not leave the state machine stuck in an
            // active phase, or every later /mic_start returns 409 until the
            // app restarts (#98).
            // isAwaitingRecorder, not isActive: a second STOP arriving while the
            // first is finalising must not downgrade a complete recording (F10).
            if (MicrophoneRecordingState.snapshot().isAwaitingRecorder) {
                MicrophoneRecordingState.markError("Recording stopped before it completed")
            }
            stopForegroundCompat()
            stopSelfResult(lastStartId)
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
        stopSelfResult(lastStartId)
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

    /**
     * Enters (or refreshes) the microphone foreground state. Returns false
     * instead of throwing when the system refuses: Android 14+ throws
     * SecurityException for a microphone FGS started from the background, and
     * Android 12+ may throw ForegroundServiceStartNotAllowedException (an
     * IllegalStateException).
     */
    private fun enterForeground(notification: Notification): Boolean =
        try {
            val starter = foregroundStarter
            if (starter != null) {
                starter(notification)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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

    private fun stopForegroundCompat() {
        inForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun safeError(prefix: String, error: Throwable): String =
        "$prefix (${error.javaClass.simpleName})"

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        inForeground = false
        stopRequested.set(true)
        isRecording.set(false)
        // The system can destroy the service before the record loop starts, with
        // no STOP command. Reset a phase that can no longer complete so
        // /mic_start does not stay blocked with 409 (#98) — but leave FINALIZING
        // to the job that owns it, which has no suspension points left to run and
        // will reach markReady/markError on its own. Resetting it here published a
        // transient `error` for a recording that was already complete (F10).
        if (MicrophoneRecordingState.snapshot().isAwaitingRecorder) {
            MicrophoneRecordingState.markError("Recorder service was destroyed before completion")
        }
        safelyStop(recorder)
        val job = recordingJob
        job?.cancel()
        if (job == null) {
            runCatching { recorder?.release() }
            recorder = null
        }
        settleAbandonedRecording(job)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Close the hole F10's exemption leaves (review F16).
     *
     * "Leave FINALIZING to the job that owns it" is only sound while that job
     * runs. `requestStop()` publishes FINALIZING from the main thread, and the
     * record loop may still be queued on `Dispatchers.IO`; a destroy inside that
     * window cancels the job *before its body starts*, so the finalisation block,
     * its catch and its finally all never execute. The phase then stays
     * FINALIZING — `isActive` — forever, `tryReserveStart()` keeps returning
     * false, and every `/mic_start` answers 409 until the app restarts: #98, one
     * phase later.
     *
     * `invokeOnCompletion` is what makes this safe for F10: it fires when the job
     * is done, so a finaliser that ran has already published READY or ERROR and
     * the settle is a no-op, while a job that never ran gets settled here. A null
     * job means this instance never started a recording, so the phase is somebody
     * else's and is left alone.
     *
     * The same job also owns the `AudioRecord` release (review F22), so it is
     * handled here too — see [releaseAbandonedRecorder].
     */
    private fun settleAbandonedRecording(job: Job?) {
        if (job == null) return
        val owned = ownedFileName
        job.invokeOnCompletion {
            MicrophoneRecordingState.settleAbandonedFinalizing(
                activeFile = owned,
                message = ABANDONED_RECORDING_MESSAGE,
            )
            releaseAbandonedRecorder()
        }
    }

    /**
     * Release an `AudioRecord` whose job never ran (review F22).
     *
     * The record loop's `finally` releases it, but a coroutine cancelled before its
     * body starts runs nothing — not the loop, not the catch, not the finally — and
     * `onDestroy`'s own unconditional release is guarded by `recordingJob == null`,
     * which is exactly the case this is not. `safelyStop` only stops capture; it
     * does not release. The native recorder therefore stayed held for the life of
     * the process: the microphone-in-use indicator never cleared, and the next
     * `/mic_start` could fail to initialise the microphone.
     *
     * Runs from the job's completion handler, so a loop that did execute has
     * already released and nulled the field and this is a no-op. Waiting for
     * completion is also what keeps a still-running loop from having the microphone
     * pulled out from under it.
     */
    private fun releaseAbandonedRecorder() {
        val abandoned = recorder ?: return
        recorder = null
        safelyStop(abandoned)
        runCatching { abandoned.release() }
    }
}
