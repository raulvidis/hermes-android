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
- The relay still accepts a legacy `?token=` query parameter from older APKs;
  if you run a reverse proxy, configure it not to log query strings on `/ws`
  until all devices are updated

### Connection Architecture
- The phone connects **out** to the server (NAT-friendly)
- The server relay only accepts one phone at a time
- All tool commands are proxied through the relay — the phone is never directly exposed

## Known Limitations (Prototype)

### No Encryption
WebSocket connections use `ws://` (plaintext), not `wss://` (TLS). This means:
- Commands, screen content, and screenshots travel unencrypted
- Anyone on the network path between phone and server can intercept traffic
- **Mitigation**: Use over a trusted network, or set up a reverse proxy with TLS (nginx/caddy)

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
