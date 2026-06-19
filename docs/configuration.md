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

## Connection modes

- **Relay (default):** phone connects out to `ws://server:8766/ws`. No `.env` config needed; `android_setup` configures it.
- **Same WiFi (direct):** `ANDROID_BRIDGE_URL=http://192.168.x.x:8765`.
- **USB (direct):** `adb forward tcp:8765 tcp:8765` then `ANDROID_BRIDGE_URL=http://localhost:8765`.

## Ports

- `8765` — phone's Ktor HTTP server (direct dev).
- `8766` — relay (WebSocket + HTTP bridge).

Never echo or dump these env values — `ANDROID_BRIDGE_TOKEN` is the pairing code. See SECURITY.md.
