package com.hermesandroid.bridge.client

import android.content.Context
import com.hermesandroid.bridge.auth.PairingManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AUTH-1 regression tests for the on-device half of pairing-code rotation.
 *
 * The bug: `RelayClient` kept its own copy of the code under
 * `relay_pairing_code`, written only by `connect()`, while **Regenerate** wrote
 * `pairing_code` via [PairingManager]. A rotated code therefore never reached the
 * relay handshake, and `autoConnect()` re-established the session with the old
 * (leaked) code on the next app launch.
 *
 * These tests pin the single-source-of-truth contract. The socket is replaced by
 * the `RelayClient.connector` test seam, so no network is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RelayClientPairingCodeTest {

    private lateinit var context: Context
    private lateinit var originalConnector: (String, String) -> Unit
    private val dialed = mutableListOf<Pair<String, String>>()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        clearPairingCache()
        PairingManager.init(context)
        RelayClient.init(context)
        dialed.clear()
        originalConnector = RelayClient.connector
        RelayClient.connector = { url, code -> dialed.add(url to code) }
        RelayClient.disconnect()
    }

    @After
    fun tearDown() {
        RelayClient.disconnect()
        RelayClient.connector = originalConnector
        clearPairingCache()
    }

    /** PairingManager caches the code in a static field; reset it between tests. */
    private fun clearPairingCache() {
        val field = PairingManager::class.java.getDeclaredField("cachedCode")
        field.isAccessible = true
        field.set(PairingManager, null)
    }

    private fun setConnected(value: Boolean) {
        val field = RelayClient::class.java.getDeclaredField("isConnected")
        field.isAccessible = true
        field.setBoolean(RelayClient, value)
    }

    @Test
    fun `pairingCode always reflects PairingManager, not a private store`() {
        val first = PairingManager.regenerateCode()
        assertEquals(first, RelayClient.pairingCode)

        val second = PairingManager.regenerateCode()
        assertFalse("rotation must produce a different code", first == second)
        assertEquals("RelayClient must see the rotated code", second, RelayClient.pairingCode)
    }

    @Test
    fun `connect presents the code PairingManager holds, ignoring the argument`() {
        val current = PairingManager.regenerateCode()

        RelayClient.connect("ws://relay.example:8766", "STALE0")

        assertEquals(1, dialed.size)
        assertEquals("ws://relay.example:8766", dialed[0].first)
        assertEquals(current, dialed[0].second)
    }

    @Test
    fun `connect falls back to the supplied code when PairingManager is empty`() {
        setPairingManagerCode("")

        RelayClient.connect("ws://relay.example:8766", "FALLBK")

        assertEquals(listOf("ws://relay.example:8766" to "FALLBK"), dialed)
    }

    @Test
    fun `connect refuses to dial without any code`() {
        setPairingManagerCode("")

        RelayClient.connect("ws://relay.example:8766", "   ")

        assertTrue("no socket may be opened without a credential", dialed.isEmpty())
    }

    @Test
    fun `regenerate then rotate re-dials with the new code`() {
        val old = PairingManager.regenerateCode()
        RelayClient.connect("wss://relay.example", old)
        assertEquals(old, dialed.last().second)

        val new = PairingManager.regenerateCode()
        val action = RelayClient.onPairingCodeRotated()

        assertEquals(PairingRotationPolicy.Action.RECONNECT, action)
        assertEquals(2, dialed.size)
        assertEquals("the live session must be re-dialed with the rotated code", new, dialed[1].second)
    }

    @Test
    fun `rotation drops an open session when no relay url is configured`() {
        PairingManager.regenerateCode()
        setConnected(true)
        RelayClient.serverUrl = null

        val action = RelayClient.onPairingCodeRotated()

        assertEquals(PairingRotationPolicy.Action.DROP, action)
        assertFalse("the stale session must be gone", RelayClient.isConnected)
        assertTrue(dialed.isEmpty())
    }

    @Test
    fun `rotation is a no-op when nothing is connected`() {
        PairingManager.regenerateCode()

        val action = RelayClient.onPairingCodeRotated()

        assertEquals(PairingRotationPolicy.Action.NONE, action)
        assertTrue(dialed.isEmpty())
    }

    @Test
    fun `autoConnect after rotation presents the new code`() {
        val old = PairingManager.regenerateCode()
        RelayClient.connect("wss://relay.example", old)
        RelayClient.disconnect()
        dialed.clear()

        val new = PairingManager.regenerateCode()
        RelayClient.autoConnect()

        assertEquals(listOf("wss://relay.example" to new), dialed)
    }

    @Test
    fun `init clears the legacy duplicate code store`() {
        val prefs = context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("relay_pairing_code", "OLDLEAK").commit()

        RelayClient.init(context)

        assertNull(
            "the pre-fix duplicate code must not survive an upgrade",
            context.getSharedPreferences("hermes_bridge_prefs", Context.MODE_PRIVATE)
                .getString("relay_pairing_code", null)
        )
    }

    private fun setPairingManagerCode(code: String) {
        val field = PairingManager::class.java.getDeclaredField("cachedCode")
        field.isAccessible = true
        field.set(PairingManager, code)
    }
}
