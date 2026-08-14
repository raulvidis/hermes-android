package com.hermesandroid.bridge.robot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RobotUiControllerTest {
    @After
    fun resetState() {
        RobotUiController.update(
            RobotPhase.IDLE,
            RobotScreenState.DEFAULT_CAPTION,
            RobotBackend.HERMES_LOCAL,
        )
        RobotUiController.markVisible(false)
    }

    @Test
    fun `wire phases are strictly allow listed`() {
        assertEquals(RobotPhase.LISTENING, RobotPhase.fromWire("LISTENING"))
        assertEquals(RobotPhase.READY, RobotPhase.fromWire(" ready "))
        assertNull(RobotPhase.fromWire("recording-secret-content"))
    }

    @Test
    fun `wire backends are strictly allow listed`() {
        assertEquals(RobotBackend.GPT_LIVE, RobotBackend.fromWire(" GPT_LIVE "))
        assertEquals(RobotBackend.HERMES_LOCAL, RobotBackend.fromWire("hermes_local"))
        assertEquals(RobotBackend.HERMES_STANDARD, RobotBackend.fromWire("hermes_standard"))
        assertEquals(RobotBackend.HERMES_LOCAL, RobotBackend.fromWire("local"))
        assertEquals(RobotBackend.GPT_LIVE, RobotBackend.fromWire("openai"))
        assertNull(RobotBackend.fromWire("send-transcript"))
    }

    @Test
    fun `caption is bounded and never persisted outside memory state`() {
        val updated = RobotUiController.update(RobotPhase.SPEAKING, "x".repeat(900))
        assertEquals(RobotPhase.SPEAKING, updated.phase)
        assertEquals(600, updated.caption.length)
        assertEquals(updated, RobotUiController.state())
    }

    @Test
    fun `blank caption gets phase-specific fallback`() {
        val updated = RobotUiController.update(RobotPhase.THINKING, "   ")
        assertEquals("Einen Moment, ich denke nach …", updated.caption)
    }

    @Test
    fun `state update preserves backend unless an allow listed backend is supplied`() {
        RobotUiController.update(RobotPhase.READY, "GPT Live bereit", RobotBackend.GPT_LIVE)

        val updated = RobotUiController.update(RobotPhase.LISTENING, null)

        assertEquals(RobotBackend.GPT_LIVE, updated.backend)
    }
}
