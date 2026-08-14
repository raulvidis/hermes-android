package com.hermesandroid.bridge.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmVoiceActivityDetectorTest {
    private val silence = ShortArray(100) { 20 }
    private val speech = ShortArray(100) { 1_200 }

    @Test
    fun `silence cannot stop a recording before speech starts`() {
        val detector = detector()

        repeat(20) {
            assertFalse(detector.shouldStop(silence, silence.size))
        }
    }

    @Test
    fun `recording stops after real speech followed by trailing silence`() {
        val detector = detector()

        assertFalse(detector.shouldStop(speech, speech.size))
        assertFalse(detector.shouldStop(speech, speech.size))
        assertFalse(detector.shouldStop(silence, silence.size))
        assertFalse(detector.shouldStop(silence, silence.size))
        assertTrue(detector.shouldStop(silence, silence.size))
    }

    @Test
    fun `brief pause during speech does not stop recording`() {
        val detector = detector()

        repeat(2) { assertFalse(detector.shouldStop(speech, speech.size)) }
        repeat(2) { assertFalse(detector.shouldStop(silence, silence.size)) }
        assertFalse(detector.shouldStop(speech, speech.size))
    }

    @Test
    fun `short noise burst is not accepted as speech`() {
        val detector = detector()

        assertFalse(detector.shouldStop(speech, count = 50))
        repeat(10) {
            assertFalse(detector.shouldStop(silence, silence.size))
        }
    }

    private fun detector() = PcmVoiceActivityDetector(
        sampleRate = 1_000,
        minSpeechMs = 160,
        trailingSilenceMs = 300,
        speechRmsThreshold = 300.0,
    )
}
