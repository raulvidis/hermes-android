package com.hermesandroid.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ── Review F16: settling a FINALIZING phase nothing will finish ────────

    /**
     * `isAwaitingRecorder` deliberately excludes FINALIZING (F10): the
     * finalisation block has no suspension points and owns both terminal states,
     * so resetting it from outside published a transient `error` for a recording
     * that was already complete. That reasoning only holds while the block runs.
     * A coroutine cancelled before its body starts executes nothing — not the
     * block, not its catch, not its finally — so the phase stays FINALIZING,
     * which is `isActive`, so `tryReserveStart()` keeps returning false and every
     * later `/mic_start` answers 409 until the app restarts (#98, finalising case).
     */
    @Test
    fun `an abandoned finalizing phase is settled for the recording that owns it`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording.wav")
        MicrophoneRecordingState.markFinalizing(2048L)

        assertTrue(
            MicrophoneRecordingState.settleAbandonedFinalizing(
                activeFile = "recording.wav",
                message = "Recorder service was destroyed before the recording completed",
            ),
        )

        val snapshot = MicrophoneRecordingState.snapshot()
        assertEquals(MicrophoneRecordingPhase.ERROR, snapshot.phase)
        assertFalse("a stuck FINALIZING keeps every /mic_start at 409", snapshot.isActive)
        assertTrue("a new recording must be reservable again", MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markError("test cleanup")
    }

    @Test
    fun `a phase belonging to a newer recording is left alone`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("newer.wav")
        MicrophoneRecordingState.markFinalizing(4096L)

        assertFalse(
            "a previous service instance must not settle somebody else's recording",
            MicrophoneRecordingState.settleAbandonedFinalizing("older.wav", "abandoned"),
        )

        assertEquals(MicrophoneRecordingPhase.FINALIZING, MicrophoneRecordingState.snapshot().phase)
        assertEquals("newer.wav", MicrophoneRecordingState.snapshot().activeFileName)
    }

    @Test
    fun `a recording the finaliser already settled is never rewritten`() {
        // F10, at the state level: the completion handler runs *after* the job's
        // own finalisation, so a good recording is already READY by then.
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording.wav")
        MicrophoneRecordingState.markFinalizing(2048L)
        MicrophoneRecordingState.markReady(java.io.File("recording.wav"), 2048L)

        assertFalse(
            MicrophoneRecordingState.settleAbandonedFinalizing("recording.wav", "abandoned"),
        )

        assertEquals(MicrophoneRecordingPhase.READY, MicrophoneRecordingState.snapshot().phase)
        assertNull(MicrophoneRecordingState.snapshot().error)
    }

    @Test
    fun `an awaiting-recorder phase is left to the reset that already handles it`() {
        // STARTING/RECORDING are onDestroy's `isAwaitingRecorder` branch, not this
        // one; settling them here too would make the two paths race.
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording.wav")
        MicrophoneRecordingState.markRecording(1024L)

        assertFalse(
            MicrophoneRecordingState.settleAbandonedFinalizing("recording.wav", "abandoned"),
        )

        assertEquals(MicrophoneRecordingPhase.RECORDING, MicrophoneRecordingState.snapshot().phase)
    }
}
