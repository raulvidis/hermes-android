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
| **Python toolset** | `tools/`, `tests/` | Python | Runs on the server. 36 `android_*` tools + WebSocket relay. Also lives in [hermes-agent](https://github.com/NousResearch/hermes-agent) as the production copy |

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

Restart hermes — run `/plugins` to verify. Should show: `✓ hermes-android v0.3.0 (38 tools)`

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
- Tap **Grant Screen Recording** → approve the system dialog (needed for `android_screen_record`)
- Grant additional runtime permissions in **Settings > Apps > Hermes Bridge > Permissions**:
  - **Location** — for `android_location`
  - **Contacts** — for `android_search_contacts`
  - **SMS** — for `android_send_sms`
  - **Phone** — for `android_call` (direct dialing)
- Enable **Notification Listener** in **Settings > Apps > Special app access > Notification access** → enable Hermes Bridge (for `android_notifications` / `android_events`)

### 3. Connect to your Hermes server

Tell hermes (via Telegram, Discord, etc):
```
Connect to my phone, code is <CODE>
```
Where `<CODE>` is the 6-character pairing code shown in the app.

Hermes will reply with the server address. Enter it in the app and tap **Connect**.

### 4. Done
The agent can now control your phone. Try: "open Instagram", "take a screenshot", "what apps do I have?"

## Android Automotive OS (Car Head Units)

The bridge app can run on Android Automotive OS (AAOS) car head units. Phone-specific features (SMS, calls, contacts) gracefully return errors when the hardware is unavailable.

### Installation

1. Build the APK: `cd hermes-android-bridge && ./gradlew assembleDebug`
2. Sideload via ADB: `adb install app/build/outputs/apk/debug/app-debug.apk`
   - USB: connect directly to the head unit's USB port
   - WiFi: `adb connect <head-unit-ip>:5555` then install
3. Grant Accessibility Service: **Settings > Accessibility > Hermes Bridge > Enable**
4. Grant overlay permission: **Settings > Apps > Special access > Draw over apps > Hermes Bridge**
5. Skip phone-specific permissions (SMS, calls, contacts) — not applicable

### Connection

The car head unit needs network access to reach the relay server:

- **USB tethering** (recommended): `adb forward tcp:8766 tcp:8766`, then enter `http://localhost:8766` in the app
- **WiFi**: enter the relay server's `http://<ip>:8766` in the app (both devices on same network)

### Limitations

| Tool | Status on AAOS |
|------|---------------|
| `android_send_sms` | Not available — returns error |
| `android_call` | Not available — returns error |
| `android_search_contacts` | Not available — returns error |
| `android_location` | Depends on car unit GPS configuration |
| `android_screen_record` | May behave differently (restricted MediaProjection on some OEMs) |

All other tools (tap, swipe, type, screenshot, read screen, open apps, etc.) work normally.


## Tools (38)

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
| `android_long_press` | Long press by coordinates or node ID |
| `android_drag` | Drag from one point to another |
| `android_pinch` | Pinch zoom in/out at coordinates |
| `android_find_nodes` | Search accessibility nodes by text/class/clickable |
| `android_describe_node` | Get detailed info about a specific node |
| `android_screen_hash` | Get hash of current screen for change detection |
| `android_diff_screen` | Compare current screen against a previous hash |
| `android_location` | Get phone GPS location |
| `android_search_contacts` | Search contacts by name |
| `android_send_sms` | Send SMS to a phone number |
| `android_call` | Make a phone call or open dialer |
| `android_media` | Control media playback (play, pause, next, previous) |
| `android_send_intent` | Send an Android intent |
| `android_broadcast` | Send a broadcast intent |
| `android_clipboard_read` | Read clipboard contents |
| `android_clipboard_write` | Write text to clipboard |
| `android_notifications` | Read current notifications |
| `android_events` | Read recent accessibility events |
| `android_event_stream` | Stream accessibility events in real-time |
| `android_screen_record` | Record screen video for a duration |
| `android_read_widgets` | Read home screen widgets |
| `android_speak` | Text-to-speech output |
| `android_speak_stop` | Stop text-to-speech |

## Permissions

| Permission | How to Grant | Required For |
|------------|-------------|--------------|
| Accessibility Service | App button → Settings > Accessibility | All tools (core requirement) |
| System Alert Window (Overlay) | App button → Settings > Draw over apps | Status overlay display |
| Screen Recording (MediaProjection) | App button → approve system dialog | `android_screen_record` |
| Location | Settings > Apps > Permissions > Location | `android_location` |
| Read Contacts | Settings > Apps > Permissions > Contacts | `android_search_contacts` |
| Send SMS | Settings > Apps > Permissions > SMS | `android_send_sms` |
| Call Phone | Settings > Apps > Permissions > Phone | `android_call` (auto-dial) |
| Notification Listener | Settings > Special app access > Notification access | `android_notifications`, `android_events` |

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
- [x] **Notification listener** — agent reads incoming notifications in real-time
- [x] **Clipboard bridge** — copy/paste between server and phone
- [ ] **File transfer** — send files/photos between phone and server
- [x] **Direct SMS/calls** — send texts and make calls without navigating the UI
- [x] **Location sharing** — agent knows where the phone is for contextual tasks

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
