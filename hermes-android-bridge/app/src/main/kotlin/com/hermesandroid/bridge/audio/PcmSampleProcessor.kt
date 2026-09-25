package com.hermesandroid.bridge.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/** Deterministic PCM processing applied before samples are written to disk. */
internal object PcmSampleProcessor {
    internal const val RECORDING_GAIN = 2.5f

    fun applyRecordingGain(samples: ShortArray, count: Int) {
        require(count in 0..samples.size) { "Invalid sample count" }
        for (index in 0 until count) {
            samples[index] = (samples[index] * RECORDING_GAIN)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    /** Largest absolute sample value in the first [count] samples (0 for all-zero audio). */
    fun peakAmplitude(samples: ShortArray, count: Int): Int {
        require(count in 0..samples.size) { "Invalid sample count" }
        var peak = 0
        for (index in 0 until count) {
            val magnitude = abs(samples[index].toInt())
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }
}
