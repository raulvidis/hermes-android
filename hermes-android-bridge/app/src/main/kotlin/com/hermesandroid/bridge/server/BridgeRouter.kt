package com.hermesandroid.bridge.server

import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.executor.ScreenReader
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.event.EventStore
import com.hermesandroid.bridge.notification.NotificationStore
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.BuildConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Application.configureRouting() {
    routing {
        // Auth interceptor is in BridgeServer.kt — no need to duplicate here

        get("/ping") {
            val serviceRunning = BridgeAccessibilityService.instance != null
            val authHeader = call.request.header(HttpHeaders.Authorization)
            val authenticated = PairingManager.validateToken(authHeader)
            call.respond(mapOf(
                "status" to "ok",
                "accessibilityService" to serviceRunning,
                "authenticated" to authenticated,
                "version" to BuildConfig.VERSION_NAME
            ))
        }

        get("/screen") {
            val bounds = call.request.queryParameters["bounds"] == "true"
            val tree = withContext(Dispatchers.Main) {
                ScreenReader.readCurrentScreen(bounds)
            }
            call.respond(mapOf("tree" to tree, "count" to countNodes(tree)))
        }

        post("/tap") {
            data class TapRequest(val x: Int? = null, val y: Int? = null, val nodeId: String? = null)
            val req = call.receive<TapRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.tap(req.x, req.y, req.nodeId)
            }
            call.respond(result)
        }

        post("/tap_text") {
            data class TapTextRequest(val text: String, val exact: Boolean = false)
            val req = call.receive<TapTextRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.tapText(req.text, req.exact)
            }
            call.respond(result)
        }

        post("/type") {
            data class TypeRequest(val text: String, val clearFirst: Boolean = false)
            val req = call.receive<TypeRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.typeText(req.text, req.clearFirst)
            }
            call.respond(result)
        }

        post("/swipe") {
            data class SwipeRequest(val direction: String, val distance: String = "medium")
            val req = call.receive<SwipeRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.swipe(req.direction, req.distance)
            }
            call.respond(result)
        }

        post("/open_app") {
            data class OpenAppRequest(val packageName: String)
            val req = call.receive<OpenAppRequest>()
            val result = ActionExecutor.openApp(req.packageName)
            call.respond(result)
        }

        post("/press_key") {
            data class PressKeyRequest(val key: String)
            val req = call.receive<PressKeyRequest>()
            val result = ActionExecutor.pressKey(req.key)
            call.respond(result)
        }

        get("/screenshot") {
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.takeScreenshot()
            }
            call.respond(result)
        }

        post("/scroll") {
            data class ScrollRequest(val direction: String, val nodeId: String? = null)
            val req = call.receive<ScrollRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.scroll(req.direction, req.nodeId)
            }
            call.respond(result)
        }

        post("/wait") {
            data class WaitRequest(
                val text: String? = null,
                val className: String? = null,
                val timeoutMs: Int = 5000
            )
            val req = call.receive<WaitRequest>()
            val result = ActionExecutor.waitForElement(req.text, req.className, req.timeoutMs)
            call.respond(result)
        }

        get("/apps") {
            val apps = withContext(Dispatchers.Main) {
                ActionExecutor.getInstalledApps()
            }
            call.respond(mapOf("apps" to apps, "count" to apps.size))
        }

        get("/current_app") {
            val result = withContext(Dispatchers.Main) {
                val service = BridgeAccessibilityService.instance
                val root = service?.windows?.firstOrNull()?.root
                val pkg = root?.packageName?.toString() ?: "unknown"
                val cls = root?.className?.toString() ?: "unknown"
                root?.recycle()
                mapOf("package" to pkg, "className" to cls)
            }
            call.respond(result)
        }

        get("/clipboard") {
            val result = ActionExecutor.clipboardRead()
            call.respond(result)
        }

        post("/clipboard") {
            data class ClipboardWriteRequest(val text: String)
            val req = call.receive<ClipboardWriteRequest>()
            val result = ActionExecutor.clipboardWrite(req.text)
            call.respond(result)
        }

        get("/notifications") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val entries = if (since > 0) {
                NotificationStore.getSince(since, limit)
            } else {
                NotificationStore.getAll(limit)
            }
            val mapped = entries.map { NotificationStore.toMap(it) }
            val listenerRunning = com.hermesandroid.bridge.service.BridgeNotificationListener.instance != null
            call.respond(mapOf(
                "notifications" to mapped,
                "count" to mapped.size,
                "listenerActive" to listenerRunning
            ))
        }

        post("/long_press") {
            data class LongPressRequest(val x: Int? = null, val y: Int? = null, val nodeId: String? = null, val duration: Long = 500)
            val req = call.receive<LongPressRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.longPress(req.x, req.y, req.nodeId, req.duration)
            }
            call.respond(result)
        }

        post("/drag") {
            data class DragRequest(val startX: Int, val startY: Int, val endX: Int, val endY: Int, val duration: Long = 500)
            val req = call.receive<DragRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.drag(req.startX, req.startY, req.endX, req.endY, req.duration)
            }
            call.respond(result)
        }

        post("/describe_node") {
            data class DescribeNodeRequest(val nodeId: String)
            val req = call.receive<DescribeNodeRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.describeNode(req.nodeId)
            }
            call.respond(result)
        }

        post("/find_nodes") {
            data class FindNodesRequest(val text: String? = null, val className: String? = null, val clickable: Boolean? = null, val limit: Int = 20)
            val req = call.receive<FindNodesRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.findNodes(req.text, req.className, req.clickable, req.limit)
            }
            call.respond(result)
        }

        post("/diff_screen") {
            data class DiffRequest(val previousHash: String)
            val req = call.receive<DiffRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.diffScreen(req.previousHash)
            }
            call.respond(result)
        }

        post("/pinch") {
            data class PinchRequest(val x: Int, val y: Int, val scale: Float = 1.5f, val duration: Long = 300)
            val req = call.receive<PinchRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.pinch(req.x, req.y, req.scale, req.duration)
            }
            call.respond(result)
        }

        get("/screen_hash") {
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.screenHash()
            }
            call.respond(result)
        }

        get("/location") {
            val result = ActionExecutor.location()
            call.respond(result)
        }

        post("/send_sms") {
            data class SmsRequest(val to: String, val body: String)
            val req = call.receive<SmsRequest>()
            val result = ActionExecutor.sendSms(req.to, req.body)
            call.respond(result)
        }

        post("/call") {
            data class CallRequest(val number: String)
            val req = call.receive<CallRequest>()
            val result = ActionExecutor.makeCall(req.number)
            call.respond(result)
        }

        post("/media") {
            data class MediaRequest(val action: String)
            val req = call.receive<MediaRequest>()
            val result = ActionExecutor.mediaControl(req.action)
            call.respond(result)
        }

        get("/contacts") {
            val query = call.request.queryParameters["query"] ?: ""
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val result = withContext(Dispatchers.IO) {
                ActionExecutor.searchContacts(query, limit)
            }
            call.respond(result)
        }

        post("/intent") {
            data class IntentRequest(val action: String, val dataUri: String? = null, val extras: Map<String, String>? = null, val packageOverride: String? = null)
            val req = call.receive<IntentRequest>()
            val result = ActionExecutor.sendIntent(req.action, req.dataUri, req.extras, req.packageOverride)
            call.respond(result)
        }

        get("/events") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            val entries = if (since > 0) {
                EventStore.getSince(since, limit)
            } else {
                EventStore.getAll(limit)
            }
            val mapped = entries.map { EventStore.toMap(it) }
            call.respond(mapOf(
                "events" to mapped,
                "count" to mapped.size,
                "streaming" to EventStore.streamingEnabled
            ))
        }

        post("/events/stream") {
            data class StreamRequest(val enabled: Boolean)
            val req = call.receive<StreamRequest>()
            EventStore.setStreaming(req.enabled)
            call.respond(mapOf("success" to true, "streaming" to req.enabled))
        }

        post("/broadcast") {
            data class BroadcastRequest(val action: String, val extras: Map<String, String>? = null)
            val req = call.receive<BroadcastRequest>()
            val result = ActionExecutor.sendBroadcast(req.action, req.extras)
            call.respond(result)
        }

        post("/screen_record") {
            data class RecordRequest(val durationMs: Long = 5000)
            val req = call.receive<RecordRequest>()
            // ScreenRecorder.record() handles its own threading internally via HandlerThread
            val result = ScreenRecorder.record(req.durationMs)
            call.respond(result)
        }

        get("/widgets") {
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.readWidgets()
            }
            call.respond(result)
        }

        post("/speak") {
            data class SpeakRequest(val text: String, val queue: Int = 1)
            val req = call.receive<SpeakRequest>()
            val result = ActionExecutor.speak(req.text, req.queue)
            call.respond(result)
        }

        post("/stop_speaking") {
            val result = ActionExecutor.stopSpeaking()
            call.respond(result)
        }
    }
}

private fun countNodes(nodes: List<Any>): Int {
    var count = 0
    for (node in nodes) {
        count++
        if (node is ScreenNode) {
            count += countNodeChildren(node)
        }
    }
    return count
}

private fun countNodeChildren(node: ScreenNode): Int {
    var count = node.children.size
    for (child in node.children) {
        count += countNodeChildren(child)
    }
    return count
}
