package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for RelayClient.buildWsUrl() — the URL rewrite every relay
 * connection goes through before the WebSocket handshake.
 *
 * The relay is the DEFAULT transport, so a wrong rewrite here is not a corner
 * case: it is "the bridge never connects". Three contracts matter:
 *  - scheme mapping: http→ws, https→wss, ws and wss kept (a TLS URL must
 *    never be downgraded to plaintext by the rewrite), and — TRANS-1 — a bare
 *    host with no scheme defaults to wss unless it is a private/LAN name, so
 *    the pairing code never travels in plaintext to a public relay. The rule
 *    table itself is pinned in [RelayUrlTest]; these cases pin the delegation;
 *  - the default relay port 8766 is appended only when the URL carries no
 *    port (a URL that names a port is left alone — custom relays and direct
 *    USB/LAN setups both depend on that);
 *  - the path is always /ws, the route the Python relay serves.
 */
class RelayClientBuildWsUrlTest {

    @Test
    fun `http URL maps to ws on the default relay port`() {
        assertEquals(
            "ws://relay.example.com:8766/ws",
            RelayClient.buildWsUrl("http://relay.example.com"),
        )
    }

    @Test
    fun `https URL maps to wss on the default relay port`() {
        assertEquals(
            "wss://relay.example.com:8766/ws",
            RelayClient.buildWsUrl("https://relay.example.com"),
        )
    }

    @Test
    fun `wss URL keeps TLS and its explicit port`() {
        assertEquals(
            "wss://relay.example.com:9443/ws",
            RelayClient.buildWsUrl("wss://relay.example.com:9443"),
        )
    }

    @Test
    fun `ws URL keeps its explicit port`() {
        assertEquals(
            "ws://relay.example.com:8766/ws",
            RelayClient.buildWsUrl("ws://relay.example.com:8766"),
        )
    }

    @Test
    fun `an explicit port is never replaced by the default`() {
        assertEquals(
            "ws://192.168.1.20:8766/ws",
            RelayClient.buildWsUrl("http://192.168.1.20:8766"),
        )
    }

    @Test
    fun `trailing slash and surrounding whitespace are ignored`() {
        assertEquals(
            "ws://relay.example.com:8766/ws",
            RelayClient.buildWsUrl("  http://relay.example.com/ "),
        )
    }

    @Test
    fun `a bare public host defaults to wss on the default relay port`() {
        assertEquals(
            "wss://relay.example.com:8766/ws",
            RelayClient.buildWsUrl("relay.example.com"),
        )
    }

    @Test
    fun `a bare LAN host keeps the ws scheme and the default relay port`() {
        assertEquals(
            "ws://192.168.1.20:8766/ws",
            RelayClient.buildWsUrl("192.168.1.20"),
        )
    }

    @Test
    fun `the path is always the ws route the relay serves`() {
        for (input in listOf(
            "http://relay.example.com",
            "https://relay.example.com",
            "ws://localhost:8766",
        )) {
            assertTrue(
                "$input must map onto the /ws route the relay serves",
                RelayClient.buildWsUrl(input).endsWith("/ws"),
            )
        }
    }
}
