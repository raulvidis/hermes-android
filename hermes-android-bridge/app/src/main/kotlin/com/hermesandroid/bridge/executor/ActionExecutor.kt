package com.hermesandroid.bridge.executor

import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.model.computeHash
import com.hermesandroid.bridge.security.SensitiveText
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object ActionExecutor {

    suspend fun tap(x: Int? = null, y: Int? = null, nodeId: String? = null): ActionResult =
        GestureExecutor.tap(x, y, nodeId)

    suspend fun tapText(text: String, exact: Boolean = false): ActionResult =
        GestureExecutor.tapText(text, exact)

    suspend fun typeText(text: String, clearFirst: Boolean = false): ActionResult =
        TextEntryExecutor.typeText(text, clearFirst)

    suspend fun swipe(direction: String, distance: String = "medium"): ActionResult =
        GestureExecutor.swipe(direction, distance)

    fun openApp(packageName: String): ActionResult =
        AppIntentExecutor.openApp(packageName)

    fun pressKey(key: String): ActionResult =
        SystemKeyExecutor.pressKey(key)

    suspend fun waitForElement(
        text: String? = null,
        className: String? = null,
        timeoutMs: Int = 5000
    ): ActionResult {
        val interval = 500L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            val nodes = ScreenReader.readCurrentScreen(false)
            val found = findInTree(nodes, text, className)
            if (found != null) {
                return ActionResult(true, "Element found", found)
            }
            delay(interval)
            elapsed += interval
        }
        return ActionResult(false, "Timeout waiting for element (text=$text, class=$className)")
    }

    suspend fun takeScreenshot(): ActionResult =
        ScreenshotExecutor.takeScreenshot()

    fun getInstalledApps(): List<Map<String, String>> =
        AppIntentExecutor.getInstalledApps()

    suspend fun scroll(direction: String, nodeId: String? = null): ActionResult =
        GestureExecutor.scroll(direction, nodeId)

    fun clipboardRead(): ActionResult =
        TextEntryExecutor.clipboardRead()

    fun clipboardWrite(text: String): ActionResult =
        TextEntryExecutor.clipboardWrite(text)

    suspend fun longPress(x: Int? = null, y: Int? = null, nodeId: String? = null, duration: Long = 500): ActionResult =
        GestureExecutor.longPress(x, y, nodeId, duration)

    suspend fun drag(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 500): ActionResult =
        GestureExecutor.drag(startX, startY, endX, endY, duration)

    fun findNodes(text: String? = null, className: String? = null, clickable: Boolean? = null, limit: Int = 20): ActionResult {
        val nodes = ScreenReader.searchNodes(text, className, clickable, limit)
        if (nodes.isEmpty()) {
            return ActionResult(false, "No matching nodes found")
        }
        return ActionResult(true, "Found ${nodes.size} nodes", mapOf("nodes" to nodes, "count" to nodes.size))
    }

    fun diffScreen(previousHash: String): ActionResult {
        val nodes = ScreenReader.readCurrentScreen(false)
        if (nodes.isEmpty()) {
            return ActionResult(false, "No screen content")
        }
        val currentHash = nodes.joinToString("|") { it.computeHash() }
        if (currentHash == previousHash) {
            return ActionResult(true, "No changes detected", mapOf("changed" to false, "hash" to currentHash))
        }
        val nodeCount = countNodes(nodes)
        return ActionResult(true, "Screen changed", mapOf(
            "changed" to true,
            "hash" to currentHash,
            "nodeCount" to nodeCount
        ))
    }

    private fun countNodes(nodes: List<com.hermesandroid.bridge.model.ScreenNode>): Int {
        var count = 0
        fun walk(ns: List<com.hermesandroid.bridge.model.ScreenNode>) {
            for (n in ns) {
                count++
                walk(n.children)
            }
        }
        walk(nodes)
        return count
    }

    suspend fun pinch(x: Int, y: Int, scale: Float = 1.5f, duration: Long = 300): ActionResult =
        GestureExecutor.pinch(x, y, scale, duration)

    fun describeNode(nodeId: String): ActionResult {
        val node = NodeLookup.findNodeById(nodeId)
            ?: return ActionResult(false, "Node not found: $nodeId")
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        // PII-1: describe a secret field, never disclose it. Everything needed to
        // act on the node stays (bounds, class, viewIdResourceName, editable);
        // text/contentDescription/hintText do not, because the hint and the
        // content description routinely carry the mask or format of the secret.
        val secret = SensitiveText.isSecret(node)
        val result = mutableMapOf<String, Any?>(
            "nodeId" to nodeId,
            "className" to node.className?.toString(),
            "packageName" to node.packageName?.toString(),
            "text" to SensitiveText.resultText(node),
            "contentDescription" to SensitiveText.resultContentDescription(node),
            "hintText" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SensitiveText.hintText(node, node.hintText)
            } else null,
            "bounds" to mapOf("left" to r.left, "top" to r.top, "right" to r.right, "bottom" to r.bottom),
            "clickable" to node.isClickable,
            "longClickable" to node.isLongClickable,
            "focusable" to node.isFocusable,
            "editable" to node.isEditable,
            "scrollable" to node.isScrollable,
            "checkable" to node.isCheckable,
            "checked" to if (node.isCheckable) node.isChecked else null,
            "enabled" to node.isEnabled,
            "selected" to node.isSelected,
            "childCount" to node.childCount,
            "viewIdResourceName" to node.viewIdResourceName,
            "password" to secret
        )
        node.recycle()
        return ActionResult(true, "Node details", result)
    }

    fun location(): ActionResult =
        DeviceServicesExecutor.location()

    /**
     * How long [DeviceServicesExecutor.sendSms] waits for the radio's send
     * report. `internal` so tests can shorten it — the same seam idea as
     * [com.hermesandroid.bridge.server.CommandDispatcher.batteryContext]. The
     * storage lives beside the send it bounds; this accessor keeps the seam at
     * the name its callers already use.
     */
    internal var smsSendReportTimeoutMs: Long
        get() = DeviceServicesExecutor.smsSendReportTimeoutMs
        set(value) {
            DeviceServicesExecutor.smsSendReportTimeoutMs = value
        }

    fun sendSms(to: String, body: String): ActionResult =
        DeviceServicesExecutor.sendSms(to, body)

    /**
     * The broadcast action one send's reports arrive on (review F15) — see
     * [DeviceServicesExecutor.smsSentReportAction] for why it carries a per-call
     * UUID. Internal for `ActionExecutorSendSmsTest`, which asserts the
     * uniqueness and the shape.
     */
    internal fun smsSentReportAction(callId: String): String =
        DeviceServicesExecutor.smsSentReportAction(callId)

    fun makeCall(number: String): ActionResult =
        DeviceServicesExecutor.makeCall(number)

    fun mediaControl(action: String): ActionResult =
        SystemKeyExecutor.mediaControl(action)

    fun searchContacts(query: String, limit: Int = 20): ActionResult =
        DeviceServicesExecutor.searchContacts(query, limit)

    fun sendIntent(action: String, dataUri: String? = null, extras: Map<String, String>? = null, packageOverride: String? = null): ActionResult =
        AppIntentExecutor.sendIntent(action, dataUri, extras, packageOverride)

    fun sendBroadcast(action: String, extras: Map<String, String>? = null): ActionResult =
        AppIntentExecutor.sendBroadcast(action, extras)

    fun screenHash(): ActionResult {
        val nodes = ScreenReader.readCurrentScreen(false)
        if (nodes.isEmpty()) {
            return ActionResult(false, "No screen content")
        }
        val hash = nodes.joinToString("|") { it.computeHash() }
        val count = nodes.sumOf { countNodes(it) }
        return ActionResult(true, "Screen hash", mapOf("hash" to hash, "nodeCount" to count))
    }

    private fun countNodes(node: ScreenNode): Int {
        return 1 + node.children.sumOf { countNodes(it) }
    }

    suspend fun readWidgets(): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(homeIntent)
        delay(1000)

        val windows = service.windows
        val roots = windows.mapNotNull { it.root }
        val widgets = mutableListOf<Map<String, Any?>>()
        for (root in roots) {
            collectWidgetInfo(root, widgets, 0)
            root.recycle()
        }
        windows.forEach { it.recycle() }

        return ActionResult(true, "Found ${widgets.size} widget elements", mapOf("widgets" to widgets, "count" to widgets.size))
    }

    private fun collectWidgetInfo(node: AccessibilityNodeInfo, widgets: MutableList<Map<String, Any?>>, depth: Int) {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val className = node.className?.toString() ?: ""

        if ((depth <= 3 && (text != null || desc != null) && className.contains("Widget", ignoreCase = true)) || (depth <= 2 && (text != null || desc != null))) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            widgets.add(mapOf(
                "text" to text,
                "contentDescription" to desc,
                "className" to className,
                "bounds" to "${r.left},${r.top},${r.right},${r.bottom}",
                "packageName" to (node.packageName?.toString() ?: "")
            ))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectWidgetInfo(child, widgets, depth + 1)
            child.recycle()
        }
    }

    private fun findInTree(
        nodes: List<com.hermesandroid.bridge.model.ScreenNode>,
        text: String?,
        className: String?
    ): com.hermesandroid.bridge.model.ScreenNode? {
        for (node in nodes) {
            val textMatch = text == null || node.text?.contains(text, true) == true ||
                    node.contentDescription?.contains(text, true) == true
            val classMatch = className == null || node.className == className
            if (textMatch && classMatch) return node
            val childMatch = findInTree(node.children, text, className)
            if (childMatch != null) return childMatch
        }
        return null
    }

    @Volatile private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false

    private suspend fun ensureTts(): Boolean {
        val service = BridgeAccessibilityService.instance ?: return false
        if (tts == null || !ttsReady) {
            ttsReady = suspendCancellableCoroutine { cont ->
                tts = android.speech.tts.TextToSpeech(service.applicationContext, android.speech.tts.TextToSpeech.OnInitListener { status ->
                    val ready = status == android.speech.tts.TextToSpeech.SUCCESS
                    ttsReady = ready
                    cont.resume(ready)
                })
            }
        }
        return ttsReady
    }

    suspend fun speak(text: String, queue: Int = android.speech.tts.TextToSpeech.QUEUE_ADD): ActionResult {
        if (!ensureTts()) {
            return ActionResult(false, "TTS not available")
        }
        tts?.speak(text, queue, null, "hermes_speak_${System.currentTimeMillis()}")
        return ActionResult(true, "Speaking: ${text.take(50)}")
    }

    fun stopSpeaking(): ActionResult {
        tts?.stop()
        return ActionResult(true, "Stopped speaking")
    }

    fun shutdownTts() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

}
