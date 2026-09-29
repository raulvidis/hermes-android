package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.hermesandroid.bridge.executor.ActionExecutor

/**
 * App and raw-intent endpoints: POST /open_app, GET /apps, POST /intent and
 * POST /broadcast. The safety denylists themselves live in
 * [com.hermesandroid.bridge.executor.AppIntentExecutor]; these handlers only
 * unpack the request body.
 *
 * Implementation detail of [CommandDispatcher] — the (method, path) routes are
 * declared in its table; these handlers hold the endpoint behaviour.
 */
internal object AppIntentCommands {

    suspend fun openApp(body: JsonObject): Pair<Any, Int> {
        val pkg = body.get("package")?.asString
            ?: return mapOf("error" to "Missing package") to 400
        val result = ActionExecutor.openApp(pkg)
        return result to 200
    }

    suspend fun apps(): Pair<Any, Int> {
        val apps = ActionExecutor.getInstalledApps()
        return mapOf("apps" to apps, "count" to apps.size) to 200
    }

    suspend fun intent(body: JsonObject): Pair<Any, Int> {
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
        return result to 200
    }

    suspend fun broadcast(body: JsonObject): Pair<Any, Int> {
        val action = body.get("action")?.asString ?: ""
        val extrasObj = body.get("extras")?.asJsonObject
        val extras = extrasObj?.let { obj ->
            val map = mutableMapOf<String, String>()
            obj.entrySet().forEach { (k, v) -> map[k] = v.asString }
            map
        }
        val result = ActionExecutor.sendBroadcast(action, extras)
        return result to 200
    }
}
