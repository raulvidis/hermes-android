package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the pure reconnect-decision function that [RelayClient.scheduleReconnect]
 * actually calls, rather than a reimplementation of the loop.
 */
class DecideReconnectTest {

    @Test
    fun `not reconnecting yields IGNORE`() {
        assertEquals(
            ReconnectAction.IGNORE,
            decideReconnect(
                shouldReconnect = false,
                hasScope = true,
                reconnectPending = false,
                isExhausted = false,
            ),
        )
    }

    @Test
    fun `a missing scope yields IGNORE even when reconnecting`() {
        assertEquals(
            ReconnectAction.IGNORE,
            decideReconnect(
                shouldReconnect = true,
                hasScope = false,
                reconnectPending = false,
                isExhausted = false,
            ),
        )
    }

    @Test
    fun `a pending attempt is skipped`() {
        assertEquals(
            ReconnectAction.SKIP_PENDING,
            decideReconnect(
                shouldReconnect = true,
                hasScope = true,
                reconnectPending = true,
                isExhausted = false,
            ),
        )
    }

    /**
     * The ordering that matters: pending MUST win over exhausted. When both are
     * true (the second of onClosed/onFailure lands while the final attempt is
     * still waiting out its backoff), we must SKIP, not declare EXHAUSTED and
     * silently drop that last retry.
     */
    @Test
    fun `pending is evaluated before exhausted`() {
        assertEquals(
            ReconnectAction.SKIP_PENDING,
            decideReconnect(
                shouldReconnect = true,
                hasScope = true,
                reconnectPending = true,
                isExhausted = true,
            ),
        )
    }

    @Test
    fun `exhausted budget with no pending attempt yields EXHAUSTED`() {
        assertEquals(
            ReconnectAction.EXHAUSTED,
            decideReconnect(
                shouldReconnect = true,
                hasScope = true,
                reconnectPending = false,
                isExhausted = true,
            ),
        )
    }

    @Test
    fun `an available budget yields ATTEMPT`() {
        assertEquals(
            ReconnectAction.ATTEMPT,
            decideReconnect(
                shouldReconnect = true,
                hasScope = true,
                reconnectPending = false,
                isExhausted = false,
            ),
        )
    }
}
