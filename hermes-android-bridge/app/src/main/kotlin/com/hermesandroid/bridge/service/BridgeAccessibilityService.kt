package com.hermesandroid.bridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BridgeA11yService"

        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            com.hermesandroid.bridge.event.EventStore.add(event)
        }
    }

    private var isForeground = false
    private var foregroundTypes = 0

    fun startForeground(includeMediaProjection: Boolean = false) {
        val requestedTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (includeMediaProjection) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
        } else {
            0
        }

        if (isForeground && (foregroundTypes and requestedTypes) == requestedTypes) return

        val channelId = "hermes_bridge_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Hermes Bridge",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Hermes Bridge running"
            }
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
                .setContentTitle("Hermes Bridge")
                .setContentText("Connected to server")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setContentTitle("Hermes Bridge")
                .setContentText("Connected to server")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i(TAG, "Starting foreground service with types=$requestedTypes")
            startForeground(1, notification, requestedTypes)
        } else {
            startForeground(1, notification)
        }
        isForeground = true
        foregroundTypes = requestedTypes
    }

    fun stopForeground() {
        if (!isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForeground = false
        foregroundTypes = 0
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
