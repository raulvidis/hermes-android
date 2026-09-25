package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.hermesandroid.bridge.executor.ActionExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract tests for POST /pinch scale validation.
 *
 * A scale of 1 dispatches a gesture whose fingers never move, and a scale
 * <= 0 or non-finite builds a nonsensical path. Those are caller errors and
 * must come back as 400 before anything reaches the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommandDispatcherPinchTest {

    private suspend fun pinch(body: JsonObject): Pair<Any, Int> =
        CommandDispatcher.dispatch("POST", "/pinch", JsonObject(), body, true)

    @Suppress("UNCHECKED_CAST")
    private fun error(raw: Any): String? = (raw as Map<String, Any?>)["error"] as String?

    private fun bodyWithScale(scale: Number): JsonObject = JsonObject().apply {
        addProperty("x", 500)
        addProperty("y", 500)
        addProperty("scale", scale)
    }

    @Test
    fun `scale of zero is rejected with 400`() = runTest {
        val (raw, status) = pinch(bodyWithScale(0))
        assertEquals(400, status)
        assertTrue(error(raw)!!.contains("greater than 0"))
    }

    @Test
    fun `negative scale is rejected with 400`() = runTest {
        val (_, status) = pinch(bodyWithScale(-2.0))
        assertEquals(400, status)
    }

    @Test
    fun `scale of exactly one is rejected with 400`() = runTest {
        val (raw, status) = pinch(bodyWithScale(1.0))
        assertEquals(400, status)
        assertTrue(error(raw)!!.contains("must not be 1"))
    }

    @Test
    fun `non-finite scale is rejected with 400`() = runTest {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val (raw, status) = pinch(bodyWithScale(bad))
            assertEquals("scale=$bad", 400, status)
            assertNotNull(error(raw))
        }
    }

    @Test
    fun `non-numeric scale is rejected with 400`() = runTest {
        val body = JsonObject().apply { addProperty("scale", "big") }
        val (raw, status) = pinch(body)
        assertEquals(400, status)
        assertEquals("scale must be a number", error(raw))
    }

    @Test
    fun `pinchScaleError accepts zoom in and zoom out factors`() {
        assertNull(ActionExecutor.pinchScaleError(1.5f))
        assertNull(ActionExecutor.pinchScaleError(0.5f))
    }
}
