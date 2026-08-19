package com.hermesandroid.bridge.media

import java.io.File

internal data class NoiseTriggerSnapshot(
    val active: Boolean = false,
    val recording: Boolean = false,
    val pausingForDialog: Boolean = false,
    val pausedForDialog: Boolean = false,
    val thresholdRms: Double = NoiseTriggeredVideoService.DEFAULT_THRESHOLD_RMS,
    val clipSeconds: Int = NoiseTriggeredVideoService.DEFAULT_CLIP_SECONDS,
    val cooldownSeconds: Int = NoiseTriggeredVideoService.DEFAULT_COOLDOWN_SECONDS,
    val lastTriggerAtMs: Long? = null,
    val latest: File? = null,
    val count: Int = 0,
    val error: String? = null,
)

internal object NoiseTriggerState {
    @Volatile
    private var current = NoiseTriggerSnapshot()

    fun snapshot(): NoiseTriggerSnapshot = current

    @Synchronized
    fun started(thresholdRms: Double, clipSeconds: Int, cooldownSeconds: Int) {
        current = NoiseTriggerSnapshot(
            active = true,
            thresholdRms = thresholdRms,
            clipSeconds = clipSeconds,
            cooldownSeconds = cooldownSeconds,
        )
    }

    @Synchronized
    fun pauseRequestedForDialog() {
        current = current.copy(
            pausingForDialog = true,
            pausedForDialog = false,
            error = null,
        )
    }

    @Synchronized
    fun pausedForDialog() {
        current = current.copy(
            pausingForDialog = false,
            pausedForDialog = true,
            recording = false,
            error = null,
        )
    }

    @Synchronized
    fun resumedAfterDialog() {
        current = current.copy(
            pausingForDialog = false,
            pausedForDialog = false,
            error = null,
        )
    }

    @Synchronized
    fun triggered() {
        current = current.copy(recording = true, lastTriggerAtMs = System.currentTimeMillis(), error = null)
    }

    @Synchronized
    fun completed(file: File?, count: Int) {
        current = current.copy(recording = false, latest = file, count = count, error = null)
    }

    @Synchronized
    fun stopped() {
        current = current.copy(
            active = false,
            recording = false,
            pausingForDialog = false,
            pausedForDialog = false,
        )
    }

    @Synchronized
    fun failed(message: String) {
        current = current.copy(recording = false, error = message)
    }
}
