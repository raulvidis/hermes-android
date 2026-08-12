package com.hermesandroid.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MicrophoneRecordingStateTest {
    @Before
    fun resetState() {
        MicrophoneRecordingState.markError("test reset")
    }

    @Test
    fun `only one start can reserve the recorder`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        assertFalse(MicrophoneRecordingState.tryReserveStart())
        assertEquals(
            MicrophoneRecordingPhase.STARTING,
            MicrophoneRecordingState.snapshot().phase,
        )
    }

    @Test
    fun `late audio progress cannot leave finalizing state`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording_test.wav")
        MicrophoneRecordingState.markRecording(bytesWritten = 128L)
        MicrophoneRecordingState.markFinalizing(bytesWritten = 128L)

        MicrophoneRecordingState.markRecording(bytesWritten = 256L)

        val snapshot = MicrophoneRecordingState.snapshot()
        assertEquals(MicrophoneRecordingPhase.FINALIZING, snapshot.phase)
        assertEquals(128L, snapshot.bytesWritten)
    }
}
