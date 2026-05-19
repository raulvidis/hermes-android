# hermes-android — Architecture Reference

> Reference document for the hermes-android system: remote Android device control via relay-based WebSocket architecture.

---

## Architecture Overview

```
User  ──>  Hermes Agent  ──HTTP──>  Relay (localhost:8766)  ──WebSocket──>  Phone
                                            │
                                     aiohttp server
                                     pairing code auth
                                     rate limiting
```

The phone **connects out** to the relay via WebSocket (NAT-friendly — no port forwarding needed). The relay bridges HTTP tool calls from the agent to the phone over that WebSocket connection.

For local/USB development, tools can also talk directly to the phone's built-in Ktor HTTP server on port 8765 by setting `ANDROID_BRIDGE_URL` to the phone's IP.

### Data flow (relay mode)

1. `android_setup(pairing_code)` starts the relay and configures auth
2. Phone connects via WebSocket to `ws://server:8766/ws?token=<pairing_code>`
3. Agent calls an `android_*` tool → HTTP request to `localhost:8766`
4. Relay wraps the HTTP request as a JSON command and sends it over WebSocket to the phone
5. Phone executes the command (via AccessibilityService), returns JSON response over WebSocket
6. Relay returns the phone's response as the HTTP response

### Command envelope format

```
Relay -> Phone:  {"request_id": "uuid", "method": "GET|POST", "path": "/screen",
                  "params": {...}, "body": {...}}
Phone -> Relay:  {"request_id": "uuid", "result": {...}, "status": 200}
```

---

## Repository Structure

```
hermes-android/
├── hermes-android-plugin/        # v0.3 plugin system integration
│   ├── __init__.py               # register(ctx) — registers 38 tools via plugin API
│   ├── android_tool.py           # tool handlers + schemas (38 tools)
│   ├── android_relay.py          # aiohttp relay server (WebSocket + HTTP bridge)
│   ├── plugin.yaml               # plugin metadata
│   └── skill.md                  # hermes-agent skill instructions
├── tools/                        # legacy integration (imported into hermes-agent directly)
│   ├── android_tool.py           # same tools, uses tools.registry for registration
│   └── android_relay.py          # same relay
├── hermes-android-bridge/        # Android app (Kotlin)
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/hermesandroid/bridge/
│           ├── MainActivity.kt          # UI: pairing code display, server URL input, connect
│           ├── BridgeApplication.kt     # App init: starts Ktor server, inits managers
│           ├── auth/PairingManager.kt   # 6-char pairing code generation & validation
│           ├── client/RelayClient.kt    # OkHttp WebSocket client (connects out to relay)
│           ├── service/BridgeAccessibilityService.kt  # AccessibilityService singleton
│           ├── server/BridgeServer.kt   # Ktor embedded server (Netty, port 8765)
│           ├── server/BridgeRouter.kt   # Ktor route definitions (local HTTP API)
│           ├── executor/ActionExecutor.kt  # tap, type, swipe, scroll, wait, etc.
│           ├── executor/ScreenReader.kt    # accessibility tree reader, node finder
│           ├── power/WakeLockManager.kt    # wake device for actions
│           ├── overlay/StatusOverlay.kt    # always-on HUD overlay
│           └── model/ScreenNode.kt         # data classes (ScreenNode, NodeBounds, ActionResult)
├── skills/android/               # app-specific skill docs (uber, whatsapp, etc.)
├── tests/
│   ├── conftest.py
│   └── test_android_tool.py
├── AGENTS.md                     # hermes-agent context file
├── PLAN.md                       # this file
├── setup.py / pyproject.toml     # package config
└── requirements.txt
```

---

## Integration Paths

### Plugin system (preferred)

Drop `hermes-android-plugin/` into `~/.hermes/plugins/hermes-android`. The `register(ctx)` function in `__init__.py` registers all 38 tools via `ctx.register_tool()`.

### Legacy tools/ directory

Import `tools/android_tool.py` directly into hermes-agent's tool registry. This path uses `from tools.registry import registry` for registration.

Both paths share the same tool implementations and relay code.

---

## Android App (hermes-android-bridge)

### Dual networking

The app runs **two** network servers/clients simultaneously:

| Component | Type | Port | Purpose |
|-----------|------|------|---------|
| **BridgeServer** (Ktor/Netty) | HTTP server | 8765 | Direct USB/LAN access (local only) |
| **RelayClient** (OkHttp) | WebSocket client | outbound | Connects to remote relay server |

### Key components

- **PairingManager** (`auth/PairingManager.kt`) — Generates and stores a 6-character alphanumeric code (excludes confusable chars: 0/O/1/I). Persists across restarts. User can regenerate from UI.
- **RelayClient** (`client/RelayClient.kt`) — WebSocket client that connects out to the relay. Auto-reconnects with exponential backoff (1s → 30s max, 5 retries). Dispatches incoming commands to `ActionExecutor` and `ScreenReader`, sends results back.
- **BridgeAccessibilityService** (`service/`) — Singleton `AccessibilityService`. Reads the UI tree on demand (not event-driven). Required for all screen interaction.
- **ActionExecutor** (`executor/ActionExecutor.kt`) — Performs taps (by coordinate or node ID), text typing, swipes, app launches, key presses, scrolling, and element waiting. Uses `WakeLockManager` to wake the device before actions.
- **ScreenReader** (`executor/ScreenReader.kt`) — Traverses the accessibility tree, builds `ScreenNode` hierarchy, finds nodes by text.

### Required permissions

- `ACCESSIBILITY_SERVICE` — core functionality
- `SYSTEM_ALERT_WINDOW` — status overlay
- `INTERNET` — WebSocket and HTTP server
- `WAKE_LOCK` — wake device for actions
- `FOREGROUND_SERVICE` — keep bridge alive

---

## Relay Server (Python — android_relay.py)

Runs as an aiohttp server in a background daemon thread. Started by `android_setup()`.

### Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/ws` | GET (WebSocket) | Phone connects here with `?token=<pairing_code>` |
| `/ping` | GET | Health check — also reports if phone is connected |
| `/screen` | GET | Read accessibility tree |
| `/screenshot` | GET | Capture screenshot |
| `/apps` | GET | List installed apps |
| `/current_app` | GET | Get foreground app |
| `/tap` | POST | Tap by coordinates or node ID |
| `/tap_text` | POST | Tap by text match |
| `/type` | POST | Type text |
| `/swipe` | POST | Swipe gesture |
| `/open_app` | POST | Launch app |
| `/press_key` | POST | Press system key |
| `/scroll` | POST | Scroll |
| `/wait` | POST | Wait for element |

### Auth and rate limiting

- Pairing code is **case-insensitive** (compared via `.upper()`)
- Failed auth attempts tracked per IP
- **5 failed attempts** within a **60-second window** → IP blocked for **5 minutes**
- Periodic cleanup of stale failure records (every 120s)
- Only one phone can be connected at a time (previous connection is kicked)

---

## Python Tools (14 total)

| Tool | Purpose |
|------|---------|
| `android_setup(pairing_code)` | Start relay, save config, return connection instructions |
| `android_ping()` | Check relay + phone connectivity |
| `android_read_screen(include_bounds)` | Read accessibility tree as JSON |
| `android_tap(x, y, node_id)` | Tap by coordinate or node ID |
| `android_tap_text(text, exact)` | Tap element by visible text |
| `android_type(text, clear_first)` | Type into focused input |
| `android_swipe(direction, distance)` | Swipe gesture |
| `android_scroll(direction, node_id)` | Scroll screen or element |
| `android_open_app(package)` | Launch app by package name |
| `android_press_key(key)` | Press system key (back, home, etc.) |
| `android_screenshot()` | Capture screenshot (saves to temp file, returns MEDIA: path) |
| `android_wait(text, class_name, timeout_ms)` | Poll for element to appear |
| `android_get_apps()` | List installed apps |
| `android_current_app()` | Get foreground app info |

### Config (environment variables)

| Variable | Default | Purpose |
|----------|---------|---------|
| `ANDROID_BRIDGE_URL` | `http://localhost:8766` | Relay URL (or direct phone URL for USB) |
| `ANDROID_BRIDGE_TOKEN` | *(none)* | Pairing code for auth headers |
| `ANDROID_RELAY_PORT` | `8766` | Port the relay listens on |
| `ANDROID_BRIDGE_TIMEOUT` | `30` | HTTP request timeout (seconds) |

---

## Setup Flow

1. Install bridge APK on Android device
2. Grant Accessibility Service + SYSTEM_ALERT_WINDOW permissions
3. User opens the bridge app → sees a 6-char pairing code
4. Agent calls `android_setup(pairing_code)` → starts relay, detects public IP
5. Agent relays `user_instructions` to user: enter server IP and pairing code in the phone app
6. User taps Connect in the phone app → phone connects to relay via WebSocket
7. Agent calls `android_ping()` to verify connection
8. All subsequent `android_*` tool calls route through the relay to the phone
