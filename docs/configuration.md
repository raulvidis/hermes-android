---
summary: "All environment variables and config options for the relay and tools."
read_when:
  - "Changing ports, relay URL, tokens, or timeouts"
  - "Switching between relay mode and direct USB/LAN mode"
---

# Configuration

Config lives in `~/.hermes/.env` (only needed for direct USB/LAN; relay mode is the default and needs none). Two variables are read from that file as a fallback — the rest are process-environment only.

| Variable | Default | Read from | Purpose |
|----------|---------|-----------|---------|
| `ANDROID_BRIDGE_URL` | `http://localhost:8766` | env, then `.env` | Relay URL, or direct phone URL for USB/LAN |
| `ANDROID_BRIDGE_TOKEN` | *(none)* | env, then `.env` | Pairing code for auth headers |
| `ANDROID_RELAY_PORT` | `8766` | env only | Port the relay listens on |
| `ANDROID_RELAY_HOST` | `localhost` | env only | Relay bind address (see #38) |
| `ANDROID_BRIDGE_TIMEOUT` | `30` | env only | HTTP request timeout (seconds) |
| `ANDROID_RELAY_CERT` | *(none)* | env only | TLS certificate; relay then serves `https`/`wss` |
| `ANDROID_RELAY_KEY` | *(none)* | env only | TLS private key — both cert and key must be set, or TLS stays off |
| `HERMES_HOME` | `~/.hermes` | env only | Directory whose `.env` supplies the two fallback variables |

Only `ANDROID_BRIDGE_URL` and `ANDROID_BRIDGE_TOKEN` fall back to `~/.hermes/.env`
(`_env()` in `tools/android_tool.py`); `os.environ` always wins, and the parsed file
is cached for the life of the process. Everything else is read with a plain
`os.getenv`, so it must be exported — including under the systemd relay, where
`contrib/hermes-android-relay.service` supplies it via `EnvironmentFile=-%h/.hermes/.env`.

## Connection modes

- **Relay (default):** phone connects out to `ws://server:8766/ws`. No `.env` config needed; `android_setup` configures it.
- **Relay + TLS:** export `ANDROID_RELAY_CERT` and `ANDROID_RELAY_KEY` before the relay starts, then enter `https://server:8766` in the app — it upgrades the socket to `wss://server:8766/ws`. The app validates with the platform trust store, so the certificate must be CA-trusted; self-signed certs fail the handshake and there is no pinning or bypass.
- **Same WiFi (direct):** `ANDROID_BRIDGE_URL=http://192.168.x.x:8765`.
- **USB (direct):** `adb forward tcp:8765 tcp:8765` then `ANDROID_BRIDGE_URL=http://localhost:8765`.
- **Persistent relay:** `contrib/hermes-android-relay.service` runs `contrib/hermes-relay-daemon.py` under systemd; it reads `ANDROID_BRIDGE_TOKEN` and `ANDROID_RELAY_PORT` and exits if the token is missing.

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

- `8765` — phone's Ktor HTTP server (direct dev). Fixed: `BridgeApplication` calls `BridgeServer.start(port = 8765)` and nothing overrides it.
- `8766` — relay (WebSocket + HTTP bridge), overridable with `ANDROID_RELAY_PORT`. Also the default port the app appends when the entered server address has none.

## On-device settings (not environment)

- **Termux Auto-Revival** (app switch, default OFF, persisted as `termux_revival_enabled`): after 3 exhausted reconnect cycles, at most once per 10 minutes, the bridge fires a Termux `RUN_COMMAND` intent at the hardcoded path `/data/data/com.termux/files/home/.termux/boot/start-zee.sh`. Termux must also allow external apps, otherwise the intent fails with a visible status. The path is not configurable — leave the switch off unless that script exists on the device.
- **Regenerating the pairing code** in the app does not update a relay that is already running; restart the hermes process (or the systemd relay unit) so the new code is loaded. See `docs/refactor/2026-09-29-commit-review.md` (F2).

Never echo or dump these env values — `ANDROID_BRIDGE_TOKEN` is the pairing code. See SECURITY.md.
