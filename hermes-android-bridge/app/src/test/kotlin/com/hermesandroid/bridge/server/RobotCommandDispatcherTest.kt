package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.hermesandroid.bridge.robot.RobotBackend
import com.hermesandroid.bridge.robot.RobotPhase
import com.hermesandroid.bridge.robot.RobotScreenState
import com.hermesandroid.bridge.robot.RobotUiController
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotCommandDispatcherTest {
    @After
    fun resetRobotState() {
        RobotUiController.update(
            RobotPhase.IDLE,
            RobotScreenState.DEFAULT_CAPTION,
            RobotBackend.HERMES_LOCAL,
        )
        RobotUiController.markVisible(false)
    }

    @Test
    fun `robot state endpoint updates allow listed phase without echoing caption`() = runTest {
        val body = JsonObject().apply {
            addProperty("phase", "speaking")
            addProperty("caption", "A private spoken answer")
            addProperty("backend", "gpt_live")
            addProperty("show", false)
        }

        val (rawResult, status) = CommandDispatcher.dispatch(
            "POST",
            "/robot_state",
            JsonObject(),
            body,
            authenticated = true,
        )

        @Suppress("UNCHECKED_CAST")
        val result = rawResult as Map<String, Any>
        assertEquals(200, status)
        assertEquals("speaking", result["phase"])
        assertEquals("gpt_live", result["backend"])
        assertFalse(result.values.contains("A private spoken answer"))
        assertEquals("A private spoken answer", RobotUiController.state().caption)
        assertEquals(RobotBackend.GPT_LIVE, RobotUiController.state().backend)
    }

    @Test
    fun `robot state endpoint rejects arbitrary phase`() = runTest {
        val body = JsonObject().apply { addProperty("phase", "run-device-command") }
        val (rawResult, status) = CommandDispatcher.dispatch(
            "POST",
            "/robot_state",
            JsonObject(),
            body,
            authenticated = true,
        )

        @Suppress("UNCHECKED_CAST")
        val result = rawResult as Map<String, Any>
        assertEquals(400, status)
        assertTrue((result["error"] as String).contains("phase"))
    }

    @Test
    fun `robot state endpoint rejects structured phase without crashing`() = runTest {
        val body = JsonObject().apply { add("phase", JsonObject()) }

        val (rawResult, status) = CommandDispatcher.dispatch(
            "POST",
            "/robot_state",
            JsonObject(),
            body,
            authenticated = true,
        )

        @Suppress("UNCHECKED_CAST")
        val result = rawResult as Map<String, Any>
        assertEquals(400, status)
        assertTrue((result["error"] as String).contains("phase"))
    }

    @Test
    fun `robot state endpoint rejects arbitrary backend`() = runTest {
        val body = JsonObject().apply {
            addProperty("phase", "ready")
            addProperty("backend", "device-control")
        }

        val (rawResult, status) = CommandDispatcher.dispatch(
            "POST",
            "/robot_state",
            JsonObject(),
            body,
            authenticated = true,
        )

        @Suppress("UNCHECKED_CAST")
        val result = rawResult as Map<String, Any>
        assertEquals(400, status)
        assertTrue((result["error"] as String).contains("backend"))
    }

    @Test
    fun `robot status exposes backend without exposing caption`() = runTest {
        RobotUiController.update(RobotPhase.READY, "Private caption", RobotBackend.HERMES_STANDARD)

        val (rawResult, status) = CommandDispatcher.dispatch(
            "GET",
            "/robot_status",
            JsonObject(),
            JsonObject(),
            authenticated = true,
        )

        @Suppress("UNCHECKED_CAST")
        val result = rawResult as Map<String, Any>
        assertEquals(200, status)
        assertEquals("hermes_standard", result["backend"])
        assertFalse(result.values.contains("Private caption"))
    }

    @Test
    fun `noise watcher rejects an unsafe clip duration before accessing the device`() = runTest {
        val body = JsonObject().apply {
            addProperty("clipSeconds", 31)
        }

        val (rawResult, status) = CommandDispatcher.dispatch(
            "POST",
            "/noise_watch_start",
            JsonObject(),
            body,
            authenticated = true,
        )

        @Suppress("UNCHECKED_CAST")
        val result = rawResult as Map<String, Any>
        assertEquals(400, status)
        assertTrue((result["error"] as String).contains("clipSeconds"))
    }
}
