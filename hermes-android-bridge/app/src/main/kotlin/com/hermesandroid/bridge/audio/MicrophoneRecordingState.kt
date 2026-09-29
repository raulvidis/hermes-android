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

    /**
     * Active phases that cannot reach a terminal state without the recorder loop.
     *
     * FINALIZING is deliberately excluded. Its block has no suspension points and
     * it owns both terminal states (`markReady`, `markError`), so resetting it
     * from outside publishes a transient `error` for a recording that is already
     * complete and downloadable — `CommandDispatcher` serves `latest` from disk
     * regardless of phase (review F10). STARTING and RECORDING do have to be
     * reset, or `/mic_start` stays blocked with 409 until the app restarts (#98).
     *
     * The exemption assumes the finalisation block actually runs. A job cancelled
     * before its body starts never reaches it, so that case is settled separately
     * by [MicrophoneRecordingState.settleAbandonedFinalizing] (review F16).
     */
    val isAwaitingRecorder: Boolean
        get() = phase == MicrophoneRecordingPhase.STARTING ||
            phase == MicrophoneRecordingPhase.RECORDING
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

    /**
     * Settle a FINALIZING phase that nothing will ever finish (review F16).
     *
     * FINALIZING is excluded from [MicrophoneRecordingSnapshot.isAwaitingRecorder]
     * on purpose (F10): the finalisation block has no suspension points and owns
     * both terminal states, so resetting it from outside published a transient
     * `error` for a recording that was already complete and downloadable. That
     * reasoning holds only while the block runs. A coroutine cancelled *before its
     * body starts* executes nothing — not the block, not its catch, not its
     * finally — so the phase stays FINALIZING, which is `isActive`, so
     * [tryReserveStart] keeps returning false and every `/mic_start` answers 409
     * until the app restarts (#98, one phase later).
     *
     * Called from the recording job's completion handler, which is what makes the
     * two cases distinguishable: a job that reached `markReady` leaves the phase at
     * READY and this is a no-op, so F10 still holds.
     *
     * @param activeFile the recording the caller owns; a phase belonging to a
     *   newer recording is left alone.
     * @return true when this call settled the phase.
     */
    @Synchronized
    fun settleAbandonedFinalizing(activeFile: String?, message: String): Boolean {
        if (current.phase != MicrophoneRecordingPhase.FINALIZING) return false
        if (activeFile != null && current.activeFileName != activeFile) return false
        current = current.copy(phase = MicrophoneRecordingPhase.ERROR, error = message)
        return true
    }

    @Synchronized
    fun markError(message: String) {
        current = current.copy(
            phase = MicrophoneRecordingPhase.ERROR,
            error = message,
        )
    }
}
