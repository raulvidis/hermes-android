package com.hermesandroid.bridge.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.delay

/**
 * Gesture and touch actions: coordinate- and node-based taps and long
 * presses, text-based tapping, swipes, scrolls, drags and pinches.
 *
 * Implementation detail of [ActionExecutor] — callers go through the facade.
 */
internal object GestureExecutor {

    suspend fun tap(x: Int? = null, y: Int? = null, nodeId: String? = null): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        if (nodeId != null) {
            val node = NodeLookup.findNodeById(nodeId)
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

    suspend fun scroll(direction: String, nodeId: String? = null): ActionResult =
        WakeLockManager.wakeForAction {
            val service = BridgeAccessibilityService.instance
                ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

            if (nodeId != null) {
                val node = NodeLookup.findNodeById(nodeId)
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

    suspend fun longPress(x: Int? = null, y: Int? = null, nodeId: String? = null, duration: Long = 500): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        if (nodeId != null) {
            val node = NodeLookup.findNodeById(nodeId)
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

    suspend fun pinch(x: Int, y: Int, scale: Float = 1.5f, duration: Long = 300): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")
        val centerX = x.toFloat()
        val centerY = y.toFloat()
        // Perpendicular strokes on opposite sides of center along the X axis.
        // Fingers start at ±startOff and end at ±endOff. For scale>1 (zoom in),
        // endOff > startOff so fingers move apart; for scale<1 (zoom out),
        // endOff < startOff so fingers move together.
        val startOff = 50f
        val endOff = 50f * scale
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
}
