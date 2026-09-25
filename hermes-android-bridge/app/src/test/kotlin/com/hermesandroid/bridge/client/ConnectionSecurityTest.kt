package com.hermesandroid.bridge.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests the pure cleartext classifier used to warn about token exposure. */
class ConnectionSecurityTest {

    @Test
    fun `wss is always secure`() {
        assertEquals(ConnectionSecurity.SECURE, classifyConnectionSecurity("wss://relay.example.com:8766/ws"))
    }

    @Test
    fun `ws to a public host is flagged`() {
        assertEquals(ConnectionSecurity.CLEARTEXT_PUBLIC, classifyConnectionSecurity("ws://relay.example.com:8766/ws"))
    }

    @Test
    fun `ws to a public bare IP is flagged`() {
        assertEquals(ConnectionSecurity.CLEARTEXT_PUBLIC, classifyConnectionSecurity("ws://8.8.8.8:8766/ws"))
    }

    @Test
    fun `loopback is private`() {
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://127.0.0.1:8766/ws"))
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://localhost:8766/ws"))
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://[::1]:8766/ws"))
    }

    @Test
    fun `rfc1918 ranges are private`() {
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://10.1.2.3:8766/ws"))
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://172.16.0.1:8766/ws"))
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://172.31.255.255:8766/ws"))
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://192.168.0.10:8766/ws"))
    }

    @Test
    fun `172 outside the private block is public`() {
        assertEquals(ConnectionSecurity.CLEARTEXT_PUBLIC, classifyConnectionSecurity("ws://172.32.0.1:8766/ws"))
        assertEquals(ConnectionSecurity.CLEARTEXT_PUBLIC, classifyConnectionSecurity("ws://172.15.0.1:8766/ws"))
    }

    @Test
    fun `link-local and CGNAT and mdns are private`() {
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://169.254.1.1:8766/ws"))
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://100.64.0.1:8766/ws"))
        assertEquals(ConnectionSecurity.CLEARTEXT_PRIVATE, classifyConnectionSecurity("ws://myphone.local:8766/ws"))
    }

    @Test
    fun `100 outside CGNAT is public`() {
        assertEquals(ConnectionSecurity.CLEARTEXT_PUBLIC, classifyConnectionSecurity("ws://100.128.0.1:8766/ws"))
    }
}
