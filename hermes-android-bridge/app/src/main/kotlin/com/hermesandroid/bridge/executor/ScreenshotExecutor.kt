package com.hermesandroid.bridge.executor

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Screenshot capture: the API-30 gate, the hardware-buffer to software
 * bitmap conversion and the JPEG/base64 encoding of the result.
 *
 * Implementation detail of [ActionExecutor] — callers go through the facade.
 */
internal object ScreenshotExecutor {

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
}
