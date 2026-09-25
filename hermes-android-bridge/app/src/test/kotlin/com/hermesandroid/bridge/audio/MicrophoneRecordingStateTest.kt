package com.hermesandroid.bridge.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MicrophoneRecordingStateTest {
    @Before
    fun resetState() {
        MicrophoneRecordingState.resetForTests()
    }

    @Test
    fun `reservation is refused while a session still owns the recorder`() {
        val token = MicrophoneRecordingState.tryClaimSession()
        assertTrue(token != 0L)
        // Even with a terminal phase visible, the service would reject a new
        // START until cleanup finished, so reserving must fail too.
        MicrophoneRecordingState.markReady(File("recording_old.wav"), 10L)

        assertFalse(MicrophoneRecordingState.tryReserveStart())
        assertEquals(0L, MicrophoneRecordingState.tryClaimSession())
    }

    @Test
    fun `READY becomes visible together with the session release`() {
        val token = MicrophoneRecordingState.tryClaimSession()
        assertTrue(MicrophoneRecordingState.isSessionClaimed())

        MicrophoneRecordingState.finishSessionReady(token, File("recording_done.wav"), 64L)

        val snapshot = MicrophoneRecordingState.snapshot()
        assertEquals(MicrophoneRecordingPhase.READY, snapshot.phase)
        assertEquals("recording_done.wav", snapshot.activeFileName)
        assertFalse(MicrophoneRecordingState.isSessionClaimed())
        // A client that polls READY and immediately starts again succeeds.
        assertTrue(MicrophoneRecordingState.tryReserveStart())
    }

    @Test
    fun `ERROR releases the session and a stale token cannot release a newer one`() {
        val first = MicrophoneRecordingState.tryClaimSession()
        MicrophoneRecordingState.finishSessionError(first, "boom")
        assertEquals(MicrophoneRecordingPhase.ERROR, MicrophoneRecordingState.snapshot().phase)
        assertEquals("boom", MicrophoneRecordingState.snapshot().error)

        val second = MicrophoneRecordingState.tryClaimSession()
        assertTrue(second != 0L && second != first)
        MicrophoneRecordingState.releaseSession(first)

        assertTrue(MicrophoneRecordingState.isSessionClaimed())
        MicrophoneRecordingState.releaseSession(second)
        assertFalse(MicrophoneRecordingState.isSessionClaimed())
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
