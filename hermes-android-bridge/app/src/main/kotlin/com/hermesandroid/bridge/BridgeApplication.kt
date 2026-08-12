package com.hermesandroid.bridge

import android.app.Application
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.model.DeviceCapabilities
import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.server.BridgeServer

class BridgeApplication : Application() {
    companion object {
        lateinit var instance: BridgeApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PairingManager.init(applicationContext)
        DeviceCapabilities.init(applicationContext)
        WakeLockManager.init(applicationContext)
        BridgeServer.start(port = 8765)

        // Initialize relay client and auto-connect if previously configured
        RelayClient.init(applicationContext)
        RelayClient.autoConnect()
    }
}
