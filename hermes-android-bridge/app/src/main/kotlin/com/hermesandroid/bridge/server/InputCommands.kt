package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.hermesandroid.bridge.executor.ActionExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Touch and typing endpoints: POST /tap, /tap_text, /type, /swipe, /scroll,
 * /long_press, /drag, /pinch and /press_key, plus clipboard read
 * (GET /clipboard) and write (POST /clipboard).
 *
 * Implementation detail of [CommandDispatcher] — the (method, path) routes are
 * declared in its table; these handlers hold the endpoint behaviour.
 */
internal object InputCommands {

    suspend fun tap(body: JsonObject): Pair<Any, Int> {
        val x = body.get("x")?.asInt
        val y = body.get("y")?.asInt
        val nodeId = body.get("nodeId")?.asString
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.tap(x, y, nodeId)
        }
        return result to 200
    }

    suspend fun tapText(body: JsonObject): Pair<Any, Int> {
        val text = body.get("text")?.asString ?: ""
        val exact = body.get("exact")?.asBoolean ?: false
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.tapText(text, exact)
        }
        return result to 200
    }

    suspend fun type(body: JsonObject): Pair<Any, Int> {
        val text = body.get("text")?.asString ?: ""
        val clearFirst = body.get("clearFirst")?.asBoolean ?: false
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.typeText(text, clearFirst)
        }
        return result to 200
    }

    suspend fun swipe(body: JsonObject): Pair<Any, Int> {
        val direction = body.get("direction")?.asString ?: ""
        val distance = body.get("distance")?.asString ?: "medium"
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.swipe(direction, distance)
        }
        return result to 200
    }

    suspend fun pressKey(body: JsonObject): Pair<Any, Int> {
        val key = body.get("key")?.asString ?: ""
        val result = ActionExecutor.pressKey(key)
        return result to 200
    }

    suspend fun scroll(body: JsonObject): Pair<Any, Int> {
        val direction = body.get("direction")?.asString ?: ""
        val nodeId = body.get("nodeId")?.asString
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.scroll(direction, nodeId)
        }
        return result to 200
    }

    suspend fun clipboardRead(): Pair<Any, Int> {
        val result = ActionExecutor.clipboardRead()
        return result to 200
    }

    suspend fun clipboardWrite(body: JsonObject): Pair<Any, Int> {
        val text = body.get("text")?.asString ?: ""
        val result = ActionExecutor.clipboardWrite(text)
        return result to 200
    }

    suspend fun longPress(body: JsonObject): Pair<Any, Int> {
        val x = body.get("x")?.asInt
        val y = body.get("y")?.asInt
        val nodeId = body.get("nodeId")?.asString
        val duration = body.get("duration")?.asLong ?: 500L
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.longPress(x, y, nodeId, duration)
        }
        return result to 200
    }

    suspend fun drag(body: JsonObject): Pair<Any, Int> {
        val startX = body.get("startX")?.asInt ?: 0
        val startY = body.get("startY")?.asInt ?: 0
        val endX = body.get("endX")?.asInt ?: 0
        val endY = body.get("endY")?.asInt ?: 0
        val duration = body.get("duration")?.asLong ?: 500L
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.drag(startX, startY, endX, endY, duration)
        }
        return result to 200
    }

    suspend fun pinch(body: JsonObject): Pair<Any, Int> {
        val x = body.get("x")?.asInt ?: 0
        val y = body.get("y")?.asInt ?: 0
        val scale = body.get("scale")?.asFloat ?: 1.5f
        val duration = body.get("duration")?.asLong ?: 300L
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.pinch(x, y, scale, duration)
        }
        return result to 200
    }
}
