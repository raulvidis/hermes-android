package com.hermesandroid.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the 30-minute recording safety cap (#99).
 * duration=0 means "until stopped" but must still be bounded by
 * MAX_DURATION_SECONDS so a missing stop command cannot fill a
 * permanently powered device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MicrophoneRecorderServiceCapTest {

    @Test
    fun `duration zero is capped at 30 minutes of samples`() {
        assertEquals(
            16_000L * (30 * 60),
            MicrophoneRecorderService.sampleLimitFor(0),
        )
    }

    @Test
    fun `explicit duration maps to exactly that many samples`() {
        assertEquals(16_000L * 5, MicrophoneRecorderService.sampleLimitFor(5))
        assertEquals(16_000L * 60, MicrophoneRecorderService.sampleLimitFor(60))
        assertEquals(
            16_000L * MicrophoneRecorderService.MAX_DURATION_SECONDS,
            MicrophoneRecorderService.sampleLimitFor(MicrophoneRecorderService.MAX_DURATION_SECONDS),
        )
    }

    @Test
    fun `no valid duration exceeds the 30 minute ceiling`() {
        val ceiling = 16_000L * MicrophoneRecorderService.MAX_DURATION_SECONDS
        for (duration in 0..MicrophoneRecorderService.MAX_DURATION_SECONDS step 137) {
            assertTrue(MicrophoneRecorderService.sampleLimitFor(duration) in 1..ceiling)
        }
    }

    @Test
    fun `all-zero audio is reported as a silenced microphone, anything else is READY`() {
        assertEquals(
            MicrophoneRecorderService.SILENT_RECORDING_MESSAGE,
            MicrophoneRecorderService.silentRecordingError(bytesWritten = 32_000L, peakAmplitude = 0),
        )
        assertNull(MicrophoneRecorderService.silentRecordingError(bytesWritten = 32_000L, peakAmplitude = 1))
        // An empty recording is not evidence of a silenced mic.
        assertNull(MicrophoneRecorderService.silentRecordingError(bytesWritten = 0L, peakAmplitude = 0))
    }
}
