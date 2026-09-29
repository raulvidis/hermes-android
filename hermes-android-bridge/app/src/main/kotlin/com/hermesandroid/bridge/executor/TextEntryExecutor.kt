package com.hermesandroid.bridge.executor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.service.BridgeAccessibilityService

/**
 * Text I/O actions: typing into the focused input node and reading or
 * writing the system clipboard.
 *
 * Implementation detail of [ActionExecutor] — callers go through the facade.
 */
internal object TextEntryExecutor {

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
}
