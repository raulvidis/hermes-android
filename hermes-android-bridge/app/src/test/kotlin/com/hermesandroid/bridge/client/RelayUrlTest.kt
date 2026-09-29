package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TRANS-1 regression tests for the relay URL builder.
 *
 * Two things must hold at once:
 *  1. a bare **public** host no longer silently becomes cleartext `ws://` — it
 *     defaults to `wss://`, because a plaintext WebSocket can be *injected into*
 *     by anyone on the path, not just read;
 *  2. LAN users are not broken — every private/loopback/link-local/`.local` form
 *     keeps the `ws://` behaviour it has always had, and an explicitly typed
 *     scheme always wins.
 */
class RelayUrlTest {

    // ── 1. LAN users keep working exactly as before ────────────────────────────

    @Test
    fun `bare private hosts stay plaintext`() {
        val cases = mapOf(
            "192.168.1.50" to "ws://192.168.1.50:8766/ws",
            "192.168.0.1" to "ws://192.168.0.1:8766/ws",
            "10.0.0.7" to "ws://10.0.0.7:8766/ws",
            "10.255.255.255" to "ws://10.255.255.255:8766/ws",
            "172.16.0.1" to "ws://172.16.0.1:8766/ws",
            "172.31.255.254" to "ws://172.31.255.254:8766/ws",
            "127.0.0.1" to "ws://127.0.0.1:8766/ws",
            "169.254.13.1" to "ws://169.254.13.1:8766/ws",
            "localhost" to "ws://localhost:8766/ws",
            "nas.local" to "ws://nas.local:8766/ws",
            "bridge.localdomain" to "ws://bridge.localdomain:8766/ws",
            "relay.internal" to "ws://relay.internal:8766/ws",
            "phone" to "ws://phone:8766/ws",
            "192.168.1.50:9000" to "ws://192.168.1.50:9000/ws",
            "localhost:8766" to "ws://localhost:8766/ws",
            "[::1]" to "ws://[::1]/ws",
            "[fe80::1]" to "ws://[fe80::1]/ws",
            "[fd00::1]:8766" to "ws://[fd00::1]:8766/ws"
        )
        assertAll(cases) { RelayUrl.build(it) }
    }

    @Test
    fun `an explicit scheme is honoured even for a public host`() {
        val cases = mapOf(
            "ws://relay.example.com" to "ws://relay.example.com:8766/ws",
            "http://relay.example.com" to "ws://relay.example.com:8766/ws",
            "WS://relay.example.com" to "ws://relay.example.com:8766/ws",
            "HTTP://relay.example.com" to "ws://relay.example.com:8766/ws"
        )
        assertAll(cases) { RelayUrl.build(it) }
    }

    @Test
    fun `an explicit tls scheme is honoured even for a private host`() {
        val cases = mapOf(
            "wss://192.168.1.50" to "wss://192.168.1.50:8766/ws",
            "https://192.168.1.50" to "wss://192.168.1.50:8766/ws",
            "wss://localhost:8766" to "wss://localhost:8766/ws"
        )
        assertAll(cases) { RelayUrl.build(it) }
    }

    // ── 2. public hosts default to TLS ────────────────────────────────────────

    @Test
    fun `bare public hosts default to tls`() {
        val cases = mapOf(
            "relay.example.com" to "wss://relay.example.com:8766/ws",
            "hermes-relay.my-vps.net" to "wss://hermes-relay.my-vps.net:8766/ws",
            "1.2.3.4" to "wss://1.2.3.4:8766/ws",
            "11.0.0.1" to "wss://11.0.0.1:8766/ws",
            "172.15.0.1" to "wss://172.15.0.1:8766/ws",
            "172.32.0.1" to "wss://172.32.0.1:8766/ws",
            "192.169.1.1" to "wss://192.169.1.1:8766/ws",
            "224.0.0.1" to "wss://224.0.0.1:8766/ws",
            "100.64.0.1" to "wss://100.64.0.1:8766/ws",
            "relay.example.com:443" to "wss://relay.example.com:443/ws",
            "[2001:db8::1]" to "wss://[2001:db8::1]/ws"
        )
        assertAll(cases) { RelayUrl.build(it) }
    }

    @Test
    fun `tls is the default rather than an opt-in`() {
        assertTrue(RelayUrl.build("relay.example.com").startsWith("wss://"))
        assertFalse(RelayUrl.build("relay.example.com").startsWith("ws://relay"))
    }

    // ── normalisation ─────────────────────────────────────────────────────────

    @Test
    fun `whitespace and trailing slashes are trimmed`() {
        val cases = mapOf(
            "  https://relay.example.com/  " to "wss://relay.example.com:8766/ws",
            "192.168.1.50/" to "ws://192.168.1.50:8766/ws",
            "\twss://relay.example.com//" to "wss://relay.example.com:8766/ws"
        )
        assertAll(cases) { RelayUrl.build(it) }
    }

    // ── the plaintext-to-a-public-host warning ────────────────────────────────

    @Test
    fun `plaintext to a public host is flagged for the UI warning`() {
        listOf(
            "ws://relay.example.com",
            "http://relay.example.com",
            "WS://1.2.3.4",
            "ws://1.2.3.4:8766"
        ).forEach {
            assertTrue("$it must be flagged", RelayUrl.isPlaintextToPublicHost(it))
        }
    }

    @Test
    fun `plaintext to a private host is not flagged`() {
        listOf(
            "192.168.1.50",
            "ws://192.168.1.50",
            "http://localhost:8766",
            "nas.local",
            "10.0.0.7:8766",
            "[::1]"
        ).forEach {
            assertFalse("$it must not be flagged", RelayUrl.isPlaintextToPublicHost(it))
        }
    }

    @Test
    fun `tls to a public host is not flagged`() {
        listOf("wss://relay.example.com", "https://relay.example.com", "relay.example.com")
            .forEach {
                assertFalse("$it must not be flagged", RelayUrl.isPlaintextToPublicHost(it))
            }
    }

    // ── host classification ───────────────────────────────────────────────────

    @Test
    fun `private host table`() {
        val privateHosts = listOf(
            "localhost", "LOCALHOST", "a.localhost", "127.0.0.1", "127.255.255.254",
            "10.1.2.3", "192.168.0.0", "172.16.0.0", "172.31.255.255",
            "169.254.0.1", "0.0.0.0", "nas.local", "x.localdomain", "y.internal",
            "singlelabel", "::1", "fe80::abcd", "fd12:3456::1", "fc00::1", "::ffff:192.168.1.1"
        )
        privateHosts.forEach { assertTrue("$it must be private", RelayUrl.isPrivateHost(it)) }
    }

    @Test
    fun `public host table`() {
        val publicHosts = listOf(
            "relay.example.com", "1.1.1.1", "8.8.8.8", "11.0.0.1", "9.255.255.255",
            "172.15.255.255", "172.32.0.0", "192.169.0.1", "193.168.0.1",
            "128.0.0.1", "169.255.0.1", "2001:db8::1", "2606:4700:4700::1111",
            "notlocal.example", "fdq.example.com"
        )
        publicHosts.forEach { assertFalse("$it must be public", RelayUrl.isPrivateHost(it)) }
    }

    @Test
    fun `host extraction strips ports and brackets`() {
        assertEquals("relay.example.com", RelayUrl.hostOf("relay.example.com:8766"))
        assertEquals("relay.example.com", RelayUrl.hostOf("relay.example.com"))
        assertEquals("2001:db8::1", RelayUrl.hostOf("[2001:db8::1]:443"))
        assertEquals("::1", RelayUrl.hostOf("[::1]"))
        assertEquals("fe80::1", RelayUrl.hostOf("fe80::1"))
    }

    private fun assertAll(cases: Map<String, String>, build: (String) -> String) {
        val failures = cases.filter { (input, expected) -> build(input) != expected }
            .map { (input, expected) -> "$input → ${build(input)} (expected $expected)" }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
