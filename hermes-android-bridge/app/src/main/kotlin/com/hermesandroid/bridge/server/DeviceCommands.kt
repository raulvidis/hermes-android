package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.model.DeviceCapabilities
import com.hermesandroid.bridge.power.BatteryMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Device-state and telephony endpoints: GET /location, GET /battery,
 * POST /send_sms, POST /call, POST /media and GET /contacts — including the
 * DeviceCapabilities telephony gates, the 400 validation that keeps AOSP's
 * address-quoting IllegalArgumentException away from the caller, and the
 * Dispatchers.IO hop that keeps the bounded SMS send off the transport thread.
 *
 * Implementation detail of [CommandDispatcher] — the (method, path) routes are
 * declared in its table; these handlers hold the endpoint behaviour. The
 * batteryContext and smsSender test seams stay on [CommandDispatcher].
 */
internal object DeviceCommands {

    suspend fun location(): Pair<Any, Int> {
        val result = ActionExecutor.location()
        return result to 200
    }

    suspend fun battery(): Pair<Any, Int> {
        // Both values come from BatteryManager — percentage via
        // getIntProperty (API 21), charge state via isCharging (API 23)
        // — against a minSdk of 26, so there is no version gate and no
        // dependency on the accessibility service. Reading the level
        // out of the status-bar node instead would be language- and
        // OEM-fragile; see BatteryMonitor.
        //
        // No auth check here on purpose. The ktor interceptor in
        // BridgeServer requires a valid Bearer token for every path
        // except /ping, and the relay authenticates at connect time
        // before passing `true`. A second gate in the handler would be
        // unreachable and would only imply the first one is optional.
        //
        // The shape is fixed — both keys, always present. Nullable
        // fields are not an option even though BridgeServer configures
        // serializeNulls(): RelayClient serialises results with a
        // default Gson(), which drops nulls, so a nullable key would
        // appear over HTTP and vanish over the relay.
        val app = CommandDispatcher.batteryContext()
        val percentage = BatteryMonitor.percentage(app)
        val charging = BatteryMonitor.charging(app)
        if (percentage == null || charging == null) {
            return mapOf(
                "error" to "Battery state unavailable on this device",
            ) to 503
        }
        return mapOf(
            "batteryPercentage" to percentage,
            "charging" to charging,
        ) to 200
    }

    suspend fun sendSms(body: JsonObject): Pair<Any, Int> {
        if (!DeviceCapabilities.hasTelephony) {
            return mapOf("success" to false, "error" to "SMS not available on this device") to 200
        }
        // Validate before the executor. AOSP's sendTextMessage throws
        // IllegalArgumentException for an empty destination — and its
        // message quotes the address, which is why the blank case is
        // rejected here with a fixed string rather than surfacing the
        // exception. /open_app already answers 400 for a missing package.
        val to = body.get("to")?.let { if (it.isJsonPrimitive) it.asString else null }
        if (to.isNullOrBlank()) {
            return mapOf("error" to "Missing or blank 'to'") to 400
        }
        val smsBody = body.get("body")?.let { if (it.isJsonPrimitive) it.asString else null }
        if (smsBody.isNullOrBlank()) {
            return mapOf("error" to "Missing or blank 'body'") to 400
        }
        // F13: sendSms blocks on a CountDownLatch for up to 10 s waiting
        // for the radio's send report. `dispatch` is suspend, so calling it
        // inline would block the transport's own thread — over direct HTTP
        // a Netty event-loop thread that every other connection shares.
        // Same shape as /contacts and /screen_record.
        val result = withContext(Dispatchers.IO) { CommandDispatcher.smsSender(to, smsBody) }
        return result to 200
    }

    suspend fun call(body: JsonObject): Pair<Any, Int> {
        if (!DeviceCapabilities.hasTelephony) {
            return mapOf("success" to false, "error" to "Phone calls not available on this device") to 200
        }
        val number = body.get("number")?.asString ?: ""
        val result = ActionExecutor.makeCall(number)
        return result to 200
    }

    suspend fun media(body: JsonObject): Pair<Any, Int> {
        val action = body.get("action")?.asString ?: ""
        val result = ActionExecutor.mediaControl(action)
        return result to 200
    }

    suspend fun contacts(params: JsonObject): Pair<Any, Int> {
        if (!DeviceCapabilities.hasTelephony) {
            return mapOf("success" to false, "error" to "Contacts not available on this device") to 200
        }
        val query = params.get("query")?.asString ?: ""
        val limit = params.get("limit")?.asString?.toIntOrNull() ?: 20
        val result = withContext(Dispatchers.IO) {
            ActionExecutor.searchContacts(query, limit)
        }
        return result to 200
    }
}
