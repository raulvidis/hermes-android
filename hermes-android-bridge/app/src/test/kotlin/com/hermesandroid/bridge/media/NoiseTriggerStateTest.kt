package com.hermesandroid.bridge.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseTriggerStateTest {
    @After
    fun reset() {
        NoiseTriggerState.stopped()
    }

    @Test
    fun `dialog pause keeps watcher preference and settings`() {
        NoiseTriggerState.started(900.0, 8, 45)

        NoiseTriggerState.pauseRequestedForDialog()
        assertTrue(NoiseTriggerState.snapshot().pausingForDialog)

        NoiseTriggerState.pausedForDialog()
        val paused = NoiseTriggerState.snapshot()
        assertTrue(paused.active)
        assertTrue(paused.pausedForDialog)
        assertFalse(paused.pausingForDialog)
        assertEquals(900.0, paused.thresholdRms, 0.0)
        assertEquals(8, paused.clipSeconds)
        assertEquals(45, paused.cooldownSeconds)

        NoiseTriggerState.resumedAfterDialog()
        val resumed = NoiseTriggerState.snapshot()
        assertTrue(resumed.active)
        assertFalse(resumed.pausedForDialog)
        assertFalse(resumed.pausingForDialog)
    }
}
