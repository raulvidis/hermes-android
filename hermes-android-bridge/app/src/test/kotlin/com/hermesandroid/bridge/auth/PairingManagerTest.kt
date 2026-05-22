package com.hermesandroid.bridge.auth

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

class PairingManagerTest {

    @Before
    fun setup() {
        clearCache()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("pairing_code", "") } returns "ABC123"
        val context = mockk<Context>()
        every { context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE) } returns prefs
        PairingManager.init(context)
    }

    private fun clearCache() {
        val field = PairingManager::class.java.getDeclaredField("cachedCode")
        field.isAccessible = true
        field.set(PairingManager, null)
    }

    @Test
    fun `valid token accepted`() {
        assertTrue(PairingManager.validateToken("Bearer ABC123"))
    }

    @Test
    fun `wrong token rejected`() {
        assertFalse(PairingManager.validateToken("Bearer WRONG1"))
    }

    @Test
    fun `different length token rejected`() {
        assertFalse(PairingManager.validateToken("Bearer ABC1234"))
    }

    @Test
    fun `short token rejected`() {
        assertFalse(PairingManager.validateToken("Bearer AB"))
    }

    @Test
    fun `null header rejected`() {
        assertFalse(PairingManager.validateToken(null))
    }

    @Test
    fun `token without bearer prefix rejected`() {
        assertFalse(PairingManager.validateToken("ABC123"))
    }
}
