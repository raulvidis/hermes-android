---
summary: "All environment variables and config options for the relay and tools."
read_when:
  - "Changing ports, relay URL, tokens, or timeouts"
  - "Switching between relay mode and direct USB/LAN mode"
---

# Configuration

Config lives in `~/.hermes/.env` (only needed for direct USB/LAN; relay mode is the default and needs none).

| Variable | Default | Purpose |
|----------|---------|---------|
| `ANDROID_BRIDGE_URL` | `http://localhost:8766` | Relay URL, or direct phone URL for USB/LAN |
| `ANDROID_BRIDGE_TOKEN` | *(none)* | Pairing code for auth headers |
| `ANDROID_RELAY_PORT` | `8766` | Port the relay listens on |
| `ANDROID_RELAY_HOST` | `localhost` | Relay bind address (see #38) |
| `ANDROID_BRIDGE_TIMEOUT` | `30` | HTTP request timeout (seconds) |
| `ANDROID_RELAY_CERT` | *(none)* | PEM certificate for the relay's own TLS listener (`wss://`) |
| `ANDROID_RELAY_KEY` | *(none)* | PEM private key for `ANDROID_RELAY_CERT` |

## Connection modes

- **Relay (default):** phone connects out to `ws://server:8766/ws`. No `.env` config needed; `android_setup` configures it.
- **Same WiFi (direct):** `ANDROID_BRIDGE_URL=http://192.168.x.x:8765`.
- **USB (direct):** `adb forward tcp:8765 tcp:8765` then `ANDROID_BRIDGE_URL=http://localhost:8765`.

## Transport security

- In the app, a bare relay host defaults to **`wss://`** unless the host is
  private (RFC1918, `127/8`, `169.254/16`, `::1`, `fe80::/10`, `*.local`,
  `*.internal`, single-label), where `ws://` stays the default. Type the scheme
  explicitly to override either default.
- `ws://` to a non-private host draws a persistent red warning in the app:
  plaintext is not only readable, it is injectable — see SECURITY.md.
- Relay-side TLS: set `ANDROID_RELAY_CERT` and `ANDROID_RELAY_KEY` to PEM paths
  and the relay terminates `wss://` itself. Otherwise put it behind nginx/caddy.

## Ports

- `8765` — phone's Ktor HTTP server (direct dev).
- `8766` — relay (WebSocket + HTTP bridge).

Never echo or dump these env values — `ANDROID_BRIDGE_TOKEN` is the pairing code. See SECURITY.md.
