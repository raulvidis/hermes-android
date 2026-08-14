package com.hermesandroid.bridge.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermesandroid.bridge.MainActivity

/**
 * Startet die Bridge-App nach jedem Geräte-Neustart.
 *
 * Der App-Prozess (BridgeApplication.onCreate) initialisiert dann automatisch:
 *  - den lokalen HTTP-Server auf Port 8765
 *  - die Relay-WebSocket-Verbindung (RelayClient.autoConnect)
 *
 * Kein manuelles "Connect" mehr nötig — die App verbindet sich mit dem
 * zuletzt gespeicherten Relay.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
        }
    }
}
