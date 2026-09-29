package com.hermesandroid.bridge.executor

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.service.BridgeAccessibilityService

/**
 * Hardware-adjacent key actions: accessibility global actions (back, home,
 * recents, notifications, power dialog, quick settings, lock screen,
 * screenshot, and the WakeLockManager-backed "wake") and media-key control
 * injected as ACTION_MEDIA_BUTTON ordered broadcasts.
 *
 * Implementation detail of [ActionExecutor] — callers go through the facade.
 */
internal object SystemKeyExecutor {

    fun pressKey(key: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        // "wake" turns the screen on (short press) — NOT the power dialog.
        // GLOBAL_ACTION_POWER_DIALOG is the long-press menu (reboot/emergency),
        // which is almost never what a caller wants when waking the device.
        if (key == "wake") {
            val woke = WakeLockManager.wake()
            return ActionResult(woke, if (woke) "Woke screen" else "Wake failed (PowerManager unavailable)")
        }

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
}
