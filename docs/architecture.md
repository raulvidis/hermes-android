---
summary: "Kotlin bridge ↔ Python toolset ↔ WebSocket relay; the 36 android_* tools and how a command flows end-to-end."
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
2. Phone connects: `ws://server:8766/ws?token=<pairing_code>`.
3. Agent calls an `android_*` tool → HTTP request to `localhost:8766`.
4. Relay wraps it as a JSON command, sends over WebSocket to the phone.
5. Phone executes via AccessibilityService, returns JSON over WebSocket.
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
| BridgeServer (Ktor/Netty) | HTTP server | 8765 | Direct USB/LAN dev |
| RelayClient (OkHttp) | WebSocket client | outbound | Connects to remote relay |

Key classes (`app/src/main/kotlin/com/hermesandroid/bridge/`):
- `auth/PairingManager.kt` — 6-char code gen/validate (excludes confusable 0/O/1/I); persisted.
- `client/RelayClient.kt` — outbound WebSocket; auto-reconnect with exponential backoff.
- `service/BridgeAccessibilityService.kt` — singleton AccessibilityService; reads UI tree on demand.
- `executor/ActionExecutor.kt` — tap/type/swipe/scroll/wait/open-app; wakes device via `WakeLockManager`.
- `executor/ScreenReader.kt` — traverses accessibility tree → `ScreenNode` hierarchy; finds nodes by text.
- `overlay/StatusOverlay.kt` — always-on HUD.

Required permissions: `ACCESSIBILITY_SERVICE`, `SYSTEM_ALERT_WINDOW`, `INTERNET`, `WAKE_LOCK`, `FOREGROUND_SERVICE` (plus optional runtime perms for location/contacts/SMS/phone/notifications).

## Relay server (Python — `android_relay.py`)

aiohttp server in a background daemon thread, started by `android_setup()`.

- `/ws` (WebSocket) — phone connects with `?token=<pairing_code>`.
- `/ping`, `/screen`, `/screenshot`, `/apps`, `/current_app` (GET); `/tap`, `/tap_text`, `/type`, `/swipe`, `/open_app`, `/press_key`, `/scroll`, `/wait` (POST).
- Auth: pairing code case-sensitive (exact compare, see #43). 5 failed attempts / 60s → IP blocked 5 min. Only one phone connected at a time.

## Tools

36 `android_*` tools span: connectivity (`ping`, `setup`), screen reading (`read_screen`, `screenshot`, `current_app`, `find_nodes`, `describe_node`, `screen_hash`, `diff_screen`), apps (`open_app`, `get_apps`), input (`tap`, `tap_text`, `type`, `long_press`, `drag`, `pinch`), gestures (`swipe`, `scroll`), keys (`press_key`), waiting (`wait`), device (`location`, `search_contacts`, `send_sms`, `call`, `media`, `send_intent`, `broadcast`, `clipboard_read`, `clipboard_write`), events (`notifications`, `events`, `event_stream`), capture (`screen_record`, `read_widgets`), voice (`speak`, `speak_stop`). See README for the full table.

## Integration paths

- **Plugin (preferred):** drop `hermes-android-plugin/` into `~/.hermes/plugins/hermes-android`; `register(ctx)` registers tools via the plugin API.
- **Legacy `tools/`:** import `tools/android_tool.py` directly into hermes-agent's registry.

Both share the same tool implementations and relay code.
