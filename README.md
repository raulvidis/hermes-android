# hermes-android

Give your AI agent hands. Remote Android device control for [hermes-agent](https://github.com/NousResearch/hermes-agent).

## How it works

```
Phone (home WiFi)  ──WebSocket──>  Hermes Server (cloud)  <──HTTP──  AI Agent
                                   relay on port 8766
```

The phone connects **out** to your Hermes server — works behind any NAT, no port forwarding, no VPN, no USB. Just a 6-character pairing code.

## Repository Structure

This repo contains **two components**:

| Component | Path | Language | Purpose |
|-----------|------|----------|---------|
| **Android bridge app** | `hermes-android-bridge/` | Kotlin | Runs on the phone. Connects to server via WebSocket, executes commands via AccessibilityService |
| **Python toolset** | `tools/`, `tests/` | Python | Runs on the server. 14 `android_*` tools + WebSocket relay. Also lives in [hermes-agent](https://github.com/NousResearch/hermes-agent) as the production copy |

> **Note:** The Python code exists here for standalone development and testing (`pip install -e .`, `pytest`). The production copy is in the hermes-agent repo. The Android app does not use or depend on the Python files.

## Install as hermes-agent plugin (v0.3.0+)

```bash
curl -sSL https://raw.githubusercontent.com/raulvidis/hermes-android/main/install.sh | bash
```

Or manually:
```bash
mkdir -p ~/.hermes/plugins
cp -r hermes-android-plugin ~/.hermes/plugins/hermes-android
```

Restart hermes — run `/plugins` to verify. Should show: `✓ hermes-android v0.2.0 (14 tools)`

## Quick Start

### 1. Install the bridge app on your phone

Build with Android Studio or from command line:
```bash
cd hermes-android-bridge
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Grant permissions on the phone
- Open Hermes Bridge app
- Tap **Enable Accessibility Service** → find Hermes Bridge → toggle ON
- Tap **Enable Status Overlay** → grant permission

### 3. Connect to your Hermes server

Tell hermes (via Telegram, Discord, etc):
```
Connect to my phone, code is <CODE>
```
Where `<CODE>` is the 6-character pairing code shown in the app.

Hermes will reply with the server address. Enter it in the app and tap **Connect**.

### 4. Done
The agent can now control your phone. Try: "open Instagram", "take a screenshot", "what apps do I have?"

## Tools (14)

| Tool | Description |
|------|-------------|
| `android_setup` | Start relay and configure pairing code |
| `android_ping` | Check if phone is connected |
| `android_read_screen` | Get accessibility tree of current screen |
| `android_screenshot` | Capture screenshot and send to user |
| `android_tap` | Tap by coordinates or node ID |
| `android_tap_text` | Tap element by visible text |
| `android_type` | Type into focused input field |
| `android_swipe` | Swipe up/down/left/right |
| `android_scroll` | Scroll screen or element |
| `android_open_app` | Launch app by package name |
| `android_press_key` | Press back, home, recents, etc. |
| `android_wait` | Wait for element to appear |
| `android_get_apps` | List installed apps |
| `android_current_app` | Get foreground app info |

## Architecture

**Android app (Kotlin):**
- AccessibilityService reads the UI tree and performs taps/types/swipes
- WebSocket client (OkHttp) connects out to the Hermes server
- Ktor HTTP server for local/USB development
- Pairing code authentication
- Screenshot capture via AccessibilityService API
- Terminal-themed UI

**Server (Python):**
- WebSocket + HTTP relay (aiohttp) on port 8766
- Tools register into hermes-agent's tool registry
- Rate-limited authentication (5 attempts / 60s, then 5min block)
- Auto-detects server public IP for setup instructions

## Security

See [SECURITY.md](SECURITY.md) for details. Key points:
- Pairing code authentication with rate limiting
- Phone connects out (never directly exposed)
- Currently unencrypted (`ws://`) — use TLS proxy for production
- Full device access once paired — only connect to trusted servers

## Development

```bash
# Python tests
pip install -e ".[dev]"
python -m pytest tests/

# Android build
cd hermes-android-bridge
./gradlew assembleDebug
```

## Roadmap

This is a working prototype. The vision: **give Hermes its own phone** — a fully autonomous mobile presence.

### v0.2 — Polish & Reliability
- [ ] TLS/WSS support for encrypted phone-server communication
- [ ] Persistent relay service (systemd unit, auto-start with gateway)
- [ ] Server-side call counter to prevent tool call loops
- [ ] Better error reporting (screenshot + annotated explanation on failure)
- [ ] Auto-reconnect relay on gateway restart

### v0.3 — Richer Phone Interaction
- [ ] **Notification listener** — agent reads incoming notifications in real-time
- [ ] **Clipboard bridge** — copy/paste between server and phone
- [ ] **File transfer** — send files/photos between phone and server
- [ ] **Direct SMS/calls** — send texts and make calls without navigating the UI
- [ ] **Location sharing** — agent knows where the phone is for contextual tasks

### v0.4 — Multi-Device & Automation
- [ ] **Multiple phones** — connect more than one device to the same relay
- [ ] **Scheduled automations** — "every morning, check my commute price on Bolt"
- [ ] **Event triggers** — "when a notification arrives from this app, do X"
- [ ] **Macro recording** — watch a workflow once, replay it on demand

### v0.5 — Hermes Gets a Voice
- [ ] **Phone call capability** — agent can answer and speak in phone calls using TTS/STT
- [ ] **Voice assistant mode** — always-listening on the phone, responds via speaker
- [ ] **Call handling** — "answer my phone, take a message, tell them I'll call back"

### v0.6 — On-Device Intelligence
- [ ] **Local model execution** — run small models (Qwen 0.5B, Gemma 2B) directly on the phone
- [ ] **Offline fallback** — basic commands work without server connection using on-device model
- [ ] **Hybrid routing** — simple tasks run locally, complex tasks go to the server
- [ ] **On-device app adapters** — fast structured parsing without round-tripping to server

### Future Ideas
- [ ] iOS support via Shortcuts/accessibility bridge
- [ ] Web dashboard for monitoring phone activity
- [ ] Cross-app workflows ("find a restaurant on Maps, share on WhatsApp, book an Uber there")
- [ ] Dedicated "Hermes Phone" — a phone that boots straight into agent mode

## Links

- **hermes-agent**: [github.com/NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent)
