package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.hermesandroid.bridge.event.EventStore
import com.hermesandroid.bridge.notification.NotificationStore
import com.hermesandroid.bridge.service.BridgeNotificationListener

/**
 * Notification and event-stream endpoints: GET /notifications, GET /events and
 * POST /events/stream, including the include_removed privacy gating (#100
 * follow-up) and the DROID-4 belt-and-braces check behind EventStore's own
 * streaming gate.
 *
 * Implementation detail of [CommandDispatcher] — the (method, path) routes are
 * declared in its table; these handlers hold the endpoint behaviour.
 */
internal object NotificationCommands {

    suspend fun notifications(params: JsonObject): Pair<Any, Int> {
        val limit = params.get("limit")?.asString?.toIntOrNull() ?: 50
        val since = params.get("since")?.asString?.toLongOrNull() ?: 0L
        // Dismissed notifications are retained on-device but only
        // served on explicit opt-in — cleared notifications can hold
        // PII the user has already dealt with (#100 follow-up).
        val includeRemoved = params.get("include_removed")?.asString
            ?.equals("true", ignoreCase = true) == true
        val entries = if (since > 0) {
            NotificationStore.getSince(since, limit, includeRemoved)
        } else {
            NotificationStore.getAll(limit, includeRemoved)
        }
        val mapped = entries.map { NotificationStore.toMap(it) }
        val listenerRunning = BridgeNotificationListener.instance != null
        return mapOf(
            "notifications" to mapped,
            "count" to mapped.size,
            "listenerActive" to listenerRunning
        ) to 200
    }

    suspend fun events(params: JsonObject): Pair<Any, Int> {
        // DROID-4: belt and braces behind the gate in EventStore.add — a
        // disabled switch must never serve events, whatever is in the
        // buffer or whatever filled it.
        return if (!EventStore.streamingEnabled) {
            mapOf(
                "events" to emptyList<Map<String, Any?>>(),
                "count" to 0,
                "streaming" to false
            ) to 200
        } else {
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
    }

    suspend fun eventsStream(body: JsonObject): Pair<Any, Int> {
        val enabled = body.get("enabled")?.asBoolean ?: false
        EventStore.setStreaming(enabled)
        return mapOf("success" to true, "streaming" to enabled) to 200
    }
}
