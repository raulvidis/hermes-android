package com.hermesandroid.bridge.model

import android.content.Context
import android.content.pm.PackageManager

object DeviceCapabilities {

    @Volatile
    var hasTelephony: Boolean = false
        private set

    fun init(context: Context) {
        hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }
}
