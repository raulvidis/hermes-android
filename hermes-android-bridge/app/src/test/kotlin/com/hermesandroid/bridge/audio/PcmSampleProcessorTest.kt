package com.hermesandroid.bridge.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmSampleProcessorTest {
    @Test
    fun `recording gain amplifies and saturates without touching unread samples`() {
        val samples = shortArrayOf(1_000, -1_000, 20_000, -20_000, 0, 123)

        PcmSampleProcessor.applyRecordingGain(samples, count = 5)

        assertArrayEquals(
            shortArrayOf(2_500, -2_500, Short.MAX_VALUE, Short.MIN_VALUE, 0, 123),
            samples,
        )
    }

    @Test
    fun `peak amplitude is zero only for all-zero audio and ignores unread samples`() {
        assertEquals(0, PcmSampleProcessor.peakAmplitude(shortArrayOf(0, 0, 0, 999), count = 3))
        assertEquals(1, PcmSampleProcessor.peakAmplitude(shortArrayOf(0, -1, 0), count = 3))
        assertEquals(32_768, PcmSampleProcessor.peakAmplitude(shortArrayOf(5, Short.MIN_VALUE), count = 2))
        assertEquals(0, PcmSampleProcessor.peakAmplitude(shortArrayOf(7), count = 0))
    }
}
