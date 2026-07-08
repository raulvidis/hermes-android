package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.event.EventStore
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.executor.ScreenReader
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.model.DeviceCapabilities
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.notification.NotificationStore
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.service.BridgeNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for command handling, shared by both transports:
 * - [RelayClient] — outbound WebSocket relay (the path Hermes drives the device through)
 * - [configureRouting] in BridgeRouter.kt — the local ktor HTTP server on :8765
 *
 * Both transports parse their request into (method, path, params, body) and call [dispatch],
 * so endpoint contracts, device-capability gating, and behaviour live in exactly one place.
 *
 * @param authenticated whether the caller is authenticated. The relay connection is
 * authenticated at connect time (token in the WS URL), so it passes `true`; the HTTP
 * server computes it from the request's Bearer token. Only `/ping` reports it back.
 */
object CommandDispatcher {

    suspend fun dispatch(
        method: String,
        path: String,
        params: JsonObject,
        body: JsonObject,
        authenticated: Boolean
    ): Pair<Any, Int> {
        return when {
            method == "GET" && path == "/ping" -> {
                val serviceRunning = BridgeAccessibilityService.instance != null
                mapOf(
                    "status" to "ok",
                    "accessibilityService" to serviceRunning,
                    "authenticated" to authenticated,
                    "version" to BuildConfig.VERSION_NAME
                ) to 200
            }

            method == "GET" && path == "/screen" -> {
                val bounds = params.get("bounds")?.asString == "true"
                val systemUi = params.get("system_ui")?.asString == "true"
                val tree = withContext(Dispatchers.Main) {
                    ScreenReader.readCurrentScreen(bounds, systemUi)
                }
                mapOf("tree" to tree, "count" to countAllNodes(tree)) to 200
            }

            method == "POST" && path == "/tap" -> {
                val x = body.get("x")?.asInt
                val y = body.get("y")?.asInt
                val nodeId = body.get("nodeId")?.asString
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.tap(x, y, nodeId)
                }
                result to 200
            }

            method == "POST" && path == "/tap_text" -> {
                val text = body.get("text")?.asString ?: ""
                val exact = body.get("exact")?.asBoolean ?: false
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.tapText(text, exact)
                }
                result to 200
            }

            method == "POST" && path == "/type" -> {
                val text = body.get("text")?.asString ?: ""
                val clearFirst = body.get("clearFirst")?.asBoolean ?: false
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.typeText(text, clearFirst)
                }
                result to 200
            }

            method == "POST" && path == "/swipe" -> {
                val direction = body.get("direction")?.asString ?: ""
                val distance = body.get("distance")?.asString ?: "medium"
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.swipe(direction, distance)
                }
                result to 200
            }

            method == "POST" && path == "/open_app" -> {
                val pkg = body.get("package")?.asString
                    ?: return@when mapOf("error" to "Missing package") to 400
                val result = ActionExecutor.openApp(pkg)
                result to 200
            }

            method == "POST" && path == "/press_key" -> {
                val key = body.get("key")?.asString ?: ""
                val result = ActionExecutor.pressKey(key)
                result to 200
            }

            method == "GET" && path == "/screenshot" -> {
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.takeScreenshot()
                }
                result to 200
            }

            method == "POST" && path == "/scroll" -> {
                val direction = body.get("direction")?.asString ?: ""
                val nodeId = body.get("nodeId")?.asString
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.scroll(direction, nodeId)
                }
                result to 200
            }

            method == "POST" && path == "/wait" -> {
                val text = body.get("text")?.asString
                val className = body.get("className")?.asString
                val timeoutMs = body.get("timeoutMs")?.asInt ?: 5000
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.waitForElement(text, className, timeoutMs)
                }
                result to 200
            }

            method == "GET" && path == "/apps" -> {
                val apps = ActionExecutor.getInstalledApps()
                mapOf("apps" to apps, "count" to apps.size) to 200
            }

            method == "GET" && path == "/current_app" -> {
                val result = withContext(Dispatchers.Main) {
                    val service = BridgeAccessibilityService.instance
                    val windows = service?.windows ?: emptyList()
                    val root = windows.firstOrNull()?.root
                    val pkg = root?.packageName?.toString() ?: "unknown"
                    val cls = root?.className?.toString() ?: "unknown"
                    root?.recycle()
                    windows.forEach { it.recycle() }
                    mapOf("package" to pkg, "className" to cls)
                }
                result to 200
            }

            method == "GET" && path == "/clipboard" -> {
                val result = ActionExecutor.clipboardRead()
                result to 200
            }

            method == "POST" && path == "/clipboard" -> {
                val text = body.get("text")?.asString ?: ""
                val result = ActionExecutor.clipboardWrite(text)
                result to 200
            }

            method == "GET" && path == "/notifications" -> {
                val limit = params.get("limit")?.asString?.toIntOrNull() ?: 50
                val since = params.get("since")?.asString?.toLongOrNull() ?: 0L
                val entries = if (since > 0) {
                    NotificationStore.getSince(since, limit)
                } else {
                    NotificationStore.getAll(limit)
                }
                val mapped = entries.map { NotificationStore.toMap(it) }
                val listenerRunning = BridgeNotificationListener.instance != null
                mapOf(
                    "notifications" to mapped,
                    "count" to mapped.size,
                    "listenerActive" to listenerRunning
                ) to 200
            }

            method == "POST" && path == "/long_press" -> {
                val x = body.get("x")?.asInt
                val y = body.get("y")?.asInt
                val nodeId = body.get("nodeId")?.asString
                val duration = body.get("duration")?.asLong ?: 500L
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.longPress(x, y, nodeId, duration)
                }
                result to 200
            }

            method == "POST" && path == "/drag" -> {
                val startX = body.get("startX")?.asInt ?: 0
                val startY = body.get("startY")?.asInt ?: 0
                val endX = body.get("endX")?.asInt ?: 0
                val endY = body.get("endY")?.asInt ?: 0
                val duration = body.get("duration")?.asLong ?: 500L
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.drag(startX, startY, endX, endY, duration)
                }
                result to 200
            }

            method == "POST" && path == "/describe_node" -> {
                val nodeId = body.get("nodeId")?.asString ?: ""
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.describeNode(nodeId)
                }
                result to 200
            }

            method == "POST" && path == "/find_nodes" -> {
                val text = body.get("text")?.asString
                val className = body.get("className")?.asString
                val clickable = body.get("clickable")?.asBoolean
                val limit = body.get("limit")?.asInt ?: 20
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.findNodes(text, className, clickable, limit)
                }
                result to 200
            }

            method == "POST" && path == "/diff_screen" -> {
                val previousHash = body.get("previousHash")?.asString ?: ""
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.diffScreen(previousHash)
                }
                result to 200
            }

            method == "POST" && path == "/pinch" -> {
                val x = body.get("x")?.asInt ?: 0
                val y = body.get("y")?.asInt ?: 0
                val scale = body.get("scale")?.asFloat ?: 1.5f
                val duration = body.get("duration")?.asLong ?: 300L
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.pinch(x, y, scale, duration)
                }
                result to 200
            }

            method == "GET" && path == "/screen_hash" -> {
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.screenHash()
                }
                result to 200
            }

            method == "GET" && path == "/location" -> {
                val result = ActionExecutor.location()
                result to 200
            }

            method == "POST" && path == "/send_sms" -> {
                if (!DeviceCapabilities.hasTelephony) {
                    return@when mapOf("success" to false, "error" to "SMS not available on this device") to 200
                }
                val to = body.get("to")?.asString ?: ""
                val smsBody = body.get("body")?.asString ?: ""
                val result = ActionExecutor.sendSms(to, smsBody)
                result to 200
            }

            method == "POST" && path == "/call" -> {
                if (!DeviceCapabilities.hasTelephony) {
                    return@when mapOf("success" to false, "error" to "Phone calls not available on this device") to 200
                }
                val number = body.get("number")?.asString ?: ""
                val result = ActionExecutor.makeCall(number)
                result to 200
            }

            method == "POST" && path == "/media" -> {
                val action = body.get("action")?.asString ?: ""
                val result = ActionExecutor.mediaControl(action)
                result to 200
            }

            method == "GET" && path == "/events" -> {
                val limit = params.get("limit")?.asString?.toIntOrNull() ?: 50
                val since = params.get("since")?.asString?.toLongOrNull() ?: 0L
                val entries = if (since > 0) {
                    EventStore.getSince(since, limit)
                } else {
                    EventStore.getAll(limit)
                }
                val mapped = entries.map { EventStore.toMap(it) }
                mapOf("events" to mapped, "count" to mapped.size, "streaming" to EventStore.streamingEnabled) to 200
            }

            method == "POST" && path == "/events/stream" -> {
                val enabled = body.get("enabled")?.asBoolean ?: false
                EventStore.setStreaming(enabled)
                mapOf("success" to true, "streaming" to enabled) to 200
            }

            method == "GET" && path == "/contacts" -> {
                if (!DeviceCapabilities.hasTelephony) {
                    return@when mapOf("success" to false, "error" to "Contacts not available on this device") to 200
                }
                val query = params.get("query")?.asString ?: ""
                val limit = params.get("limit")?.asString?.toIntOrNull() ?: 20
                val result = withContext(Dispatchers.IO) {
                    ActionExecutor.searchContacts(query, limit)
                }
                result to 200
            }

            method == "POST" && path == "/intent" -> {
                val action = body.get("action")?.asString ?: ""
                val dataUri = body.get("dataUri")?.asString
                val extrasObj = body.get("extras")?.asJsonObject
                val extras = extrasObj?.let { obj ->
                    val map = mutableMapOf<String, String>()
                    obj.entrySet().forEach { (k, v) -> map[k] = v.asString }
                    map
                }
                val packageOverride = body.get("packageOverride")?.asString
                val result = ActionExecutor.sendIntent(action, dataUri, extras, packageOverride)
                result to 200
            }

            method == "POST" && path == "/broadcast" -> {
                val action = body.get("action")?.asString ?: ""
                val extrasObj = body.get("extras")?.asJsonObject
                val extras = extrasObj?.let { obj ->
                    val map = mutableMapOf<String, String>()
                    obj.entrySet().forEach { (k, v) -> map[k] = v.asString }
                    map
                }
                val result = ActionExecutor.sendBroadcast(action, extras)
                result to 200
            }

            method == "POST" && path == "/speak" -> {
                val text = body.get("text")?.asString ?: ""
                val queue = body.get("queue")?.asInt ?: 1
                val result = ActionExecutor.speak(text, queue)
                result to 200
            }

            method == "POST" && path == "/stop_speaking" -> {
                val result = ActionExecutor.stopSpeaking()
                result to 200
            }

            method == "POST" && path == "/screen_record" -> {
                val durationMs = body.get("durationMs")?.asLong?.coerceAtMost(30_000L) ?: 5000L
                val result = withContext(Dispatchers.IO) {
                    ScreenRecorder.record(durationMs)
                }
                result to 200
            }

            method == "GET" && path == "/widgets" -> {
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.readWidgets()
                }
                result to 200
            }

            // ── Shell (root) command execution ──────────────────────────────

            method == "POST" && path == "/shell" -> {
                val command = body.get("command")?.asString ?: ""
                if (command.isEmpty()) {
                    mapOf("error" to "Missing 'command' field") to 400
                } else {
                    val result = withContext(Dispatchers.IO) {
                        ActionExecutor.executeShell(command)
                    }
                    result to 200
                }
            }

            else -> {
                mapOf("error" to "Unknown command: $method $path") to 404
            }
        }
    }

    private fun countAllNodes(nodes: List<ScreenNode>): Int {
        var count = 0
        for (node in nodes) {
            count += 1 + countAllNodes(node.children)
        }
        return count
    }
}
