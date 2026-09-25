package com.hermesandroid.bridge.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.model.computeHash
import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

object ActionExecutor {

    suspend fun tap(x: Int? = null, y: Int? = null, nodeId: String? = null): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        if (nodeId != null) {
            val node = findNodeById(nodeId)
                ?: return@wakeForAction ActionResult(false, "Node not found: $nodeId")
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return@wakeForAction ActionResult(result, if (result) "Tapped node $nodeId" else "Click action failed")
        }

        if (x != null && y != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            var done = false
            var success = false
            service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { success = true; done = true }
                override fun onCancelled(gestureDescription: GestureDescription) { success = false; done = true }
            }, null)
            var waited = 0
            while (!done && waited < 2000) { delay(50); waited += 50 }
            return@wakeForAction ActionResult(success, if (success) "Tapped ($x, $y)" else "Tap gesture cancelled")
        }

        ActionResult(false, "Provide either (x, y) or nodeId")
    }

    suspend fun tapText(text: String, exact: Boolean = false): ActionResult =
        WakeLockManager.wakeForAction {
        val node = ScreenReader.findNodeByText(text, exact)
            ?: return@wakeForAction ActionResult(false, "Element with text '$text' not found")

        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return@wakeForAction ActionResult(result, if (result) "Tapped '$text'" else "Click failed on '$text'")
        }

        var parent = node.parent
        var clickableParent: AccessibilityNodeInfo? = null
        while (parent != null) {
            if (parent.isClickable) {
                clickableParent = parent
                break
            }
            val grandparent = parent.parent
            parent.recycle()
            parent = grandparent
        }

        if (clickableParent != null) {
            val result = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clickableParent.recycle()
            node.recycle()
            return@wakeForAction ActionResult(result, if (result) "Tapped '$text' (via parent)" else "Click failed on '$text'")
        }

        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        node.recycle()
        val cx = (r.left + r.right) / 2
        val cy = (r.top + r.bottom) / 2
        tap(cx, cy)
    }

    suspend fun typeText(text: String, clearFirst: Boolean = false): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        val focusedNode = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        try {
            if (clearFirst) {
                val clearArgs = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
                    )
                }
                focusedNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            }

            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            val result = focusedNode?.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT, arguments
            ) ?: false
            ActionResult(result, if (result) "Typed text" else "No focused input found")
        } finally {
            focusedNode?.recycle()
        }
    }

    suspend fun swipe(direction: String, distance: String = "medium"): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        val displayMetrics = service.resources.displayMetrics
        val w = displayMetrics.widthPixels
        val h = displayMetrics.heightPixels

        val shortDist = 0.2f
        val mediumDist = 0.4f
        val longDist = 0.7f
        val dist = when (distance) { "short" -> shortDist; "long" -> longDist; else -> mediumDist }

        val (startX, startY, endX, endY) = when (direction) {
            "up" ->    arrayOf(w / 2f, h * 0.7f, w / 2f, h * (0.7f - dist))
            "down" ->  arrayOf(w / 2f, h * 0.3f, w / 2f, h * (0.3f + dist))
            "left" ->  arrayOf(w * 0.8f, h / 2f, w * (0.8f - dist), h / 2f)
            "right" -> arrayOf(w * 0.2f, h / 2f, w * (0.2f + dist), h / 2f)
            else -> return@wakeForAction ActionResult(false, "Unknown direction: $direction")
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        service.dispatchGesture(gesture, null, null)
        delay(400)
        ActionResult(true, "Swiped $direction ($distance)")
    }

    fun openApp(packageName: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult(false, "App not found: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return ActionResult(true, "Opening $packageName")
    }

    fun pressKey(key: String): ActionResult {
        // "wake" turns the screen on (short press) — NOT the power dialog.
        // GLOBAL_ACTION_POWER_DIALOG is the long-press menu (reboot/emergency),
        // which is almost never what a caller wants when waking the device.
        // Handled before the accessibility-service check: it only needs
        // PowerManager, so it must keep working when the service is down.
        if (key == "wake") {
            val woke = WakeLockManager.wake()
            return ActionResult(woke, if (woke) "Woke screen" else "Wake failed (PowerManager unavailable)")
        }

        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
            "take_screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            "volume_up", "volume_down", "enter", "delete", "tab", "escape", "search" ->
                return ActionResult(false, "Key '$key' is not supported via AccessibilityService global actions")
            else -> return ActionResult(false, "Unknown key: $key")
        }
        if (action == -1) {
            return ActionResult(false, "Key '$key' requires Android 9+ (API 28)")
        }
        val result = service.performGlobalAction(action)
        return ActionResult(result, if (result) "Pressed $key" else "Key press failed")
    }

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

    suspend fun takeScreenshot(): ActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ActionResult(false, "Screenshot requires Android 11 (API 30) or higher")
        }
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        return suspendCancellableCoroutine { cont ->
            val executor = Executor { it.run() }
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hwBitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer, result.colorSpace
                        )
                        if (hwBitmap == null) {
                            cont.resume(ActionResult(false, "Failed to create bitmap"))
                            result.hardwareBuffer.close()
                            return
                        }
                        // Convert to software bitmap for compression
                        val bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap.recycle()
                        result.hardwareBuffer.close()
                        if (bitmap == null) {
                            cont.resume(ActionResult(false, "Failed to copy screenshot bitmap"))
                            return
                        }

                        val w = bitmap.width
                        val h = bitmap.height
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        bitmap.recycle()

                        cont.resume(ActionResult(true, "Screenshot captured", mapOf(
                            "image" to base64,
                            "width" to w,
                            "height" to h,
                            "format" to "jpeg",
                            "encoding" to "base64"
                        )))
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(ActionResult(false, "Screenshot failed with error code $errorCode"))
                    }
                }
            )
        }
    }

    fun getInstalledApps(): List<Map<String, String>> {
        val service = BridgeAccessibilityService.instance ?: return emptyList()
        val pm = service.packageManager
        // Use queryIntentActivities to get all launchable apps (works on Android 11+)
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(launchIntent, 0).mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
            mapOf(
                "packageName" to appInfo.packageName,
                "label" to pm.getApplicationLabel(appInfo).toString()
            )
        }.distinctBy { it["packageName"] }.sortedBy { it["label"] }
    }

    suspend fun scroll(direction: String, nodeId: String? = null): ActionResult =
        WakeLockManager.wakeForAction {
            val service = BridgeAccessibilityService.instance
                ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

            if (nodeId != null) {
                val node = findNodeById(nodeId)
                    ?: return@wakeForAction ActionResult(false, "Node not found: $nodeId")
                val action = when (direction) {
                    "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> return@wakeForAction ActionResult(false, "Unknown direction: $direction")
                }
                val result = node.performAction(action)
                node.recycle()
                return@wakeForAction ActionResult(result, if (result) "Scrolled $direction in node $nodeId" else "Scroll failed on node $nodeId")
            }

            swipe(direction, "medium")
        }

    fun clipboardRead(): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip()) {
            return ActionResult(true, "Clipboard is empty", "")
        }
        val clip = cm.primaryClip ?: return ActionResult(true, "Clipboard is empty", "")
        if (clip.itemCount == 0) {
            return ActionResult(true, "Clipboard is empty", "")
        }
        val text = clip.getItemAt(0)?.text?.toString() ?: ""
        return ActionResult(true, "Clipboard read", text)
    }

    fun clipboardWrite(text: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("hermes", text)
        cm.setPrimaryClip(clip)
        return ActionResult(true, "Copied to clipboard")
    }

    suspend fun longPress(x: Int? = null, y: Int? = null, nodeId: String? = null, duration: Long = 500): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        if (nodeId != null) {
            val node = findNodeById(nodeId)
                ?: return@wakeForAction ActionResult(false, "Node not found: $nodeId")
            val result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            node.recycle()
            return@wakeForAction ActionResult(result, if (result) "Long pressed node $nodeId" else "Long click action failed")
        }

        if (x != null && y != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            var done = false
            var success = false
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { success = true; done = true }
                override fun onCancelled(g: GestureDescription) { success = false; done = true }
            }, null)
            var waited = 0
            while (!done && waited < 3000) { delay(50); waited += 50 }
            return@wakeForAction ActionResult(success, if (success) "Long pressed ($x, $y) ${duration}ms" else "Long press gesture cancelled")
        }

        ActionResult(false, "Provide either (x, y) or nodeId")
    }

    suspend fun drag(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 500): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        var done = false
        var success = false
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { success = true; done = true }
            override fun onCancelled(g: GestureDescription) { success = false; done = true }
        }, null)
        var waited = 0
        while (!done && waited < 3000) { delay(50); waited += 50 }
        ActionResult(success, if (success) "Dragged ($startX,$startY) to ($endX,$endY)" else "Drag gesture cancelled")
    }

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

    /**
     * Returns an error message if [scale] is not a usable pinch factor, or
     * null if it is. A pinch needs a finite, positive factor other than 1
     * (1 would dispatch a gesture whose fingers never move).
     */
    fun pinchScaleError(scale: Float): String? = when {
        !scale.isFinite() -> "scale must be a finite number"
        scale <= 0f -> "scale must be greater than 0"
        scale == 1f -> "scale must not be 1 (use >1 to zoom in, <1 to zoom out)"
        else -> null
    }

    suspend fun pinch(x: Int, y: Int, scale: Float = 1.5f, duration: Long = 300): ActionResult =
        WakeLockManager.wakeForAction {
        pinchScaleError(scale)?.let { return@wakeForAction ActionResult(false, it) }
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (x !in 0 until w || y !in 0 until h) {
            return@wakeForAction ActionResult(false, "Pinch center ($x, $y) is outside the screen (${w}x$h)")
        }
        val centerX = x.toFloat()
        val centerY = y.toFloat()
        // Perpendicular strokes on opposite sides of center along the X axis.
        // Fingers start at ±startOff and end at ±endOff. For scale>1 (zoom in),
        // endOff > startOff so fingers move apart; for scale<1 (zoom out),
        // endOff < startOff so fingers move together.
        //
        // The start span (2 * startOff) is ~40% of the short screen edge. A
        // fixed 100px span is below ScaleGestureDetector's minimum scaling
        // span (~27mm) on most devices, so apps silently ignored the gesture.
        var startOff = minOf(w, h) * 0.2f
        var endOff = startOff * scale
        // Keep both fingers on screen for the whole gesture. The wider of the
        // two spans is the one that can leave the screen; shrink both by the
        // same factor so the effective scale is preserved.
        val maxOff = minOf(centerX, (w - 1) - centerX)
        val widest = maxOf(startOff, endOff)
        if (widest > maxOff) {
            val factor = maxOff / widest
            startOff *= factor
            endOff *= factor
        }
        if (minOf(startOff, endOff) < 1f) {
            return@wakeForAction ActionResult(false, "Pinch center ($x, $y) is too close to the screen edge")
        }
        val path1 = Path().apply {
            moveTo(centerX - startOff, centerY)
            lineTo(centerX - endOff, centerY)
        }
        val path2 = Path().apply {
            moveTo(centerX + startOff, centerY)
            lineTo(centerX + endOff, centerY)
        }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, duration)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, duration)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        var done = false
        var success = false
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { success = true; done = true }
            override fun onCancelled(g: GestureDescription) { success = false; done = true }
        }, null)
        var waited = 0
        while (!done && waited < 3000) { delay(50); waited += 50 }
        val direction = if (scale > 1f) "zoom in" else "zoom out"
        ActionResult(success, if (success) "Pinch $direction at ($x, $y) scale $scale" else "Pinch gesture cancelled")
    }

    fun describeNode(nodeId: String): ActionResult {
        val node = findNodeById(nodeId)
            ?: return ActionResult(false, "Node not found: $nodeId")
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        val result = mutableMapOf<String, Any?>(
            "nodeId" to nodeId,
            "className" to node.className?.toString(),
            "packageName" to node.packageName?.toString(),
            "text" to node.text?.toString(),
            "contentDescription" to node.contentDescription?.toString(),
            "hintText" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() else null,
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
            "viewIdResourceName" to node.viewIdResourceName
        )
        node.recycle()
        return ActionResult(true, "Node details", result)
    }

    fun location(): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        if (!service.hasSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !service.hasSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return ActionResult(false, "Location permission not granted. Grant it in Settings > Apps > Hermes Bridge > Permissions.")
        }
        return try {
            val lm = service.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = lm.getProviders(true)
            var best: android.location.Location? = null
            for (provider in providers) {
                @Suppress("DEPRECATION")
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.accuracy < best.accuracy) {
                    best = loc
                }
            }
            if (best == null) {
                return ActionResult(false, "No location available. Enable GPS/Location.")
            }
            ActionResult(true, "Location", mapOf(
                "latitude" to best.latitude,
                "longitude" to best.longitude,
                "accuracy" to best.accuracy,
                "altitude" to best.altitude,
                "provider" to (best.provider ?: "unknown"),
                "timestamp" to best.time
            ))
        } catch (e: SecurityException) {
            ActionResult(false, "Location permission denied: ${e.message}")
        }
    }

    fun sendSms(to: String, body: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        if (!service.hasSelfPermission(android.Manifest.permission.SEND_SMS)) {
            return ActionResult(false, "SEND_SMS permission not granted. Grant it in Settings > Apps > Hermes Bridge > Permissions.")
        }
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                service.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(to, null, body, null, null)
            ActionResult(true, "SMS sent")
        } catch (e: SecurityException) {
            ActionResult(false, "SMS permission denied: ${e.message}")
        }
    }

    fun makeCall(number: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val hasCallPermission = service.hasSelfPermission(android.Manifest.permission.CALL_PHONE)
        val intent = Intent(if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL).apply {
            data = android.net.Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            service.startActivity(intent)
            ActionResult(true, if (hasCallPermission) "Calling" else "Opened dialer (grant CALL_PHONE permission to auto-dial)")
        } catch (e: SecurityException) {
            ActionResult(false, "Call failed: ${e.message}")
        }
    }

    fun mediaControl(action: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val keyCode = when (action) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ActionResult(false, "Unknown media action: $action. Use play, pause, toggle, next, previous.")
        }
        val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        }
        val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        }
        service.sendOrderedBroadcast(downIntent, null)
        service.sendOrderedBroadcast(upIntent, null)
        return ActionResult(true, "Media $action sent")
    }

    fun searchContacts(query: String, limit: Int = 20): ActionResult {
        val safeLimit = limit.coerceAtMost(100).coerceAtLeast(1)
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        if (!service.hasSelfPermission(android.Manifest.permission.READ_CONTACTS)) {
            return ActionResult(false, "READ_CONTACTS permission not granted. Grant it in Settings > Apps > Hermes Bridge > Permissions.")
        }
        return try {
            val results = mutableListOf<Map<String, String?>>()
            val safeQuery = android.net.Uri.encode(query)
            val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_FILTER_URI, safeQuery)
            val projection = arrayOf(
                android.provider.ContactsContract.Contacts._ID,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME
            )
            val cursor = service.contentResolver.query(uri, projection, null, null, "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} ASC")
            cursor?.use {
                val idIdx = it.getColumnIndex(android.provider.ContactsContract.Contacts._ID)
                val nameIdx = it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                while (it.moveToNext() && results.size < safeLimit) {
                    val contactId = it.getString(idIdx) ?: continue
                    val name = it.getString(nameIdx) ?: continue
                    val phoneNumbers = mutableListOf<String>()
                    val phoneCursor = service.contentResolver.query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )
                    phoneCursor?.use { pc ->
                        val numIdx = pc.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (pc.moveToNext()) {
                            pc.getString(numIdx)?.let { num -> phoneNumbers.add(num) }
                        }
                    }
                    results.add(mapOf("id" to contactId, "name" to name, "phones" to phoneNumbers.joinToString(", ")))
                }
            }
            if (results.isEmpty()) {
                ActionResult(false, "No contacts found matching '$query'")
            } else {
                ActionResult(true, "Found ${results.size} contacts", mapOf("contacts" to results, "count" to results.size))
            }
        } catch (e: SecurityException) {
            ActionResult(false, "READ_CONTACTS permission denied: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // sendIntent / sendBroadcast safety lists.
    //
    // These are BEST-EFFORT denylists, not a security boundary. A paired
    // caller already has full remote control of the device (it can tap its
    // way through Settings via the accessibility service), and new or
    // OEM-specific actions, schemes and providers appear all the time. The
    // lists exist to stop the obvious one-shot foot-guns — package
    // install/uninstall, factory reset, credential/admin changes, silent
    // dial/SMS deep links — not to contain a hostile client. Do not rely on
    // them for anything stronger than that.
    // ---------------------------------------------------------------------

    /**
     * Activity actions sendIntent refuses to launch. These have no legitimate
     * agent use case via a raw intent (the dedicated call/send_sms tools cover
     * telephony, and settings can be navigated to via open_app). Custom
     * namespace actions (containing a dot) are NOT exempt, because
     * package-manager actions like android.intent.action.DELETE are dotted too.
     */
    private val blockedIntentActions = setOf(
        // Package management — install/uninstall from arbitrary sources
        "android.intent.action.INSTALL_PACKAGE",
        "android.intent.action.DELETE",
        "android.intent.action.UNINSTALL_PACKAGE",
        "android.intent.action.PACKAGE_INSTALL",
        // Device wipe / factory reset / power
        "android.intent.action.MASTER_CLEAR",
        "android.intent.action.FACTORY_RESET",
        "android.intent.action.ACTION_SHUTDOWN",
        "android.intent.action.REBOOT",
        "android.intent.action.REQUEST_SHUTDOWN",
        "com.android.internal.intent.action.REQUEST_SHUTDOWN",
        // Telephony — direct dial without confirmation
        "android.intent.action.CALL",
        "android.intent.action.CALL_PRIVILEGED",
        "android.intent.action.CALL_EMERGENCY",
        // Default-app / role changes (e.g. hijacking the default SMS app)
        "android.provider.Telephony.ACTION_CHANGE_DEFAULT",
        "android.telecom.action.CHANGE_DEFAULT_DIALER",
        "android.app.role.action.REQUEST_ROLE",
        // Runtime-permission management UI
        "android.intent.action.MANAGE_PERMISSIONS",
        "android.intent.action.MANAGE_APP_PERMISSIONS",
        "android.intent.action.MANAGE_PERMISSION_APPS",
        "android.intent.action.MANAGE_DEFAULT_APP",
        // Device admin, screen lock and provisioning (android.app.action.*).
        // An explicit list rather than a prefix: the namespace also holds
        // harmless actions, and a prefix would hide which ones were meant.
        "android.app.action.ADD_DEVICE_ADMIN",
        "android.app.action.SET_NEW_PASSWORD",
        "android.app.action.SET_NEW_PARENT_PROFILE_PASSWORD",
        "android.app.action.START_ENCRYPTION",
        "android.app.action.PROVISION_MANAGED_PROFILE",
        "android.app.action.PROVISION_MANAGED_DEVICE",
        "android.app.action.PROVISION_FINANCED_DEVICE",
        "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE",
        "android.app.action.CONFIRM_DEVICE_CREDENTIAL",
        "android.app.action.CONFIRM_FRP_CREDENTIAL",
        // Credential storage — install/reset CA and user certificates
        "android.credentials.INSTALL",
        "android.credentials.INSTALL_AS_USER",
        "android.credentials.RESET",
        "android.credentials.UNLOCK",
        // Dangerous settings toggles — permissions, accessibility, dev options.
        // (Also covered by the android.settings. prefix below; listed so the
        // intent is explicit if the prefix rule ever changes.)
        "android.settings.ACCESSIBILITY_SETTINGS",
        "android.settings.APPLICATION_DETAILS_SETTINGS",
        "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
        "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
        "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
        "android.settings.MANAGE_OVERLAY_PERMISSION",
        "android.settings.action.MANAGE_OVERLAY_PERMISSION",
        "android.settings.NOTIFICATION_LISTENER_SETTINGS",
        "android.settings.USAGE_ACCESS_SETTINGS",
        "android.settings.VR_LISTENER_SETTINGS",
        "android.settings.WIFI_SETTINGS",
        "android.settings.WIRELESS_SETTINGS",
        "android.settings.AIRPLANE_MODE_SETTINGS",
        "android.settings.DATA_ROAMING_SETTINGS",
        "android.settings.SECURITY_SETTINGS",
        "android.settings.PRIVACY_SETTINGS",
        "android.settings.BIOMETRIC_ENROLL",
        "android.settings.ADD_ACCOUNT_SETTINGS",
        "android.settings.SYNC_SETTINGS",
        // Unknown app sources (alternate action name)
        "android.provider.action.MANAGE_UNKNOWN_APP_SOURCES",
        // App ops — can grant dangerous permissions
        "android.settings.APP_OPS_SETTINGS"
    )

    /** Any action in these namespaces is refused (settings / provider screens). */
    private val blockedIntentActionPrefixes = listOf(
        "android.settings.",
        "android.provider.action."
    )

    /**
     * Packages sendIntent will not target via packageOverride: the system
     * Settings app and the permission / package installer UIs. Reaching these
     * with an arbitrary action sidesteps the action blocklist entirely.
     */
    private val blockedIntentPackages = setOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    /**
     * URI schemes that turn a benign action (e.g. VIEW) into something the
     * action blocklist is meant to prevent: intent:// redirects, Play Store
     * deep links, and dial / SMS / MMS / SIP / voicemail composers.
     */
    private val blockedUriSchemes = setOf(
        "intent", "market",
        "sms", "smsto", "mms", "mmsto",
        "tel", "sip", "voicemail", "wtai"
    )

    /**
     * content:// authorities holding settings, contacts, call log, SMS/MMS and
     * SIM data. Matched against the parsed authority, not a string prefix, so
     * content://CALL_LOG/... and user-qualified content://10@com.android.contacts
     * are caught too.
     */
    private val blockedContentAuthorities = setOf(
        "settings",
        "com.android.contacts", "contacts",
        "call_log",
        "sms", "mms", "mms-sms",
        "icc"
    )

    /**
     * Web hosts that open the Play Store exactly like market:// does. Blocking
     * market:// alone is pointless if https://play.google.com/store/... works.
     * A null path prefix means every path on the host is blocked.
     */
    private val blockedStoreHosts = mapOf(
        "play.google.com" to "/store",
        "market.android.com" to null
    )

    /**
     * Returns a rejection message if [dataUri] must not be attached to an
     * outgoing intent, or null if it is allowed.
     */
    private fun blockedDataUriReason(dataUri: String): String? {
        val uri = android.net.Uri.parse(dataUri)
        // Two scheme readings, both checked. Uri.getScheme() is what intent
        // resolution actually sees; the manual split also covers single-colon
        // schemes (tel:, smsto:) in case parsing ever disagrees — there
        // substringBefore("://") alone would return the whole "tel:123456".
        val parsedScheme = uri.scheme?.trim()?.lowercase()
        val manualScheme = dataUri.trim().substringBefore("://").substringBefore(":").lowercase()
        for (candidate in listOfNotNull(parsedScheme, manualScheme)) {
            if (candidate in blockedUriSchemes) {
                return "URI scheme '$candidate' is blocked for safety"
            }
        }
        val scheme = parsedScheme ?: manualScheme
        if (scheme == "content") {
            // Strip a "<userId>@" qualifier (content://10@com.android.contacts).
            val authority = uri.authority?.substringAfterLast('@')?.trim()?.lowercase()
            if (authority != null && authority in blockedContentAuthorities) {
                return "Content provider URI is blocked for safety"
            }
        }
        if (scheme == "http" || scheme == "https") {
            val host = uri.host?.trim()?.lowercase()?.removeSuffix(".")
            if (host != null && blockedStoreHosts.containsKey(host)) {
                val pathPrefix = blockedStoreHosts[host]
                val path = uri.path?.lowercase() ?: ""
                if (pathPrefix == null || path == pathPrefix || path.startsWith("$pathPrefix/")) {
                    return "Play Store links are blocked for safety"
                }
            }
        }
        return null
    }

    fun sendIntent(action: String, dataUri: String? = null, extras: Map<String, String>? = null, packageOverride: String? = null): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        if (action.isBlank()) {
            return ActionResult(false, "Intent action is empty")
        }
        if (action in blockedIntentActions) {
            return ActionResult(false, "Intent action '$action' is blocked for safety")
        }
        if (blockedIntentActionPrefixes.any { action.startsWith(it) }) {
            return ActionResult(false, "Intent action '$action' is blocked for safety (settings/provider actions are not allowed)")
        }
        if (packageOverride != null && packageOverride.trim().lowercase() in blockedIntentPackages) {
            return ActionResult(false, "Target package '$packageOverride' is blocked for safety")
        }
        if (dataUri != null) {
            val reason = blockedDataUriReason(dataUri)
            if (reason != null) return ActionResult(false, reason)
        }

        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (dataUri != null) {
                    setData(android.net.Uri.parse(dataUri))
                }
                extras?.forEach { (key, value) ->
                    putExtra(key, value)
                }
                if (packageOverride != null) {
                    setPackage(packageOverride)
                }
            }
            service.startActivity(intent)
            ActionResult(true, "Intent sent: $action")
        } catch (e: Exception) {
            ActionResult(false, "Intent failed: ${e.message}")
        }
    }

    /**
     * System broadcasts sendBroadcast refuses to send. Most are protected
     * broadcasts the OS rejects anyway (SecurityException); listing them keeps
     * the refusal explicit instead of relying on that. Any other action —
     * dotted system actions included — is sent as-is, so like the sendIntent
     * lists above this is best-effort, not a boundary.
     */
    private val blockedBroadcastActions = setOf(
        "android.intent.action.MEDIA_MOUNTED",
        "android.intent.action.MEDIA_UNMOUNTED",
        "android.intent.action.MEDIA_EJECT",
        "android.intent.action.MEDIA_SCANNER_FINISHED",
        "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "android.intent.action.PACKAGE_ADDED",
        "android.intent.action.PACKAGE_REMOVED",
        "android.intent.action.PACKAGE_REPLACED",
        "android.intent.action.PACKAGE_DATA_CLEARED",
        "android.intent.action.DEVICE_STORAGE_LOW",
        "android.intent.action.DEVICE_STORAGE_OK",
        "android.intent.action.ACTION_SHUTDOWN",
        "android.intent.action.MASTER_CLEAR",
        "android.intent.action.FACTORY_RESET",
        "android.intent.action.REBOOT",
        "com.android.internal.intent.action.REQUEST_SHUTDOWN"
    )

    fun sendBroadcast(action: String, extras: Map<String, String>? = null): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        if (action.isBlank()) {
            return ActionResult(false, "Broadcast action is empty")
        }
        if (action in blockedBroadcastActions) {
            return ActionResult(false, "Broadcast action '$action' is blocked for safety")
        }

        // Catch everything, as sendIntent does: SecurityException for a
        // protected broadcast is the common case, but anything thrown here
        // must become an error result rather than escape as a 500.
        return try {
            val intent = Intent(action)
            extras?.forEach { (key, value) -> intent.putExtra(key, value) }
            service.sendBroadcast(intent)
            ActionResult(true, "Broadcast sent: $action")
        } catch (e: Exception) {
            ActionResult(false, "Broadcast failed: ${e.message}")
        }
    }

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

    private fun findNodeById(nodeId: String): AccessibilityNodeInfo? {
        val service = BridgeAccessibilityService.instance ?: return null
        val windows = service.windows
        val roots = windows.mapNotNull { it.root }
        var found: AccessibilityNodeInfo? = null
        for ((wi, root) in roots.withIndex()) {
            val matches = findNodeByIdInTree(root, nodeId, "$wi")
            if (matches.isNotEmpty()) {
                found = matches.first()
                if (found !== roots[wi]) roots[wi].recycle()
                for (r in roots.subList(wi + 1, roots.size)) r.recycle()
                break
            }
            root.recycle()
        }
        windows.forEach { it.recycle() }
        return found
    }

    /**
     * DFS search matching the stable ID format from ScreenReader.buildNode.
     *
     * Memory contract: the returned node(s) are intentionally NOT recycled — the caller
     * owns them and must recycle when done. Unmatched child nodes traversed during the
     * DFS are recycled, including intermediate ancestors on the path to a match.
     * Siblings after the first match are skipped
     * (early break) and left for the system to reclaim.
     */
    private fun findNodeByIdInTree(
        info: AccessibilityNodeInfo, targetId: String, path: String
    ): List<AccessibilityNodeInfo> {
        val r = android.graphics.Rect()
        info.getBoundsInScreen(r)
        val id = "${info.packageName ?: "?"}_${info.className ?: "?"}_${path}_${r.left}_${r.top}_${r.right}_${r.bottom}"
        if (id == targetId) return listOf(info)
        val results = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until info.childCount) {
            val child = info.getChild(i) ?: continue
            val found = findNodeByIdInTree(child, targetId, "${path}_$i")
            if (found.isNotEmpty()) {
                results.addAll(found)
                // Recycle the intermediate ancestor unless the child itself is a match
                if (found.none { it === child }) child.recycle()
                break
            } else {
                child.recycle()
            }
        }
        return results
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
    /** Wall-clock time of the last failed init, or 0 if the last init did not fail. */
    @Volatile private var ttsFailedAt = 0L
    /** Serialises ensureTts so concurrent /speak calls cannot each create an engine. */
    private val ttsMutex = Mutex()
    private const val TTS_INIT_TIMEOUT_MS = 10_000L
    /**
     * After a failed init, don't try again for this long. On a device with no
     * TTS engine every attempt fails, and without a backoff each /speak call
     * would bind (and leak) a fresh TextToSpeech instance.
     */
    private const val TTS_RETRY_BACKOFF_MS = 60_000L

    private suspend fun ensureTts(): Boolean = ttsMutex.withLock {
        if (tts != null && ttsReady) return@withLock true
        val failedAt = ttsFailedAt
        if (failedAt != 0L && System.currentTimeMillis() - failedAt < TTS_RETRY_BACKOFF_MS) {
            return@withLock false
        }
        val service = BridgeAccessibilityService.instance ?: return@withLock false

        // Discard any instance left over from an earlier, unfinished init.
        tts?.shutdown()
        tts = null
        ttsReady = false

        var created: android.speech.tts.TextToSpeech? = null
        val ready = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                created = android.speech.tts.TextToSpeech(
                    service.applicationContext,
                    android.speech.tts.TextToSpeech.OnInitListener { status ->
                        // May fire after a timeout has already cancelled us.
                        if (cont.isActive) cont.resume(status == android.speech.tts.TextToSpeech.SUCCESS)
                    }
                )
            }
        } ?: false

        val instance = created
        if (ready && instance != null) {
            tts = instance
            ttsReady = true
            ttsFailedAt = 0L
            true
        } else {
            // A failed (or timed-out) engine still holds a service binding.
            try { instance?.shutdown() } catch (_: Exception) {}
            ttsFailedAt = System.currentTimeMillis()
            false
        }
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
        ttsFailedAt = 0L
    }

    private fun Context.hasSelfPermission(permission: String): Boolean {
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
            checkSelfPermission(permission)
    }
}
