package com.hermesandroid.bridge.audio

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression tests for #98: the recorder state machine could stick at
 * STARTING when the service was destroyed before the record loop began (or
 * a STOP arrived while isRecording was already false), after which every
 * /mic_start returned 409 until the app restarted.
 *
 * requestStop()'s not-recording branch and onDestroy() must reset an active
 * phase to ERROR so tryReserveStart() succeeds again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MicrophoneRecorderServiceStuckStateTest {

    private fun stopIntent(): Intent =
        Intent(RuntimeEnvironment.getApplication(), MicrophoneRecorderService::class.java)
            .apply { action = "stop" }

    @Test
    fun `stop before the record loop resets a stuck STARTING state`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        assertTrue(MicrophoneRecordingState.snapshot().isActive)

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create().startCommand(0, 1)

        val snapshot = MicrophoneRecordingState.snapshot()
        assertFalse("state must not stay active after STOP", snapshot.isActive)
        assertEquals(MicrophoneRecordingPhase.ERROR, snapshot.phase)

        controller.destroy()
    }

    @Test
    fun `a new start is possible after the stuck state is reset`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create().startCommand(0, 1)
        controller.destroy()

        // The exact symptom of #98 was this returning false forever.
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markError("test cleanup")
    }

    @Test
    fun `stop while inactive does not overwrite a READY result`() {
        val completed = java.io.File("recording_ready.wav")
        MicrophoneRecordingState.markReady(completed, 42L)

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create().startCommand(0, 1)

        assertEquals(MicrophoneRecordingPhase.READY, MicrophoneRecordingState.snapshot().phase)
        assertEquals("recording_ready.wav", MicrophoneRecordingState.snapshot().activeFileName)

        controller.destroy()
    }

    @Test
    fun `service destroyed without STOP resets an active state`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        // No startCommand: the system destroys the service before the loop.
        controller.destroy()

        val snapshot = MicrophoneRecordingState.snapshot()
        assertFalse(snapshot.isActive)
        assertEquals(MicrophoneRecordingPhase.ERROR, snapshot.phase)
    }

    /**
     * Review F10: `onDestroy` reset *any* active phase, and `isActive` includes
     * FINALIZING. The finalisation block has no suspension points, so a job
     * already inside it ran to `markReady` and overwrote the ERROR — the final
     * state was right, but between `onDestroy`'s `markError` and the job's
     * `markReady` there was a window in which `/mic_status` reported
     * `{"phase":"error","error":"Recorder service was destroyed before
     * completion"}` for a recording that was complete and downloadable
     * (`CommandDispatcher` serves `latest` from disk regardless of phase).
     *
     * `android_mic_stop()` stops the service, so the stop path races the
     * finaliser routinely: the agent's next `android_mic_status()` poll could land
     * in the window, report "recording failed", and never call `android_mic_fetch()`.
     * `docs/manual-tests.md` step 12 ("wait for android_mic_status() to report
     * ready") would hang on that run.
     */
    @Test
    fun `destroy during FINALIZING leaves the phase to the finaliser`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording.wav")
        MicrophoneRecordingState.markRecording(1024L)
        MicrophoneRecordingState.markFinalizing(2048L)
        assertEquals(MicrophoneRecordingPhase.FINALIZING, MicrophoneRecordingState.snapshot().phase)

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        controller.destroy()

        val snapshot = MicrophoneRecordingState.snapshot()
        assertNotEquals(
            "a finalising recording is already complete; onDestroy must not downgrade it",
            MicrophoneRecordingPhase.ERROR,
            snapshot.phase,
        )
        assertEquals(MicrophoneRecordingPhase.FINALIZING, snapshot.phase)

        // The finaliser owns both terminal states and still reaches READY.
        MicrophoneRecordingState.markReady(java.io.File("recording.wav"), 2048L)
        assertEquals(MicrophoneRecordingPhase.READY, MicrophoneRecordingState.snapshot().phase)
        assertEquals("recording.wav", MicrophoneRecordingState.snapshot().activeFileName)
    }

    @Test
    fun `a second STOP while finalising does not publish an error`() {
        // requestStop()'s #98 branch had the same defect: it reset any active
        // phase, and a STOP that arrives while the first one is finalising finds
        // isRecording already false with the phase at FINALIZING.
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording.wav")
        MicrophoneRecordingState.markRecording(1024L)
        MicrophoneRecordingState.markFinalizing(2048L)

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create().startCommand(0, 1)

        assertNotEquals(
            MicrophoneRecordingPhase.ERROR,
            MicrophoneRecordingState.snapshot().phase,
        )
        controller.destroy()
        MicrophoneRecordingState.markReady(java.io.File("recording.wav"), 2048L)
    }

    @Test
    fun `destroy during RECORDING still resets, so #98 stays fixed`() {
        // The other half of F10: narrowing the reset must not reopen the stuck
        // state that #98 closed. RECORDING cannot reach a terminal phase once the
        // service is gone, so it still has to be reset.
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording.wav")
        MicrophoneRecordingState.markRecording(1024L)

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        controller.destroy()

        val snapshot = MicrophoneRecordingState.snapshot()
        assertEquals(MicrophoneRecordingPhase.ERROR, snapshot.phase)
        assertFalse(snapshot.isActive)
        assertTrue("a new recording must be reservable again", MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markError("test cleanup")
    }
}
