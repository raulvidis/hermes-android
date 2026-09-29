package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AUTH-1 regression tests: what a pairing-code rotation must do to the relay session.
 *
 * Before the fix, tapping **Regenerate** wrote a new code to one on-device store
 * while the relay client kept presenting a second, stale one, and the already-open
 * WebSocket (authenticated with the leaked code) stayed up. Rotation was cosmetic.
 */
class PairingRotationPolicyTest {

    private val policy = PairingRotationPolicy()

    @Test
    fun `live session with a saved url reconnects`() {
        assertEquals(
            PairingRotationPolicy.Action.RECONNECT,
            policy.onCodeRotated(savedUrl = "ws://relay.example:8766", sessionActive = true, reconnectArmed = false)
        )
    }

    @Test
    fun `armed reconnect loop with a saved url reconnects`() {
        // Session is momentarily down (backoff) but will come back on its own —
        // rotation must still re-dial so the new code is the one presented.
        assertEquals(
            PairingRotationPolicy.Action.RECONNECT,
            policy.onCodeRotated(savedUrl = "wss://relay.example", sessionActive = false, reconnectArmed = true)
        )
    }

    @Test
    fun `live session without a saved url is dropped`() {
        assertEquals(
            PairingRotationPolicy.Action.DROP,
            policy.onCodeRotated(savedUrl = null, sessionActive = true, reconnectArmed = false)
        )
        assertEquals(
            PairingRotationPolicy.Action.DROP,
            policy.onCodeRotated(savedUrl = "   ", sessionActive = false, reconnectArmed = true)
        )
    }

    @Test
    fun `no session and nothing armed is a no-op`() {
        assertEquals(
            PairingRotationPolicy.Action.NONE,
            policy.onCodeRotated(savedUrl = "wss://relay.example", sessionActive = false, reconnectArmed = false)
        )
        assertEquals(
            PairingRotationPolicy.Action.NONE,
            policy.onCodeRotated(savedUrl = null, sessionActive = false, reconnectArmed = false)
        )
    }
}
