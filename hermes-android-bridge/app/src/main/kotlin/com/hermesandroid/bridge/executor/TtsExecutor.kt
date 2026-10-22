package com.hermesandroid.bridge.executor

import android.content.Context
import android.speech.tts.TextToSpeech
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Text-to-speech: lazily initialises a TextToSpeech instance bound to the
 * application context and keeps the ready state for the speak/stop/shutdown
 * lifecycle.
 *
 * Implementation detail of [ActionExecutor] — callers go through the facade
 * (the accessibility service calls shutdownTts via the facade on
 * onInterrupt/onDestroy).
 */
internal object TtsExecutor {

    @Volatile private var tts: TextToSpeech? = null

    @Volatile private var ttsReady = false

    /** Wall-clock time of the last failed init, or 0 if the last init did not fail. */
    @Volatile private var ttsFailedAt = 0L

    /** Serialises ensureTts so concurrent /speak calls cannot each create an engine. */
    private val ttsMutex = Mutex()

    private const val TTS_INIT_TIMEOUT_MS = 10_000L

    /**
     * After a failed init, don't try again for this long. On a device with no
     * TTS engine every attempt fails, and without a backoff each /speak call
     * would bind a fresh TextToSpeech instance.
     */
    internal const val TTS_RETRY_BACKOFF_MS = 60_000L

    /** Test seam: builds the engine. Production never assigns it. */
    @Volatile internal var ttsFactory: (Context, TextToSpeech.OnInitListener) -> TextToSpeech =
        { context, listener -> TextToSpeech(context, listener) }

    /** Test seam: the clock the retry backoff is measured on. */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    private suspend fun ensureTts(): Boolean = ttsMutex.withLock {
        if (tts != null && ttsReady) return@withLock true
        val failedAt = ttsFailedAt
        if (failedAt != 0L && clock() - failedAt < TTS_RETRY_BACKOFF_MS) {
            return@withLock false
        }
        val service = BridgeAccessibilityService.instance ?: return@withLock false

        // Discard any instance left over from an earlier, unfinished init.
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false

        var created: TextToSpeech? = null
        val ready = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                created = ttsFactory(service.applicationContext, TextToSpeech.OnInitListener { status ->
                    // May fire after the timeout has already cancelled us, or
                    // synchronously from inside the constructor on failure.
                    if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS)
                })
            }
        } ?: false

        val instance = created
        if (ready && instance != null) {
            tts = instance
            ttsReady = true
            ttsFailedAt = 0L
            true
        } else {
            // A failed (or timed-out) engine still holds a service binding.
            try { instance?.shutdown() } catch (_: Exception) {}
            ttsFailedAt = clock()
            false
        }
    }

    suspend fun speak(text: String, queue: Int = TextToSpeech.QUEUE_ADD): ActionResult {
        if (!ensureTts()) {
            return ActionResult(false, "TTS not available")
        }
        tts?.speak(text, queue, null, "hermes_speak_${System.currentTimeMillis()}")
        return ActionResult(true, "Speaking: ${text.take(50)}")
    }

    fun stopSpeaking(): ActionResult {
        tts?.stop()
        return ActionResult(true, "Stopped speaking")
    }

    fun shutdownTts() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        ttsFailedAt = 0L
    }
}
