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
| `ANDROID_RELAY_CERT` | *(none)* | env, then `.env` | TLS certificate; relay then serves `https`/`wss` for **both** the phone and the tools |
| `ANDROID_RELAY_KEY` | *(none)* | env, then `.env` | TLS private key — both cert and key must be set, or TLS stays off |
| `ANDROID_RELAY_PUBLIC_HOST` | *(none)* | env, then `.env` | Hostname the TLS certificate covers. Only changes the address `android_setup` prints for the phone — the relay still binds `ANDROID_RELAY_HOST`. Required for a working `wss://` unless the certificate has an IP SAN. Setting it also skips public-IP detection, so `android_setup` makes no external calls. The TLS warning is keyed on the *address* being an IP, so setting this to a bare IP still warns |
| `HERMES_HOME` | `~/.hermes` | env only | Directory whose `.env` supplies the fallback variables |

All of them fall back to `~/.hermes/.env`: `os.environ` always wins, and the parsed
file is cached for the life of the process. Two modules read it, because the relay
also runs standalone on a host without the tool package — `_env()` in
`tools/android_tool.py` covers `ANDROID_BRIDGE_URL`, `ANDROID_BRIDGE_TOKEN`,
`ANDROID_RELAY_PORT`, `ANDROID_BRIDGE_TIMEOUT`, `ANDROID_RELAY_CERT`, `ANDROID_RELAY_KEY`
and `ANDROID_RELAY_PUBLIC_HOST`; `_env()` in `tools/android_relay.py` covers
`ANDROID_RELAY_PORT`, `ANDROID_RELAY_HOST`, `ANDROID_RELAY_CERT` and
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

- **Relay (default):** phone connects out to the relay's `/ws` route — `ws://server:8766/ws` unless the address you type names a TLS scheme (see [Transport security](#transport-security) below). No `.env` config needed; `android_setup` configures it and prints the address with the scheme the relay is actually serving.
- **Relay + TLS:** export `ANDROID_RELAY_CERT` and `ANDROID_RELAY_KEY` before the relay starts. TLS then covers the relay's *whole* site — the `/ws` route the phone dials and the HTTP routes the tools call are one listener — so `android_setup` prints `wss://ip:8766` for the phone *and* writes `ANDROID_BRIDGE_URL=https://localhost:8766` for the tools. Typing `wss://server:8766` or `https://server:8766` in the app upgrades the same way. The app validates with the platform trust store, so the certificate must be CA-trusted; self-signed certs fail the handshake and there is no pinning or bypass.
  Hostname verification applies too, and certificates are issued for DNS names — Let's Encrypt will not issue one for a bare IP. So set `ANDROID_RELAY_PUBLIC_HOST` to the name the certificate covers; `android_setup` then prints `wss://relay.example.com:8766` instead of the detected public IP, which is the address that actually completes a handshake. Without it, setup still prints the IP and says so in `server_address_note` and in the instructions it hands the user. The variable changes only what is printed: the relay binds `ANDROID_RELAY_HOST` regardless.
  The tools cannot verify that certificate on `https://localhost` — it is issued for the relay's public name, never for `localhost` — so `_bridge_tls_verify()` switches verification off for a **loopback** bridge URL only, and leaves it fully on for any other `https://` bridge URL. Loopback traffic never leaves the machine, and anyone able to intercept it already has local code execution and can read `~/.hermes/.env` (which holds the token) directly. Point `ANDROID_BRIDGE_URL` at a name the certificate covers instead of `localhost` if you want that hop verified too.
- **Same WiFi (direct):** `ANDROID_BRIDGE_URL=http://192.168.x.x:8765`.
- **USB (direct):** `adb forward tcp:8765 tcp:8765` then `ANDROID_BRIDGE_URL=http://localhost:8765`.
- **Persistent relay:** `contrib/hermes-android-relay.service` runs `contrib/hermes-relay-daemon.py` under systemd; it reads `ANDROID_BRIDGE_TOKEN` and `ANDROID_RELAY_PORT` and exits if the token is missing.

## Transport security

- In the app, the scheme you type wins: `wss://`/`https://` dials TLS, `ws://`/`http://`
  dials plaintext. A **bare** host (`relay.example.com`, `203.0.113.7:8766`) dials
  plaintext `ws://`, because the phone has no way to know whether the relay was
  given a certificate — defaulting a bare public host to `wss://` made the address
  `android_setup` prints fail the TLS handshake against a plaintext relay.
- `android_setup` therefore prints an explicit scheme: `wss://ip:port` when
  `ANDROID_RELAY_CERT`/`ANDROID_RELAY_KEY` are set, `ws://ip:port` otherwise. Type
  what it prints.
- `ws://` to a non-private host draws a persistent red warning in the app:
  plaintext is not only readable, it is injectable — see SECURITY.md. "Private"
  means loopback, RFC1918, `100.64.0.0/10` (CGNAT and Tailscale), `169.254/16`,
  `0/8`, `::1`, `fe80::/10`, `fc00::/7`, IPv4-mapped forms of the same,
  `*.local`/`*.localdomain`/`*.internal`, `localhost` and single-label names.
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
