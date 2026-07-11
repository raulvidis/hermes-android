# Changelog

## 0.3.0 — public media fork line (2026-07-11)

### Added
- Background mic capture (`/mic_record`, `android_mic_record`)
- Background camera capture (`/camera_record`, `android_camera_record`) with Camera2 dual-looper
- Remote audio playback (`/play_remote_audio`) for server-generated TTS
- Optional ElevenLabs helper tool gated on `ELEVENLABS_API_KEY` (voice/model via env)
- Env-based APK signing (`HERMES_ANDROID_KEYSTORE*`)
- Env-tunable relay auth rate limits (`ANDROID_AUTH_*`)
- Docs: `docs/FORK_FEATURES.md`, `docs/DEPLOYMENT_WSS.md`, `docs/BUILDING_APK.md`

### Fixed
- Relay pairing code not applied when relay already running; auth ban clear on re-pair
- Client IP for rate limits behind reverse proxies (`X-Forwarded-For`)
- Samsung false camera open timeout when Camera2 callbacks shared blocked handler
- Front camera portrait orientation hint (OEM-dependent)

### Security
- No keystores/passwords/API keys in repository
- Documented wss-behind-proxy deployment; discourage public cleartext relay


All notable changes to this project are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/); this project adheres to Conventional Commits.

## [Unreleased]

### Security
- pairing code now sent as `Authorization: Bearer` header on the relay WebSocket handshake instead of a `?token=` query parameter (which leaks into reverse-proxy access logs); relay keeps legacy query fallback for older APKs
- redact PII-bearing fields (recipient, phone number, message/typed/clipboard text, intent extras) from relay debug body logs

### Fixed
- recycle intermediate ancestor AccessibilityNodeInfo nodes on the path to a match in ScreenReader.findNodeByTextDfs and ActionExecutor.findNodeByIdInTree — previously leaked on every tap_text/tap-by-id call, exhausting the accessibility node pool over long sessions

### Changed
- install.sh cleans its temp dir on all exit paths via trap
- document all 38 registered tools in the tools/android_tool.py module docstring; mark android_send_sms/android_call docstrings as destructive (confirm-first)
- `android_read_screen` now excludes System UI (status bar, nav bar) by default for token efficiency; pass `include_system_ui=true` to include it. Use `android_press_key` for back/home/recents (#34, @null-dev)
- screen hashes/diffs no longer churn on clock/battery updates since System UI is filtered from the tree by default (#34)

## [0.3.0]

### Added
- feat(bridge): add per-IP auth rate limiting to BridgeServer (#51)
- Notification listener — agent reads incoming notifications in real-time (`android_notifications` / `android_events`)
- Clipboard bridge — read/write clipboard between server and phone (`android_clipboard_read` / `android_clipboard_write`)
- Direct SMS and calls without UI navigation (`android_send_sms` / `android_call`)
- Location sharing — agent reads phone GPS location (`android_location`)
- hermes-agent v0.3 plugin system integration (`hermes-android-plugin/`, 38 tools)

### Changed
- wrap /wait handler in withContext(Dispatchers.Main) for thread-safety (#55)
- bind relay to localhost by default via ANDROID_RELAY_HOST env var (#38)
- cap ScreenRecorder duration to 30s to prevent OOM (#37)
- wrap ScreenRecorder.record() in Dispatchers.IO (#36)
- strip PII from makeCall and clipboardWrite responses (#35)
- downgrade relay body logging from INFO to DEBUG with truncation (#47, #33)
- strip recipient phone number from SMS success response (#28)
- convert ensureTts from CountDownLatch to suspend function (#27)

### Fixed
- fix(android_macro): halt on transport-level errors, not just success=false (#54)
- add runtime permission guard to location() (#49)
- null-check Bitmap.copy() result in takeScreenshot (#50)
- synchronize NotificationStore getAll/getSince/clear with lock (#45)
- replace ACTION_CUT with ACTION_SET_TEXT empty string in typeText clearFirst (#46)
- use exact-case comparison for pairing code auth (#43)
- align pressKey schema with actually supported keys (#44)
- synchronize EventStore clear(), getAll(), getSince() with lock (#41)
- synchronize NotificationStore.markRemoved() with lock (#42)
- fix ConcurrentLinkedDeque size/removeLast race condition (#39)
- remove dead code in diffScreen — unused currentTexts map (#40)
- recycle AccessibilityWindowInfo objects in /current_app handler (#30)
- recycle AccessibilityWindowInfo in findNodeById and readWidgets (#31)
- recycle AccessibilityWindowInfo in ScreenReader (#32)

[Unreleased]: https://github.com/raulvidis/hermes-android/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/raulvidis/hermes-android/releases/tag/v0.3.0
