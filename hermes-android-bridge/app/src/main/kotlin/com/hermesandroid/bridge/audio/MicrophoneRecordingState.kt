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

    /**
     * Token of the recorder session that currently owns the microphone, or 0.
     * The service claims a session before touching the recorder and releases
     * it only after all cleanup is done, atomically with publishing the
     * terminal READY/ERROR state. A client that sees READY and immediately
     * calls /mic_start therefore never races the previous session's teardown.
     */
    @Volatile
    private var activeSessionToken = 0L
    private var lastSessionToken = 0L

    fun snapshot(): MicrophoneRecordingSnapshot = current

    @Synchronized
    fun tryReserveStart(): Boolean {
        // A claimed session means the service would reject a new START, so the
        // reservation must fail instead of leaving the state stuck at STARTING.
        if (current.isActive || activeSessionToken != 0L) return false
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

    /** Claims the recorder for one session. Returns a non-zero token, or 0 if already claimed. */
    @Synchronized
    fun tryClaimSession(): Long {
        if (activeSessionToken != 0L) return 0L
        lastSessionToken += 1
        activeSessionToken = lastSessionToken
        return activeSessionToken
    }

    fun isSessionClaimed(): Boolean = activeSessionToken != 0L

    /** Releases [token] if it still owns the recorder; a stale token is a no-op. */
    @Synchronized
    fun releaseSession(token: Long) {
        if (token != 0L && activeSessionToken == token) activeSessionToken = 0L
    }

    /** Releases the session and publishes READY in one step. Cleanup must already be done. */
    @Synchronized
    fun finishSessionReady(token: Long, file: File, bytesWritten: Long) {
        releaseSession(token)
        markReady(file, bytesWritten)
    }

    /** Releases the session and publishes ERROR in one step. Cleanup must already be done. */
    @Synchronized
    fun finishSessionError(token: Long, message: String) {
        releaseSession(token)
        markError(message)
    }

    /** Test-only: restores the pristine IDLE state and drops any session claim. */
    @Synchronized
    internal fun resetForTests() {
        current = MicrophoneRecordingSnapshot()
        activeSessionToken = 0L
    }
}
