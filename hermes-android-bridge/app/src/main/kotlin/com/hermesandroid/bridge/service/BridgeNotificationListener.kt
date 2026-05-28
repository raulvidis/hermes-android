package com.hermesandroid.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hermesandroid.bridge.BuildConfig
import com.hermesandroid.bridge.notification.NotificationStore

class BridgeNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "BridgeNotifListener"

        @Volatile
        var instance: BridgeNotificationListener? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        NotificationStore.maxCapacity = 100
        Log.i(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val entry = NotificationStore.parseNotification(sbn)
        if (entry != null) {
            NotificationStore.add(entry)
            if (BuildConfig.DEBUG) Log.d(TAG, "Notification from ${entry.packageName}: ${entry.text?.take(50)}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        NotificationStore.markRemoved(key)
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
    }
}
