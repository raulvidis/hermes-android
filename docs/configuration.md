---
summary: "All environment variables and config options for the relay and tools."
read_when:
  - "Changing ports, relay URL, tokens, or timeouts"
  - "Switching between relay mode and direct USB/LAN mode"
---

# Configuration

Config lives in `~/.hermes/.env` (only needed for direct USB/LAN; relay mode is the default and needs none). Every `ANDROID_*` variable below is read from that file as a fallback; `HERMES_HOME` itself is not, because it selects the file.

| Variable | Default | Read from | Purpose |
|----------|---------|-----------|---------|
| `ANDROID_BRIDGE_URL` | `http://localhost:8766` | env, then `.env` | Relay URL, or direct phone URL for USB/LAN |
| `ANDROID_BRIDGE_TOKEN` | *(none)* | env, then `.env` | Pairing code for auth headers |
| `ANDROID_RELAY_PORT` | `8766` | env, then `.env` | Port the relay listens on |
| `ANDROID_RELAY_HOST` | `localhost` | env, then `.env` | Relay bind address (see #38) |
| `ANDROID_BRIDGE_TIMEOUT` | `30` | env, then `.env` | HTTP request timeout (seconds) |
| `ANDROID_RELAY_CERT` | *(none)* | env, then `.env` | TLS certificate; relay then serves `https`/`wss` |
| `ANDROID_RELAY_KEY` | *(none)* | env, then `.env` | TLS private key — both cert and key must be set, or TLS stays off |
| `HERMES_HOME` | `~/.hermes` | env only | Directory whose `.env` supplies the fallback variables |

All of them fall back to `~/.hermes/.env`: `os.environ` always wins, and the parsed
file is cached for the life of the process. Two modules read it, because the relay
also runs standalone on a host without the tool package — `_env()` in
`tools/android_tool.py` covers `ANDROID_BRIDGE_URL`, `ANDROID_BRIDGE_TOKEN`,
`ANDROID_RELAY_PORT` and `ANDROID_BRIDGE_TIMEOUT`; `_env()` in `tools/android_relay.py`
covers `ANDROID_RELAY_PORT`, `ANDROID_RELAY_HOST`, `ANDROID_RELAY_CERT` and
`ANDROID_RELAY_KEY`. `HERMES_HOME` is the one variable that must be exported, since
it names the file being read.

The fallback matters because `android_setup` starts the relay *in-process*, inside a
gateway that loads `.env` for its own config but does not export it. Under the systemd
relay the variables are exported anyway — `contrib/hermes-android-relay.service`
supplies them with `EnvironmentFile=-%h/.hermes/.env` — so both paths agree.

`android_setup` persists `ANDROID_BRIDGE_URL`, `ANDROID_BRIDGE_TOKEN` and, on a fresh
install only, `ANDROID_RELAY_PORT`. It never overwrites an `ANDROID_RELAY_PORT` you
already set, in either the environment or the file.

## Connection modes

- **Relay (default):** phone connects out to the relay's `/ws` route — `wss://server:8766/ws` for a public host, `ws://server:8766/ws` for a LAN/private one (see [Transport security](#transport-security) below). No `.env` config needed; `android_setup` configures it.
- **Relay + TLS:** export `ANDROID_RELAY_CERT` and `ANDROID_RELAY_KEY` before the relay starts. A public host then needs no scheme typed in the app — it already defaults to `wss://server:8766/ws` — and an explicit `https://server:8766` upgrades the same way. The app validates with the platform trust store, so the certificate must be CA-trusted; self-signed certs fail the handshake and there is no pinning or bypass.
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

- **Termux Auto-Revival** (app switch, default OFF, persisted as `termux_revival_enabled`): after 3 exhausted reconnect cycles the bridge fires a Termux `RUN_COMMAND` intent to restart the stack, at most once per 10 minutes and **at most 3 times in total**. The budget is refilled only by a connection that actually opens, so a relay that is merely unreachable — wrong URL, rotated pairing code, server down for the weekend — cannot make the device run a script forever. When the budget is spent the status line says so once; tapping **Connect** re-arms the notice but not the budget. An HTTP 401/403 does not count as a revival failure at all, because restarting Termux cannot fix a credential problem.
  - **Script path** — `RelayClient.DEFAULT_REVIVAL_SCRIPT`, `/data/data/com.termux/files/home/.termux/boot/start-hermes.sh`. Not configurable and there is no UI for it, so **leave the switch off unless you have created that exact file**. It must be an absolute path inside Termux's own home, and executable. Because it lives in `.termux/boot/`, Termux also runs it at its own startup.
  - **Termux setting** — Termux > Settings > *Allow external apps* must be enabled, or `startService` throws `SecurityException` and the app shows "Revival blocked — allow Termux RUN_COMMAND". The manifest declares `com.termux.permission.RUN_COMMAND` for every install; the permission alone is not sufficient.
  - The status line carries the attempt count (`firing Termux revival (2/3)`).
- **Regenerating the pairing code** in the app does not update a relay that is already running; restart the hermes process (or the systemd relay unit) so the new code is loaded. See `docs/refactor/2026-09-29-commit-review.md` (F2).

Never echo or dump these env values — `ANDROID_BRIDGE_TOKEN` is the pairing code. See SECURITY.md.
