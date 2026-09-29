package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.executor.ScreenReader
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Screen-reading endpoints: GET /screen, GET /screenshot, POST /describe_node,
 * POST /find_nodes, POST /diff_screen, GET /screen_hash, POST /wait,
 * GET /widgets and GET /current_app, plus the node-counting helper /screen
 * reports.
 *
 * Implementation detail of [CommandDispatcher] — the (method, path) routes are
 * declared in its table; these handlers hold the endpoint behaviour.
 */
internal object ScreenCommands {

    suspend fun screen(params: JsonObject): Pair<Any, Int> {
        val bounds = params.get("bounds")?.asString == "true"
        val systemUi = params.get("system_ui")?.asString == "true"
        val tree = withContext(Dispatchers.Main) {
            ScreenReader.readCurrentScreen(bounds, systemUi)
        }
        return mapOf("tree" to tree, "count" to countAllNodes(tree)) to 200
    }

    suspend fun screenshot(): Pair<Any, Int> {
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.takeScreenshot()
        }
        return result to 200
    }

    suspend fun wait(body: JsonObject): Pair<Any, Int> {
        val text = body.get("text")?.asString
        val className = body.get("className")?.asString
        val timeoutMs = CommandDispatcher.clampWaitTimeoutMs(body.get("timeoutMs")?.asInt)
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.waitForElement(text, className, timeoutMs)
        }
        return result to 200
    }

    suspend fun currentApp(): Pair<Any, Int> {
        val result = withContext(Dispatchers.Main) {
            val service = BridgeAccessibilityService.instance
            val windows = service?.windows ?: emptyList()
            val roots = windows.mapNotNull { it.root }
            val firstRoot = roots.firstOrNull()
            val pkg = firstRoot?.packageName?.toString() ?: "unknown"
            val cls = firstRoot?.className?.toString() ?: "unknown"
            roots.forEach { it.recycle() }
            windows.forEach { it.recycle() }
            mapOf("package" to pkg, "className" to cls)
        }
        return result to 200
    }

    suspend fun describeNode(body: JsonObject): Pair<Any, Int> {
        val nodeId = body.get("nodeId")?.asString ?: ""
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.describeNode(nodeId)
        }
        return result to 200
    }

    suspend fun findNodes(body: JsonObject): Pair<Any, Int> {
        val text = body.get("text")?.asString
        val className = body.get("className")?.asString
        val clickable = body.get("clickable")?.asBoolean
        val limit = body.get("limit")?.asInt ?: 20
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.findNodes(text, className, clickable, limit)
        }
        return result to 200
    }

    suspend fun diffScreen(body: JsonObject): Pair<Any, Int> {
        val previousHash = body.get("previousHash")?.asString ?: ""
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.diffScreen(previousHash)
        }
        return result to 200
    }

    suspend fun screenHash(): Pair<Any, Int> {
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.screenHash()
        }
        return result to 200
    }

    suspend fun widgets(): Pair<Any, Int> {
        val result = withContext(Dispatchers.Main) {
            ActionExecutor.readWidgets()
        }
        return result to 200
    }

    private fun countAllNodes(nodes: List<ScreenNode>): Int {
        var count = 0
        for (node in nodes) {
            count += 1 + countAllNodes(node.children)
        }
        return count
    }
}
