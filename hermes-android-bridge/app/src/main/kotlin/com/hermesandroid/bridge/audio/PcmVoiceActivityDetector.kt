package com.hermesandroid.bridge.audio

import kotlin.math.sqrt

/**
 * Small deterministic speech-end detector for push-to-talk recordings.
 *
 * It never stops before a minimum amount of speech has been observed. Quiet
 * input simply falls back to the caller's hard duration limit.
 */
internal class PcmVoiceActivityDetector(
    sampleRate: Int,
    minSpeechMs: Int = DEFAULT_MIN_SPEECH_MS,
    trailingSilenceMs: Int = DEFAULT_TRAILING_SILENCE_MS,
    private val speechRmsThreshold: Double = DEFAULT_SPEECH_RMS_THRESHOLD,
) {
    companion object {
        const val DEFAULT_MIN_SPEECH_MS = 160
        const val DEFAULT_TRAILING_SILENCE_MS = 700
        const val MIN_TRAILING_SILENCE_MS = 300
        const val MAX_TRAILING_SILENCE_MS = 3_000
        private const val DEFAULT_SPEECH_RMS_THRESHOLD = 300.0
    }

    private val minimumSpeechSamples = samplesForMs(sampleRate, minSpeechMs)
    private val trailingSilenceSamples = samplesForMs(sampleRate, trailingSilenceMs)
    private var consecutiveSpeechSamples = 0L
    private var consecutiveSilenceSamples = 0L
    private var speechStarted = false

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(minSpeechMs > 0) { "minSpeechMs must be positive" }
        require(trailingSilenceMs > 0) { "trailingSilenceMs must be positive" }
        require(speechRmsThreshold > 0) { "speechRmsThreshold must be positive" }
    }

    fun shouldStop(samples: ShortArray, count: Int): Boolean {
        require(count in 0..samples.size) { "Invalid sample count" }
        if (count == 0) return false

        val speechInBlock = rootMeanSquare(samples, count) >= speechRmsThreshold
        if (speechInBlock) {
            consecutiveSpeechSamples += count
            consecutiveSilenceSamples = 0L
            if (consecutiveSpeechSamples >= minimumSpeechSamples) {
                speechStarted = true
            }
        } else if (speechStarted) {
            consecutiveSilenceSamples += count
        } else {
            consecutiveSpeechSamples = 0L
        }

        return speechStarted && consecutiveSilenceSamples >= trailingSilenceSamples
    }

    private fun rootMeanSquare(samples: ShortArray, count: Int): Double {
        var sumSquares = 0.0
        for (index in 0 until count) {
            val sample = samples[index].toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / count)
    }

    private fun samplesForMs(sampleRate: Int, milliseconds: Int): Long =
        sampleRate.toLong() * milliseconds.toLong() / 1_000L
}
