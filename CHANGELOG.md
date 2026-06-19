# Changelog

All notable changes to this project are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/); this project adheres to Conventional Commits.

## [Unreleased]

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
