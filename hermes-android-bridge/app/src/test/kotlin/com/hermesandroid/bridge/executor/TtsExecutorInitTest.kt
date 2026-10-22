package com.hermesandroid.bridge.executor

import android.content.Context
import android.speech.tts.TextToSpeech
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TextToSpeech init lifecycle, through the [TtsExecutor.ttsFactory] and
 * [TtsExecutor.clock] seams.
 *
 * ensureTts() used to check and create the engine without any locking, so
 * concurrent /speak calls each created their own TextToSpeech; a failed init
 * was never shut down (its service binding leaked), a device with no engine
 * bound a fresh one on every call, and an init whose callback never fired hung
 * the request forever.
 *
 * No Robolectric runner: TextToSpeech is mocked, which needs the plain
 * (AGP mockable) android.jar — same reason as WakeLockManagerAcquireTest.
 */
class TtsExecutorInitTest {

    private var now = 1_000_000L
    private val created = mutableListOf<TextToSpeech>()
    private val listeners = mutableListOf<TextToSpeech.OnInitListener>()

    /** What a freshly built engine reports: a status, or null for "never calls back". */
    private var initStatus: Int? = TextToSpeech.SUCCESS

    @Before
    fun setup() {
        val service = mockk<BridgeAccessibilityService>(relaxed = true)
        every { service.applicationContext } returns mockk<Context>(relaxed = true)
        mockkObject(BridgeAccessibilityService.Companion)
        every { BridgeAccessibilityService.instance } returns service

        TtsExecutor.shutdownTts()
        TtsExecutor.clock = { now }
        TtsExecutor.ttsFactory = { _, listener ->
            val engine = mockk<TextToSpeech>(relaxed = true)
            created.add(engine)
            listeners.add(listener)
            // Like the real constructor on a device without an engine, a
            // failure is reported synchronously, from inside the factory.
            initStatus?.let { listener.onInit(it) }
            engine
        }
    }

    @After
    fun tearDown() {
        TtsExecutor.shutdownTts()
        TtsExecutor.ttsFactory = { context, listener -> TextToSpeech(context, listener) }
        TtsExecutor.clock = { System.currentTimeMillis() }
        unmockkAll()
    }

    @Test
    fun `a ready engine is reused`() = runTest {
        assertTrue(TtsExecutor.speak("one").success)
        assertTrue(TtsExecutor.speak("two").success)

        assertEquals(1, created.size)
        verify(exactly = 2) { created[0].speak(any<CharSequence>(), any(), any<android.os.Bundle>(), any<String>()) }
    }

    @Test
    fun `concurrent speak calls share one engine`() = runTest {
        initStatus = null // hold the init open until both calls are waiting

        val first = async { TtsExecutor.speak("one") }
        val second = async { TtsExecutor.speak("two") }
        runCurrent()
        assertEquals("only one engine may be under construction", 1, created.size)

        listeners.single().onInit(TextToSpeech.SUCCESS)

        assertTrue(first.await().success)
        assertTrue(second.await().success)
        assertEquals(1, created.size)
    }

    @Test
    fun `a failed init is shut down and not retried within the backoff`() = runTest {
        initStatus = TextToSpeech.ERROR

        val result = TtsExecutor.speak("hello")

        assertFalse(result.success)
        assertEquals("TTS not available", result.message)
        verify(exactly = 1) { created.single().shutdown() }

        now += TtsExecutor.TTS_RETRY_BACKOFF_MS - 1
        assertFalse(TtsExecutor.speak("hello").success)
        assertEquals("no new engine inside the backoff", 1, created.size)

        now += 1
        initStatus = TextToSpeech.SUCCESS
        assertTrue(TtsExecutor.speak("hello").success)
        assertEquals(2, created.size)
    }

    @Test
    fun `an init that never calls back times out and is shut down`() = runTest {
        initStatus = null

        val result = TtsExecutor.speak("hello")

        assertFalse(result.success)
        verify(exactly = 1) { created.single().shutdown() }
        // A late callback after the timeout must not throw or revive the engine.
        listeners.single().onInit(TextToSpeech.SUCCESS)
        assertEquals(1, created.size)
    }

    @Test
    fun `shutdownTts clears the backoff`() = runTest {
        initStatus = TextToSpeech.ERROR
        assertFalse(TtsExecutor.speak("hello").success)

        TtsExecutor.shutdownTts()
        initStatus = TextToSpeech.SUCCESS

        assertTrue(TtsExecutor.speak("hello").success)
        assertEquals(2, created.size)
    }
}
