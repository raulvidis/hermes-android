package com.hermesandroid.bridge.client

/**
 * AUTH-1: what a pairing-code rotation must do to the live relay session.
 *
 * The relay authenticates the WebSocket handshake once, at connect time
 * (`Bearer <code>` on the upgrade request). A session opened with a code that
 * has since leaked therefore keeps full device control after the user taps
 * **Regenerate**, unless the session is explicitly dropped. Rotation must
 * either re-dial (presenting the new code) or disconnect.
 *
 * Pure decision object with no Android dependencies — the same shape as
 * [ReconnectPolicy] and [RevivalPolicy] — so the contract is unit-testable.
 */
class PairingRotationPolicy {

    enum class Action {
        /** No live session and no armed reconnect: nothing to drop. */
        NONE,

        /** A session was live or armed but no relay URL is stored: drop and stay down. */
        DROP,

        /** Drop the live session and immediately re-dial with the rotated code. */
        RECONNECT
    }

    /**
     * @param savedUrl        the persisted relay URL, if the user ever configured one
     * @param sessionActive   a WebSocket is currently open
     * @param reconnectArmed  [RelayClient] is in its reconnect loop (session may be
     *                        down right now but will come back on its own)
     */
    fun onCodeRotated(
        savedUrl: String?,
        sessionActive: Boolean,
        reconnectArmed: Boolean
    ): Action = when {
        !sessionActive && !reconnectArmed -> Action.NONE
        savedUrl.isNullOrBlank() -> Action.DROP
        else -> Action.RECONNECT
    }
}
