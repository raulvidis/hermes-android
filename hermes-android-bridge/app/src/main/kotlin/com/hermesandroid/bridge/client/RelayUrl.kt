package com.hermesandroid.bridge.client

/**
 * TRANS-1: relay URL → WebSocket URL, and the plaintext-transport decision.
 *
 * The previous rule was "TLS only if the user typed `https://` or `wss://`", so a
 * bare `relay.example.com` silently became cleartext `ws://`. A plaintext
 * WebSocket has no integrity protection, so an on-path attacker does not merely
 * *read* the session: they can append server→client frames to the established TCP
 * stream, and `RelayClient.handleMessage` dispatches whatever arrives with
 * `authenticated = true` (connection-time auth is the only gate). Command
 * injection, not just sniffing — and no credential required.
 *
 * Rule now, deliberately conservative so LAN users are not broken:
 *  - an explicit scheme always wins (`http`→`ws`, `https`→`wss`, `ws`→`ws`,
 *    `wss`→`wss`, case-insensitively) — nobody's typed URL changes meaning;
 *  - a bare host that is loopback / RFC1918 / link-local / unique-local IPv6 /
 *    `.local` (or a single-label name, which only resolves inside a local search
 *    domain) stays `ws://`, exactly as before: there is no certificate to present
 *    on a LAN;
 *  - any other bare host defaults to `wss://`.
 *
 * Pure string/host logic with no Android dependencies, so the table is testable.
 */
object RelayUrl {

    /** Port the relay listens on when the URL does not name one. */
    const val DEFAULT_PORT = 8766

    private const val WS_PATH = "/ws"

    /** The WebSocket URL to dial for a user-supplied relay URL. */
    fun build(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        val lower = trimmed.lowercase()
        val explicitTls = lower.startsWith("https://") || lower.startsWith("wss://")
        val explicitPlaintext = lower.startsWith("http://") || lower.startsWith("ws://")
        val authority = stripScheme(trimmed, lower)
        // Unchanged from the previous behaviour: an authority that already names a
        // port (or an IPv6 literal, which is full of colons) is left alone.
        val withPort = if (authority.contains(":")) authority else "$authority:$DEFAULT_PORT"
        val scheme = when {
            explicitTls -> "wss"
            explicitPlaintext -> "ws"
            isPrivateHost(hostOf(authority)) -> "ws"
            else -> "wss"
        }
        return "$scheme://$withPort$WS_PATH"
    }

    /**
     * True when dialling [serverUrl] would carry the pairing code — and every
     * command — in plaintext to a host that is not private. The UI warns on this;
     * a LAN address does not warn, because plaintext there is the long-standing
     * default and there is no certificate to expect.
     */
    fun isPlaintextToPublicHost(serverUrl: String): Boolean {
        val authority = stripScheme(serverUrl.trim().trimEnd('/'))
        return build(serverUrl).startsWith("ws://") && !isPrivateHost(hostOf(authority))
    }

    private fun stripScheme(url: String, lower: String = url.lowercase()): String = when {
        lower.startsWith("https://") -> url.substring(8)
        lower.startsWith("http://") -> url.substring(7)
        lower.startsWith("wss://") -> url.substring(6)
        lower.startsWith("ws://") -> url.substring(5)
        else -> url
    }

    /** Host without port or brackets. Bare IPv6 literals are returned whole. */
    fun hostOf(authority: String): String {
        val trimmed = authority.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            return if (end > 0) trimmed.substring(1, end) else trimmed.removePrefix("[")
        }
        // More than one colon and no brackets: an IPv6 literal, not host:port.
        if (trimmed.count { it == ':' } > 1) return trimmed
        return trimmed.substringBeforeLast(":")
    }

    /**
     * Loopback, RFC1918, link-local, unique-local IPv6, and local-only names —
     * i.e. "LAN", where plaintext stays the default.
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().removeSuffix(".").lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.endsWith(".local") || h.endsWith(".localdomain") || h.endsWith(".internal")) return true
        if (h.contains(":")) return isPrivateIpv6(h)
        // A single-label name has no public DNS answer; it only resolves through a
        // local search domain (or not at all).
        if (!h.contains(".")) return true
        return isPrivateIpv4(h)
    }

    private fun isPrivateIpv6(host: String): Boolean {
        if (host == "::1" || host == "::") return true
        if (host.startsWith("fe80:")) return true                       // link-local
        if (host.startsWith("fc") || host.startsWith("fd")) return true // unique-local fc00::/7
        val mapped = host.removePrefix("::ffff:")
        return mapped != host && isPrivateIpv4(mapped)                  // IPv4-mapped
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||                       // 10.0.0.0/8
            a == 127 ||                         // 127.0.0.0/8 loopback
            a == 0 ||                           // 0.0.0.0/8 this-network
            (a == 192 && b == 168) ||           // 192.168.0.0/16
            (a == 172 && b in 16..31) ||        // 172.16.0.0/12
            (a == 169 && b == 254)              // 169.254.0.0/16 link-local
    }
}
