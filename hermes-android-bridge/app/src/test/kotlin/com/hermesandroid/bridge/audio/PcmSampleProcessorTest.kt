package com.hermesandroid.bridge.audio

import org.junit.Assert.assertArrayEquals
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
}
