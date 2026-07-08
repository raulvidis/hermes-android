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
                "up" -> arrayOf(w / 2f, h * 0.7f, w / 2f, h * (0.7f - dist))
                "down" -> arrayOf(w / 2f, h * 0.3f, w / 2f, h * (0.3f + dist))
                "left" -> arrayOf(w * 0.8f, h / 2f, w * (0.8f - dist), h / 2f)
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
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
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
            "nodes" to nodes,
            "count" to nodeCount
        ))
    }

    fun screenHash(): ActionResult {
        val nodes = ScreenReader.readCurrentScreen(false)
        if (nodes.isEmpty()) {
            return ActionResult(true, "Screen hash", mapOf("hash" to ""))
        }
        val hash = nodes.joinToString("|") { it.computeHash() }
        val count = countNodes(nodes)
        return ActionResult(true, "Screen hash", mapOf("hash" to hash, "count" to count))
    }

    fun location(): ActionResult {
        return try {
            val service = BridgeAccessibilityService.instance
                ?: return ActionResult(false, "Accessibility service not running")
            val locationManager = service.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = locationManager.getProviders(true)
            if (providers.isEmpty()) {
                return ActionResult(false, "No location providers enabled")
            }
            // Try GPS first, then network
            var loc: android.location.Location? = null
            for (provider in providers) {
                loc = locationManager.getLastKnownLocation(provider)
                if (loc != null) break
            }
            if (loc != null) {
                ActionResult(true, "Location retrieved", mapOf(
                    "latitude" to loc.latitude,
                    "longitude" to loc.longitude,
                    "accuracy" to loc.accuracy
                ))
            } else {
                ActionResult(false, "No recent location available")
            }
        } catch (e: SecurityException) {
            ActionResult(false, "Location permission denied")
        } catch (e: Exception) {
            ActionResult(false, "Location error: ${e.message}")
        }
    }

    fun sendSms(to: String, body: String): ActionResult {
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(to, null, body, null, null)
            ActionResult(true, "SMS sent to $to")
        } catch (e: Exception) {
            ActionResult(false, "SMS failed: ${e.message}")
        }
    }

    fun makeCall(number: String): ActionResult {
        return try {
            val service = BridgeAccessibilityService.instance
                ?: return ActionResult(false, "Accessibility service not running")
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            ActionResult(true, "Calling $number")
        } catch (e: SecurityException) {
            ActionResult(false, "Call permission denied")
        } catch (e: Exception) {
            ActionResult(false, "Call failed: ${e.message}")
        }
    }

    fun mediaControl(action: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val keyCode = when (action) {
            "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            "volume_up" -> android.view.KeyEvent.KEYCODE_VOLUME_UP
            "volume_down" -> android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            "mute" -> android.view.KeyEvent.KEYCODE_VOLUME_MUTE
            else -> return ActionResult(false, "Unknown media action: $action")
        }
        val downTime = android.os.SystemClock.uptimeMillis()
        val event = android.view.KeyEvent(downTime, downTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
        service.dispatchKeyEvent(event)
        return ActionResult(true, "Media action: $action")
    }

    fun sendIntent(action: String, dataUri: String?, extras: Map<String, String>?, packageOverride: String?): ActionResult {
        return try {
            val service = BridgeAccessibilityService.instance
                ?: return ActionResult(false, "Accessibility service not running")
            val intent = Intent(action).apply {
                if (dataUri != null) data = android.net.Uri.parse(dataUri)
                if (packageOverride != null) `package` = packageOverride
                extras?.forEach { (k, v) -> putExtra(k, v) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            ActionResult(true, "Intent sent: $action")
        } catch (e: Exception) {
            ActionResult(false, "Intent failed: ${e.message}")
        }
    }

    fun sendBroadcast(action: String, extras: Map<String, String>?): ActionResult {
        return try {
            val service = BridgeAccessibilityService.instance
                ?: return ActionResult(false, "Accessibility service not running")
            val intent = Intent(action).apply {
                extras?.forEach { (k, v) -> putExtra(k, v) }
            }
            service.sendBroadcast(intent)
            ActionResult(true, "Broadcast sent: $action")
        } catch (e: Exception) {
            ActionResult(false, "Broadcast failed: ${e.message}")
        }
    }

    suspend fun pinch(x: Int, y: Int, scale: Float, duration: Long): ActionResult =
        WakeLockManager.wakeForAction {
            val service = BridgeAccessibilityService.instance
                ?: return@wakeForAction ActionResult(false, "Accessibility service not running")
            val path1 = Path().apply {
                moveTo(x.toFloat(), (y - 100).toFloat())
                lineTo(x.toFloat(), (y - 100 * scale).toFloat())
            }
            val path2 = Path().apply {
                moveTo(x.toFloat(), (y + 100).toFloat())
                lineTo(x.toFloat(), (y + 100 * scale).toFloat())
            }
            val gesture = GestureDescription.Builder().apply {
                addStroke(GestureDescription.StrokeDescription(path1, 0, duration))
                addStroke(GestureDescription.StrokeDescription(path2, 0, duration))
            }.build()
            var done = false
            var success = false
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { success = true; done = true }
                override fun onCancelled(g: GestureDescription) { success = false; done = true }
            }, null)
            var waited = 0
            while (!done && waited < 3000) { delay(50); waited += 50 }
            ActionResult(success, if (success) "Pinched at ($x,$y) scale=$scale" else "Pinch gesture cancelled")
        }

    fun searchContacts(query: String, limit: Int): ActionResult {
        return try {
            val service = BridgeAccessibilityService.instance
                ?: return ActionResult(false, "Accessibility service not running")
            val contentResolver = service.contentResolver
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            val cursor = contentResolver.query(uri, null, selection, selectionArgs, null)
            if (cursor == null) {
                return ActionResult(false, "Could not access contacts")
            }
            val contacts = mutableListOf<Map<String, String>>()
            while (cursor.moveToNext() && contacts.size < limit) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ))
                val phone = cursor.getString(cursor.getColumnIndexOrThrow(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ))
                contacts.add(mapOf("name" to name, "phone" to phone))
            }
            cursor.close()
            ActionResult(true, "Found ${contacts.size} contacts", mapOf("contacts" to contacts, "count" to contacts.size))
        } catch (e: SecurityException) {
            ActionResult(false, "Contacts permission denied")
        } catch (e: Exception) {
            ActionResult(false, "Contacts search failed: ${e.message}")
        }
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
        val widgets = mutableListOf<Map<String, String?>>()
        for (root in roots) {
            collectWidgetInfo(root, widgets, 0)
            root.recycle()
        }
        windows.forEach { it.recycle() }
        return ActionResult(true, "Found ${widgets.size} widget elements", mapOf("widgets" to widgets, "count" to widgets.size))
    }

    private fun collectWidgetInfo(node: AccessibilityNodeInfo, widgets: MutableList<Map<String, String?>>, depth: Int) {
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
        nodes: List<ScreenNode>,
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

    fun executeShell(command: String): ActionResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            val output = buildString {
                if (stdout.isNotEmpty()) append(stdout)
                if (stderr.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("stderr: $stderr")
                }
            }
            ActionResult(true, "Shell executed (exit=$exitCode)", mapOf(
                "stdout" to stdout,
                "stderr" to stderr,
                "exitCode" to exitCode,
                "output" to output
            ))
        } catch (e: Exception) {
            ActionResult(false, "Shell execution failed: ${e.message}")
        }
    }

    fun shutdownTts() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun Context.hasSelfPermission(permission: String): Boolean {
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
            checkSelfPermission(permission)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun countNodes(nodes: List<ScreenNode>): Int {
        var count = 0
        for (node in nodes) {
            count += 1 + countNodes(node.children)
        }
        return count
    }

    private fun findNodeById(nodeId: String): AccessibilityNodeInfo? {
        val service = BridgeAccessibilityService.instance ?: return null
        val windows = service.windows
        for (window in windows) {
            val root = window.root ?: continue
            val found = findNodeByIdInTree(root, nodeId, "")
            if (found.isNotEmpty()) {
                // found contains the target node; recycle the rest
                for (i in 1 until found.size) found[i].recycle()
                windows.forEach { it.recycle() }
                return found[0]
            }
            root.recycle()
        }
        windows.forEach { it.recycle() }
        return null
    }

    /**
     * Recursively search for a node by its synthetic ID.
     * Returns a list where the last element is the matched node (so callers can
     * recycle intermediate nodes they won't return).
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
                break
            } else {
                child.recycle()
            }
        }
        return results
    }
}
