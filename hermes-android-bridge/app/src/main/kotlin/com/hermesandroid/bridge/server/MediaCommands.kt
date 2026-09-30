package com.hermesandroid.bridge.server

import android.Manifest
import android.content.pm.PackageManager
import com.google.gson.JsonObject
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.audio.MicrophoneRecorderService
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import com.hermesandroid.bridge.audio.MicrophoneRecordingState
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.media.ScreenRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Audio and speech endpoints: POST /speak, POST /stop_speaking, POST
 * /mic_start, POST /mic_stop, GET /mic_status and POST /screen_record —
 * including the microphone duration validation, the RECORD_AUDIO 403, the
 * single-active-recording 409 reservation and the clamped recording duration.
 *
 * Implementation detail of [CommandDispatcher] — the (method, path) routes are
 * declared in its table; these handlers hold the endpoint behaviour.
 */
internal object MediaCommands {

    suspend fun speak(body: JsonObject): Pair<Any, Int> {
        val text = body.get("text")?.asString ?: ""
        val queue = body.get("queue")?.asInt ?: 1
        val result = ActionExecutor.speak(text, queue)
        return result to 200
    }

    suspend fun stopSpeaking(): Pair<Any, Int> {
        val result = ActionExecutor.stopSpeaking()
        return result to 200
    }

    suspend fun micStart(body: JsonObject): Pair<Any, Int> {
        val durationValue = body.get("duration")
        val durationSec = when {
            durationValue == null -> 0
            !durationValue.isJsonPrimitive || !durationValue.asJsonPrimitive.isNumber -> {
                return mapOf("error" to "duration must be an integer number of seconds") to 400
            }
            else -> durationValue.asJsonPrimitive.asString.toIntOrNull()
                ?: return mapOf("error" to "duration must be an integer number of seconds") to 400
        }
        val app = BridgeApplication.instance
        if (durationSec !in 0..MicrophoneRecorderService.MAX_DURATION_SECONDS) {
            return mapOf(
                "error" to "duration must be between 0 and ${MicrophoneRecorderService.MAX_DURATION_SECONDS} seconds"
            ) to 400
        }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return mapOf("error" to "Microphone permission is not granted") to 403
        }
        if (!MicrophoneRecordingState.tryReserveStart()) {
            return mapOf("error" to "A microphone recording is already active") to 409
        }

        return runCatching {
            MicrophoneRecorderService.start(app, durationSec)
            mapOf(
                "status" to "starting",
                "duration" to durationSec,
            ) to 202
        }.getOrElse { error ->
            MicrophoneRecordingState.markError(
                "Could not start microphone service (${error.javaClass.simpleName})",
            )
            mapOf(
                "error" to "Could not start microphone service (${error.javaClass.simpleName})"
            ) to 500
        }
    }

    suspend fun micStop(): Pair<Any, Int> {
        val snapshot = MicrophoneRecordingState.snapshot()
        if (!snapshot.isActive) {
            return mapOf("status" to "idle") to 200
        }
        return runCatching {
            MicrophoneRecorderService.stop(BridgeApplication.instance)
            mapOf("status" to "stopping") to 202
        }.getOrElse { error ->
            mapOf(
                "error" to "Could not stop microphone service (${error.javaClass.simpleName})"
            ) to 500
        }
    }

    suspend fun micStatus(): Pair<Any, Int> {
        val app = BridgeApplication.instance
        val snapshot = MicrophoneRecordingState.snapshot()
        val files = MicrophoneRecordingFiles.listCompleted(app)
        val latest = files.firstOrNull()
        return mapOf(
            "phase" to snapshot.phase.name.lowercase(),
            "recording" to snapshot.isActive,
            "count" to files.size,
            "retentionLimit" to MicrophoneRecordingFiles.MAX_COMPLETED_RECORDINGS,
            "latest" to latest?.name,
            "latestSize" to latest?.length(),
            "bytesWritten" to snapshot.bytesWritten,
            "startedAt" to snapshot.startedAtMs,
            "error" to snapshot.error,
        ) to 200
    }

    suspend fun screenRecord(body: JsonObject): Pair<Any, Int> {
        val durationMs = CommandDispatcher.clampRecordDurationMs(body.get("durationMs")?.asLong)
        val result = withContext(Dispatchers.IO) {
            ScreenRecorder.record(durationMs)
        }
        return result to 200
    }
}
