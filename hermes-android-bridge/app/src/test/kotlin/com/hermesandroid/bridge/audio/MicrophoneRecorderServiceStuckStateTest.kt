package com.hermesandroid.bridge.audio

import android.content.Intent
import android.media.AudioRecord
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @After
    fun resetState() {
        // A test that fails mid-way would otherwise leave the state machine in an
        // active phase, and the next test's tryReserveStart() would fail for
        // somebody else's reason.
        MicrophoneRecordingState.markError("test reset")
        unmockkAll()
    }

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

    // ── Review F16: F10's exemption assumed the finaliser always runs ──────────

    /**
     * F10 left FINALIZING to "the job that owns it, which has no suspension
     * points left to run and will reach markReady/markError on its own". That is
     * true of a job whose body has started. A coroutine cancelled *before* it
     * starts runs nothing at all — not the finalisation block, not its catch, not
     * its finally — so the phase stays FINALIZING forever: `isActive` is true,
     * `tryReserveStart()` returns false, and every `/mic_start` answers 409 until
     * the app restarts. That is #98 again, one phase later.
     *
     * Production reaches it with a STOP and a service destroy inside one dispatch
     * quantum: `requestStop()` publishes FINALIZING from the main thread while the
     * record loop is still queued on `Dispatchers.IO`, then `onDestroy` cancels it.
     */
    @Test
    fun `destroy settles a FINALIZING phase whose job never ran`() {
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
        // This instance does own a recording, and its job can never run.
        controller.get().ownedFileName = "recording.wav"
        controller.get().recordingJob = unrunnableJob()

        controller.destroy()

        val snapshot = MicrophoneRecordingState.snapshot()
        assertEquals(MicrophoneRecordingPhase.ERROR, snapshot.phase)
        assertFalse("a stuck FINALIZING keeps every /mic_start at 409", snapshot.isActive)
        assertTrue("a new recording must be reservable again", MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markError("test cleanup")
    }

    @Test
    fun `destroy does not downgrade a recording whose job is still finalising`() {
        // The F10 property F16 must not break: while the finaliser can still reach
        // markReady, onDestroy publishes nothing.
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording.wav")
        MicrophoneRecordingState.markRecording(1024L)
        MicrophoneRecordingState.markFinalizing(2048L)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val job = scope.launch {
            started.countDown()
            // Blocking, not suspending — the shape of the real finalisation block,
            // and the reason cancel() cannot interrupt it.
            gate.await(10, TimeUnit.SECONDS)
            MicrophoneRecordingState.markReady(java.io.File("recording.wav"), 2048L)
        }
        assertTrue("the finaliser must be running before destroy", started.await(5, TimeUnit.SECONDS))

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        controller.get().ownedFileName = "recording.wav"
        controller.get().recordingJob = job

        controller.destroy()

        assertEquals(
            "the finaliser is still running; onDestroy must not publish an error",
            MicrophoneRecordingPhase.FINALIZING,
            MicrophoneRecordingState.snapshot().phase,
        )

        // Registered after the production handler, so it runs after it.
        val settled = CountDownLatch(1)
        job.invokeOnCompletion { settled.countDown() }
        gate.countDown()
        assertTrue(settled.await(5, TimeUnit.SECONDS))

        assertEquals(
            "a recording the finaliser completed must not be downgraded afterwards",
            MicrophoneRecordingPhase.READY,
            MicrophoneRecordingState.snapshot().phase,
        )
        scope.cancel()
    }

    @Test
    fun `destroy leaves a FINALIZING phase it does not own alone`() {
        // No recording job means this instance never started a recording, so the
        // phase belongs to somebody else and F10's exemption still applies.
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording.wav")
        MicrophoneRecordingState.markFinalizing(2048L)

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        controller.destroy()

        assertEquals(MicrophoneRecordingPhase.FINALIZING, MicrophoneRecordingState.snapshot().phase)
        MicrophoneRecordingState.markReady(java.io.File("recording.wav"), 2048L)
    }

    /** A job whose body can never run: the scope is cancelled before it dispatches. */
    private fun unrunnableJob(): Job {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel()
        var ran = false
        val job = scope.launch { ran = true }
        assertFalse("the job body must not have run", ran)
        return job
    }

    // ── Review F22: the abandoned job leaks the AudioRecord, not just the phase ──

    /**
     * F16 settled the *phase* for a job cancelled before its body started, but the
     * same job also owns the `AudioRecord` release: it happens in the record loop's
     * `finally`, and a coroutine whose body never starts runs no finally. `onDestroy`
     * only calls `safelyStop`, which stops capture without releasing, and its
     * unconditional release is guarded by `recordingJob == null` — which is exactly
     * the case this is not. The native recorder stayed held for the life of the
     * process: the microphone-in-use indicator never cleared and the next
     * `/mic_start` could fail to initialise the microphone.
     */
    @Test
    fun `an abandoned job's recorder is released on destroy`() {
        val audioRecord = mockk<AudioRecord>(relaxed = true)

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        controller.get().recorder = audioRecord
        controller.get().ownedFileName = "recording.wav"
        controller.get().recordingJob = unrunnableJob()

        controller.destroy()

        verify(exactly = 1) { audioRecord.release() }
        assertNull("the field must not outlive the release", controller.get().recorder)
    }

    @Test
    fun `the release happens whatever phase the abandoned job left behind`() {
        // The leak is not tied to FINALIZING: a STARTING phase is reset by
        // onDestroy's own branch, and the recorder was still held either way.
        val audioRecord = mockk<AudioRecord>(relaxed = true)
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markStarting("recording.wav")

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        controller.get().recorder = audioRecord
        controller.get().ownedFileName = "recording.wav"
        controller.get().recordingJob = unrunnableJob()

        controller.destroy()

        verify(exactly = 1) { audioRecord.release() }
        assertEquals(MicrophoneRecordingPhase.ERROR, MicrophoneRecordingState.snapshot().phase)
    }

    @Test
    fun `a still-running finaliser keeps ownership of the recorder`() {
        // The other half: releasing eagerly would pull the microphone out from
        // under a record loop that is still writing to it. A bare `Job()` will not
        // model this — cancelling it completes it instantly — so this is a real
        // coroutine blocked on a latch, which is what a loop inside a blocking
        // `AudioRecord.read` looks like to `cancel()`: uninterruptible.
        val audioRecord = mockk<AudioRecord>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val job = scope.launch {
            started.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        assertTrue("the loop must be running before destroy", started.await(5, TimeUnit.SECONDS))

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        controller.get().recorder = audioRecord
        controller.get().ownedFileName = "recording.wav"
        controller.get().recordingJob = job

        controller.destroy()

        verify(exactly = 0) { audioRecord.release() }

        // Registered after the production handler, so it runs after it. Once the
        // job is done, anything still held is released — in production the loop's
        // own finally has already nulled the field, so the handler is the no-op.
        val settled = CountDownLatch(1)
        job.invokeOnCompletion { settled.countDown() }
        gate.countDown()
        assertTrue(settled.await(5, TimeUnit.SECONDS))
        verify(exactly = 1) { audioRecord.release() }
        scope.cancel()
    }

    @Test
    fun `destroy with no recorder held releases nothing`() {
        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        controller.get().recordingJob = unrunnableJob()

        controller.destroy()

        assertNull(controller.get().recorder)
    }
}
