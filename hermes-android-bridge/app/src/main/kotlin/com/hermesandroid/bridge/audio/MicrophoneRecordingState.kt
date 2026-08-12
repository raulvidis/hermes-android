package com.hermesandroid.bridge.audio

import java.io.File

internal enum class MicrophoneRecordingPhase {
    IDLE,
    STARTING,
    RECORDING,
    FINALIZING,
    READY,
    ERROR,
}

internal data class MicrophoneRecordingSnapshot(
    val phase: MicrophoneRecordingPhase = MicrophoneRecordingPhase.IDLE,
    val activeFileName: String? = null,
    val bytesWritten: Long = 0L,
    val startedAtMs: Long? = null,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = phase == MicrophoneRecordingPhase.STARTING ||
            phase == MicrophoneRecordingPhase.RECORDING ||
            phase == MicrophoneRecordingPhase.FINALIZING
}

internal object MicrophoneRecordingState {
    @Volatile
    private var current = MicrophoneRecordingSnapshot()

    fun snapshot(): MicrophoneRecordingSnapshot = current

    @Synchronized
    fun tryReserveStart(): Boolean {
        if (current.isActive) return false
        current = MicrophoneRecordingSnapshot(
            phase = MicrophoneRecordingPhase.STARTING,
            startedAtMs = System.currentTimeMillis(),
        )
        return true
    }

    @Synchronized
    fun markStarting(fileName: String) {
        val reservedStart = current.startedAtMs
            .takeIf { current.phase == MicrophoneRecordingPhase.STARTING }
        current = current.copy(
            phase = MicrophoneRecordingPhase.STARTING,
            activeFileName = fileName,
            bytesWritten = 0L,
            startedAtMs = reservedStart ?: System.currentTimeMillis(),
            error = null,
        )
    }

    @Synchronized
    fun markRecording(bytesWritten: Long = 0L) {
        if (current.phase == MicrophoneRecordingPhase.FINALIZING) return
        current = current.copy(
            phase = MicrophoneRecordingPhase.RECORDING,
            bytesWritten = bytesWritten,
            error = null,
        )
    }

    @Synchronized
    fun markFinalizing(bytesWritten: Long) {
        if (!current.isActive) return
        current = current.copy(
            phase = MicrophoneRecordingPhase.FINALIZING,
            bytesWritten = bytesWritten,
        )
    }

    @Synchronized
    fun markReady(file: File, bytesWritten: Long) {
        current = MicrophoneRecordingSnapshot(
            phase = MicrophoneRecordingPhase.READY,
            activeFileName = file.name,
            bytesWritten = bytesWritten,
            startedAtMs = current.startedAtMs,
        )
    }

    @Synchronized
    fun markError(message: String) {
        current = current.copy(
            phase = MicrophoneRecordingPhase.ERROR,
            error = message,
        )
    }
}
