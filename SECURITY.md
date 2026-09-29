# Security

## Overview

hermes-android gives a remote AI agent full control of an Android device via AccessibilityService. This is powerful and inherently sensitive — treat it with the same caution as remote desktop access.

## Current Security Model

### Authentication
- **Pairing code**: A random 6-character alphanumeric code generated on the phone
- The phone and server must share this code to establish a connection
- Codes use characters `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no ambiguous 0/O/1/I)

### Rate Limiting
- Failed WebSocket authentication attempts are rate-limited per IP
- After 5 failed attempts in 60 seconds, the IP is blocked for 5 minutes
- Blocked IPs receive HTTP 429

### WebSocket Authentication Transport
- The phone sends the pairing code as an `Authorization: Bearer` header on the
  WebSocket handshake — never in the URL, so it does not land in reverse-proxy
  access logs
- Legacy `?token=` query authentication is rejected so pairing codes cannot land
  in reverse-proxy access logs

### Secret-field redaction
- Nodes the platform marks `isPassword` are never serialised as text: `/screen`,
  `/find_nodes`, `/describe_node` and `/events` emit `"[redacted]"` in place of
  the text and drop `contentDescription`/`hintText` for those nodes. The node
  itself stays listed (id, class, bounds, editable) so an agent can still tap and
  type into it.
- Text *searches* match the redacted value too, so `/find_nodes` cannot be used
  as a character-by-character oracle against a secret field.
- **Not covered:** `/screenshot` and `/screen_record` capture rendered pixels, so
  anything visible on screen — a password mid-typing, an OTP the IME
  auto-filled — is in the image. Android blanks `FLAG_SECURE` windows; most login
  screens do not set it.

### Connection Architecture
- The phone connects **out** to the server (NAT-friendly)
- The server relay only accepts one phone at a time
- All tool commands are proxied through the relay — the phone is never directly exposed

## Known Limitations (Prototype)

### Plaintext transports allow command injection, not just sniffing
A bare relay host typed into the app now defaults to `wss://` unless it is
private (RFC1918, `127/8`, `169.254/16`, `::1`, `fe80::/10`, `*.local`,
`*.internal`, or a single-label name), where `ws://` stays the default. An
explicit scheme always wins — so `ws://` and the phone's own LAN server on
`:8765` are still plaintext when you ask for them. Plaintext means:
- Commands, screen content, and screenshots travel unencrypted and can be read by
  anyone on the network path
- **A plaintext WebSocket has no integrity protection.** An on-path attacker does
  not need the pairing code and does not need to break anything: they can append
  server→client frames to the established TCP stream, and the phone dispatches
  every arriving frame as an already-authenticated command. Reading the traffic is
  the lesser problem — injecting `/send_sms`, `/call` or `/intent` is the real one
- **There is no server authentication on `ws://`.** The phone sends its long-lived
  pairing code to whatever host the name resolves to, so DNS spoofing or a rogue
  access point harvests the credential — and because that one code is shared by
  the relay and the `:8765` server, it works against both
- The app shows a persistent red warning on the relay status line (and a toast)
  when you connect over `ws://` to a non-private host
- **Mitigation**: use `wss://`. The relay can terminate TLS itself
  (`ANDROID_RELAY_CERT` / `ANDROID_RELAY_KEY`), or sit behind a reverse proxy with
  Let's Encrypt (nginx/caddy). Restrict `ws://` to a LAN/loopback address you
  control, and never to a public host

### Full Device Access
Once paired, the agent has unrestricted access to:
- Read all screen content (any app)
- Tap, type, swipe anywhere
- Open any app
- Take screenshots
- Read installed app list

There is no granular permission system — the agent can access banking apps, messages, etc.
- **Mitigation**: Only pair with trusted Hermes instances. Disconnect when not in use.

### No Command Audit Log
There is no persistent log of what commands the agent executed on the phone.
- **Mitigation**: The relay logs commands to stdout when run with INFO logging.

## Recommendations for Production Use

1. **Add TLS**: Put the relay behind a reverse proxy (nginx/caddy) with Let's Encrypt certificates
2. **Use a strong pairing code**: Don't share your code publicly
3. **Disconnect when idle**: Tap Disconnect in the app when you're not actively using it
4. **Monitor the phone**: Keep the status overlay enabled to see when the bridge is active
5. **Don't pair on public WiFi**: The unencrypted connection is vulnerable to interception

## Reporting Security Issues

If you find a security vulnerability, please open an issue on GitHub or contact the maintainers directly. Do not exploit vulnerabilities on other people's devices.
