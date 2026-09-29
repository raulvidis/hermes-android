package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TRANS-1 regression tests for the relay URL builder.
 *
 * Two things must hold at once:
 *  1. a **bare** host keeps the plaintext `ws://` it has always produced. The
 *     relay only terminates TLS when `ANDROID_RELAY_CERT`/`ANDROID_RELAY_KEY`
 *     are configured, and `android_setup` used to tell the user to type a bare
 *     `public_ip:port` — so defaulting a bare public host to `wss://` broke the
 *     documented setup and every already-stored URL with a TLS handshake
 *     failure against a plaintext listener. TLS is therefore something the
 *     operator opts into by typing a scheme, and `android_setup` now emits the
 *     scheme that matches what the relay actually serves;
 *  2. plaintext to a host that is not private is still *loud*: an explicit
 *     scheme always wins, and [RelayUrl.isPlaintextToPublicHost] keeps driving
 *     the toast and the persistent red status line, because a plaintext
 *     WebSocket can be injected into by anyone on the path, not just read.
 */
class RelayUrlTest {

    // ── 1. a bare host keeps the long-standing plaintext behaviour ────────────

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
            "100.64.0.1" to "ws://100.64.0.1:8766/ws",
            "100.111.22.33:8766" to "ws://100.111.22.33:8766/ws",
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

    /**
     * The regression this file exists for: `android_setup` hands the user a bare
     * `public_ip:port`, and a stored bare URL must keep dialling the plaintext
     * relay rather than failing a TLS handshake it has no certificate for.
     */
    @Test
    fun `bare public hosts stay plaintext too`() {
        val cases = mapOf(
            "relay.example.com" to "ws://relay.example.com:8766/ws",
            "hermes-relay.my-vps.net" to "ws://hermes-relay.my-vps.net:8766/ws",
            "1.2.3.4" to "ws://1.2.3.4:8766/ws",
            "203.0.113.7:8766" to "ws://203.0.113.7:8766/ws",
            "11.0.0.1" to "ws://11.0.0.1:8766/ws",
            "172.15.0.1" to "ws://172.15.0.1:8766/ws",
            "172.32.0.1" to "ws://172.32.0.1:8766/ws",
            "192.169.1.1" to "ws://192.169.1.1:8766/ws",
            "224.0.0.1" to "ws://224.0.0.1:8766/ws",
            "relay.example.com:443" to "ws://relay.example.com:443/ws",
            "[2001:db8::1]" to "ws://[2001:db8::1]/ws"
        )
        assertAll(cases) { RelayUrl.build(it) }
    }

    @Test
    fun `a bare host never gets a scheme it did not ask for`() {
        listOf("relay.example.com", "1.2.3.4", "203.0.113.7:8766", "[2001:db8::1]").forEach {
            assertTrue("$it must dial ws://", RelayUrl.build(it).startsWith("ws://"))
            assertFalse("$it must not be upgraded to wss://", RelayUrl.build(it).startsWith("wss://"))
        }
    }

    // ── 2. an explicit scheme always wins ─────────────────────────────────────

    @Test
    fun `an explicit plaintext scheme is honoured even for a public host`() {
        val cases = mapOf(
            "ws://relay.example.com" to "ws://relay.example.com:8766/ws",
            "http://relay.example.com" to "ws://relay.example.com:8766/ws",
            "WS://relay.example.com" to "ws://relay.example.com:8766/ws",
            "HTTP://relay.example.com" to "ws://relay.example.com:8766/ws"
        )
        assertAll(cases) { RelayUrl.build(it) }
    }

    @Test
    fun `an explicit tls scheme is honoured for any host`() {
        val cases = mapOf(
            "wss://relay.example.com" to "wss://relay.example.com:8766/ws",
            "https://relay.example.com" to "wss://relay.example.com:8766/ws",
            "WSS://relay.example.com" to "wss://relay.example.com:8766/ws",
            "HTTPS://relay.example.com" to "wss://relay.example.com:8766/ws",
            "wss://192.168.1.50" to "wss://192.168.1.50:8766/ws",
            "https://192.168.1.50" to "wss://192.168.1.50:8766/ws",
            "wss://localhost:8766" to "wss://localhost:8766/ws"
        )
        assertAll(cases) { RelayUrl.build(it) }
    }

    /**
     * The other half of the fix: TLS is reachable, but only by asking for it.
     * `android_setup` emits `wss://ip:port` when the relay has a certificate, so
     * the scheme has to survive the rewrite unchanged.
     */
    @Test
    fun `the wss address android_setup emits is dialled as tls`() {
        assertEquals("wss://203.0.113.7:8766/ws", RelayUrl.build("wss://203.0.113.7:8766"))
        assertEquals("ws://203.0.113.7:8766/ws", RelayUrl.build("ws://203.0.113.7:8766"))
    }

    // ── normalisation ─────────────────────────────────────────────────────────

    @Test
    fun `whitespace and trailing slashes are trimmed`() {
        val cases = mapOf(
            "  https://relay.example.com/  " to "wss://relay.example.com:8766/ws",
            "192.168.1.50/" to "ws://192.168.1.50:8766/ws",
            " relay.example.com " to "ws://relay.example.com:8766/ws",
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
            "ws://1.2.3.4:8766",
            // A bare host now dials ws:// too, so it must draw the same warning:
            // this is what keeps TRANS-1 visible after the scheme default went
            // back to plaintext.
            "relay.example.com",
            "1.2.3.4",
            "203.0.113.7:8766"
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
        listOf("wss://relay.example.com", "https://relay.example.com", "wss://1.2.3.4:8766")
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
            "singlelabel", "::1", "fe80::abcd", "fd12:3456::1", "fc00::1", "::ffff:192.168.1.1",
            // 100.64.0.0/10 — shared address space (RFC 6598), used for CGNAT and
            // by Tailscale for every tailnet node. Neither is a public host, and
            // neither can present a CA-trusted certificate for a 100.x name.
            "100.64.0.0", "100.64.0.1", "100.100.100.100", "100.127.255.255",
            "::ffff:100.64.0.1"
        )
        privateHosts.forEach { assertTrue("$it must be private", RelayUrl.isPrivateHost(it)) }
    }

    @Test
    fun `public host table`() {
        val publicHosts = listOf(
            "relay.example.com", "1.1.1.1", "8.8.8.8", "11.0.0.1", "9.255.255.255",
            "172.15.255.255", "172.32.0.0", "192.169.0.1", "193.168.0.1",
            "128.0.0.1", "169.255.0.1", "2001:db8::1", "2606:4700:4700::1111",
            "notlocal.example", "fdq.example.com",
            // Just outside 100.64.0.0/10 on both sides: the range is /10, not /8.
            "100.63.255.255", "100.128.0.0", "100.0.0.1", "101.64.0.1"
        )
        publicHosts.forEach { assertFalse("$it must be public", RelayUrl.isPrivateHost(it)) }
    }

    /**
     * The point of classifying 100.64.0.0/10 as private: a Tailscale relay address
     * is a private overlay network, so dialling it over `ws://` must not draw the
     * red "anyone on the path can inject commands" warning.
     */
    @Test
    fun `plaintext to a tailnet address is not flagged`() {
        listOf("100.64.0.1", "100.111.22.33:8766", "ws://100.127.255.254").forEach {
            assertFalse("$it must not be flagged", RelayUrl.isPlaintextToPublicHost(it))
        }
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
