package com.hermesandroid.bridge.model

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {

    // Each test establishes state via DeviceCapabilities.init(), so no shared
    // reset is needed — and hasTelephony has a private setter by design.

    @Test
    fun `hasTelephony true when feature present`() {
        val context = mockk<Context>()
        val pm = mockk<PackageManager>()
        every { context.packageManager } returns pm
        every { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) } returns true

        DeviceCapabilities.init(context)
        assertTrue(DeviceCapabilities.hasTelephony)
    }

    @Test
    fun `hasTelephony false when feature absent`() {
        val context = mockk<Context>()
        val pm = mockk<PackageManager>()
        every { context.packageManager } returns pm
        every { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) } returns false

        DeviceCapabilities.init(context)
        assertFalse(DeviceCapabilities.hasTelephony)
    }
}
