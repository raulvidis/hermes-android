---
summary: "Kotlin bridge ↔ Python toolset ↔ WebSocket relay; the 42 android_* tools and how a command flows end-to-end."
read_when:
  - "Changing the relay, bridge app, or tool wiring"
  - "Tracing how an android_* tool call reaches the phone"
  - "Adding a new tool or endpoint"
---

# Architecture

```
User  ──>  Hermes Agent  ──HTTP──>  Relay (localhost:8766)  ──WebSocket──>  Phone
                                          │
                                   aiohttp server
                                   pairing code auth
                                   rate limiting
```

The phone **connects out** to the relay over WebSocket (NAT-friendly — no port forwarding). The relay bridges HTTP tool calls from the agent to the phone over that socket. For local/USB dev, tools can talk directly to the phone's Ktor HTTP server on port 8765 by setting `ANDROID_BRIDGE_URL`.

## Two components

| Component | Path | Language | Role |
|-----------|------|----------|------|
| Android bridge app | `hermes-android-bridge/` | Kotlin | Runs on the phone; executes commands via AccessibilityService |
| Python toolset | `tools/`, `tests/`, `hermes-android-plugin/` | Python | Runs on the server; `android_*` tools + WebSocket relay |

The Python code is standalone here for dev/test (`pip install -e .`, `pytest`); the production copy lives in hermes-agent. **The APK does not depend on the Python files.**

## Data flow (relay mode)

1. `android_setup(pairing_code)` starts the relay + configures auth.
2. Phone connects to `/ws` with `Authorization: Bearer <pairing_code>`.
3. Agent calls an `android_*` tool → HTTP request to `localhost:8766`.
4. Relay wraps it as a JSON command, sends over WebSocket to the phone.
5. Phone executes the command and returns JSON over WebSocket; completed microphone WAVs use bounded binary frames with length and SHA-256 verification. The bridge keeps the 10 newest completed recordings.
6. Relay returns the phone's response as the HTTP response.

### Command envelope

```
Relay -> Phone:  {"request_id":"uuid","method":"GET|POST","path":"/screen","params":{...},"body":{...}}
Phone -> Relay:  {"request_id":"uuid","result":{...},"status":200}
```

## Android app (Kotlin)

Runs two network endpoints simultaneously:

| Component | Type | Port | Purpose |
|-----------|------|------|---------|
| BridgeServer (Ktor/Netty) | HTTP server | 8765 | Direct USB/LAN dev (fixed port) |
| RelayClient (OkHttp) | WebSocket client | outbound | Connects to remote relay |

Key classes (`app/src/main/kotlin/com/hermesandroid/bridge/`):
- `server/CommandDispatcher.kt` — single source of truth for every command; both transports call `dispatch(method, path, params, body, authenticated)`.
- `server/BridgeRouter.kt` — thin Ktor adapter: parses method/path/params/body, serves `/mic_file` as a WAV download, hands the rest to the dispatcher.
- `server/BridgeServer.kt` — Netty on `0.0.0.0:8765`; Bearer-token interceptor for every path except `/ping`, plus the per-IP `AuthRateLimiter`.
- `auth/PairingManager.kt` — 6-char code gen/validate (excludes confusable 0/O/1/I); persisted; constant-time compare.
- `client/RelayClient.kt` — outbound WebSocket; auto-reconnect with exponential backoff.
- `client/RevivalPolicy.kt` — pure gate/counter/cooldown/fire-budget behind the opt-in Termux revival watchdog; an auth failure (401/403) does not count, and the budget is re-armed only by a session that opens.
- `service/BridgeAccessibilityService.kt` — singleton AccessibilityService; reads UI tree on demand.
- `executor/ActionExecutor.kt` — tap/type/swipe/scroll/wait/open-app, plus `press_key` global actions, intents, broadcasts, SMS/call, contacts, TTS and widgets; wakes the device via `WakeLockManager`.
- `executor/ScreenReader.kt` — traverses accessibility tree → `ScreenNode` hierarchy; finds nodes by text.
- `audio/MicrophoneRecorderService.kt` (+ `MicrophoneRecordingState.kt`, `MicrophoneRecordingFiles.kt`) — foreground 16 kHz mono WAV recorder; 30-minute cap, 10-file retention, traversal-safe name resolution.
- `notification/NotificationStore.kt`, `event/EventStore.kt` — bounded on-device buffers (notifications: 50 by default, raised to 300 when the listener connects; events: 200); dismissed notifications are retained but served only on opt-in.
- `power/BatteryMonitor.kt` — battery level and charge state from `BatteryManager`; platform values, never status-bar text.
- `overlay/StatusOverlay.kt` — always-on HUD.

Manifest permissions: `INTERNET`, `WAKE_LOCK`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE` (plus `_SPECIAL_USE`, `_MEDIA_PROJECTION`, `_MICROPHONE`), `RECORD_AUDIO`, `READ_MEDIA_IMAGES`, `QUERY_ALL_PACKAGES`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `SEND_SMS`, `CALL_PHONE`, `READ_CONTACTS`, and `com.termux.permission.RUN_COMMAND` for the opt-in revival watchdog. The accessibility service and the notification listener are bound through `BIND_ACCESSIBILITY_SERVICE` / `BIND_NOTIFICATION_LISTENER_SERVICE` rather than `uses-permission`. Telephony and GPS are optional `uses-feature` (`required="false"`), which is why AAOS installs and degrades instead of failing.

## Relay server (Python — `android_relay.py`)

aiohttp server in a background daemon thread, started by `android_setup()`.

- `/ws` (WebSocket) — phone connects with a Bearer authorization header.
- HTTP bridge endpoints (method per path) — GET: `/ping`, `/screen`, `/screenshot`, `/apps`, `/current_app`, `/notifications`, `/contacts`, `/events`, `/screen_hash`, `/location`, `/battery`, `/widgets`, `/mic_status`, `/mic_file`. POST: `/tap`, `/tap_text`, `/type`, `/swipe`, `/open_app`, `/press_key`, `/scroll`, `/wait`, `/long_press`, `/drag`, `/describe_node`, `/find_nodes`, `/diff_screen`, `/pinch`, `/send_sms`, `/call`, `/media`, `/intent`, `/broadcast`, `/speak`, `/stop_speaking`, `/screen_record`, `/events/stream`, `/mic_start`, `/mic_stop`. Both: `/clipboard`.
- Auth: pairing code case-sensitive (exact compare, see #43). 5 failed attempts / 60s → IP blocked 5 min. Only one phone connected at a time — a second connection replaces the first.
- The endpoint list above is an **allowlist** (`_ROUTES`), not a description: a path missing from it is refused by the relay even though `CommandDispatcher` handles it, so a new route is unreachable over the default transport until it is added here too. `tests/test_android_relay.py` pins this line to `_ROUTES` in both directions and pins the two `android_relay.py` copies byte-identical — do not reflow it.
- TLS is opt-in: with `ANDROID_RELAY_CERT` + `ANDROID_RELAY_KEY` set the same port serves `https`/`wss`, otherwise plaintext. The phone upgrades to `wss://` when the server address starts with `https://` or `wss://`, and validates against the platform trust store (CA-trusted certs only).
- `contrib/hermes-relay-daemon.py` + `contrib/hermes-android-relay.service` run this same relay persistently under systemd, reading `ANDROID_BRIDGE_TOKEN` and `ANDROID_RELAY_PORT` from the environment (`EnvironmentFile=-%h/.hermes/.env`).

### Status codes

`CommandDispatcher` picks the status for anything it handles, so both transports agree: `200` for normal results (including capability errors such as "SMS not available on this device", which return `success: false` at `200`), `202` for `/mic_start` and `/mic_stop` accepted, `400` for a missing `package` or an invalid `duration`, `403` when the microphone permission is absent, `404` for `Unknown command: <METHOD> <path>`, `409` when a recording is already active, and `503` when battery state is unavailable. The relay returns the phone's `result` unwrapped with the phone's status, so a tool sees the same body either way.

The relay adds its own statuses before the phone is ever reached: `401` (missing or wrong Bearer token), `429` (IP blocked by the auth throttle), `503` (no phone connected), `502` (send failed or the phone connection dropped) and `504` (no reply within `_RESPONSE_TIMEOUT`, 30s). aiohttp answers `404`/`405` for a path or method that is not in `_ROUTES`.

The bridge's Ktor layer has no exception mapping, so an exception thrown inside a handler surfaces as a non-JSON `500` over HTTP while the relay path converts it to `{"error": …}` with status `500` — see `docs/refactor/2026-09-29-commit-review.md` (L1).

## Tools

42 `android_*` tools span: connectivity (`ping`, `setup`), screen reading (`read_screen`, `screenshot`, `current_app`, `find_nodes`, `describe_node`, `screen_hash`, `diff_screen`), apps (`open_app`, `get_apps`), input (`tap`, `tap_text`, `type`, `long_press`, `drag`, `pinch`), gestures (`swipe`, `scroll`), keys (`press_key`), waiting (`wait`), sequencing (`macro`), device (`location`, `search_contacts`, `send_sms`, `call`, `media`, `send_intent`, `broadcast`, `clipboard_read`, `clipboard_write`), events (`notifications`, `events`, `event_stream`), capture (`screen_record`, `read_widgets`), microphone (`mic_record`, `mic_stop`, `mic_status`, `mic_fetch`), and voice (`speak`, `speak_stop`). See README for the full table.

`GET /battery` is the one bridge endpoint with no tool wrapper: it is allowlisted on the relay and reachable over raw HTTP, but there is no `android_battery` in `_SCHEMAS`, so an agent cannot call it today.

## Integration paths

- **Plugin (preferred):** drop `hermes-android-plugin/` into `~/.hermes/plugins/hermes-android`; `register(ctx)` registers tools via the plugin API.
- **Legacy `tools/`:** import `tools/android_tool.py` directly into hermes-agent's registry.

Both share the same tool implementations and relay code.
