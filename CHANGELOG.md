# Changelog

All notable changes to this project are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/); this project adheres to Conventional Commits.

## [Unreleased]

### Removed
- Offline Mode (full) toggle — the on-device Lighter Zee brain was retired
  (2026-09-03, cloud-only decision). Deleted `OfflineToggler.kt`, the dashboard
  switch and its state wiring, and the layout rows (portrait + landscape).
  **v0.4.2** — redo after the offline brain removal so the app no longer promises
  a mode that doesn't exist.

### Added
- `android_press_key("wake")` turns the screen on via a short auto-releasing wake lock. `power` is unchanged and still opens the long-press power dialog (#101).
- `android_*` tools fall back to reading `ANDROID_BRIDGE_URL`/`ANDROID_BRIDGE_TOKEN` from `~/.hermes/.env` when the gateway process does not export them (`os.environ` still takes precedence) (#101).

### Fixed
- Mic recorder state machine can no longer stick at `starting` after an early STOP or service destruction — `/mic_start` returned 409 until app restart before (#98).
- Plugin now registers the android usage skill via `ctx.register_skill()`, so agents discover the accessibility-tree-first guidance instead of defaulting to screenshot + vision. Screenshot tool description defers to `android_read_screen`/`android_find_nodes`.

### Security
- `android_mic_fetch` connection errors no longer echo the bridge host:port from `requests` exception text (#99).
- Regression coverage for the security-critical mic paths (#99): `MicrophoneRecordingFiles.resolve()` traversal/symlink defense, relay binary-stream negative paths (checksum/length/oversize/disconnect), and the 30-minute sample cap.

### Added
- four microphone tools (`android_mic_record`, `android_mic_stop`, `android_mic_status`, `android_mic_fetch`) with relay-routed binary WAV streaming and `MEDIA:` delivery
- recorder state reporting and atomic `.part` → `.wav` finalization so interrupted recordings are never advertised as complete
- keep-last-10 retention for completed WAV recordings

### Security
- remove device-specific connection details and legacy SCP instructions from microphone tooling
- stop logging notification content and pairing-token prefixes, including in debug builds

### Fixed
- use one canonical microphone directory for recording, status, and download
- use the on-device-tested `VOICE_RECOGNITION` source with saturating 2.5x PCM gain and stop the recorder service after a STOP command
- register microphone routes, schemas, and handlers in both the standalone toolset and installable plugin
- replace deprecated aiohttp bare route handlers and add end-to-end binary-stream regression coverage

## [0.4.1] - 2026-08-09

### Fixed
- relay reconnect: an unreachable server address retried forever. Each failed attempt fired `onFailure`, which cancelled the retry coroutine and started a new one with a fresh counter, so the 5-attempt cap was never reached and the UI stayed stuck on "Connecting…". Attempt count and exponential backoff now live in a `ReconnectPolicy` whose budget survives across callbacks. The budget is refilled only by a user-initiated Connect or by a session that stayed up ≥60s — reaching `onOpen` is not enough, so a relay that accepts the socket and immediately drops it (wrong pairing code) also stops instead of flapping forever. Callbacks from superseded sockets are ignored via a generation guard, `onClosed`/`onFailure` for one dead socket can no longer schedule two attempts, and the exhaustion path retires the socket so a late callback can't overwrite the "Tap Connect to retry" status
- version string on the main view was hardcoded to `v0.2.0` in both portrait and landscape layouts; now dynamically reads from `BuildConfig.VERSION_NAME`

## [0.4.0] - 2026-08-03

### Security
- sendIntent: block dangerous activity-launch actions (CALL, CALL_PRIVILEGED, ~20 sensitive settings screens) plus an `android.settings.*` prefix catch-all so new settings actions are denied by default (#89, extended by audit)
- sendIntent: URI scheme denylist (`intent://`, `market://`, `tel:`, `smsto:`, `mmsto:`) and `content://settings` / contacts provider prefixes close scheme-based bypasses of the action blocklist; fixes single-colon scheme extraction that let `tel:123` through (#91)
- sendBroadcast: blocklist for dangerous broadcast actions (#80) incl. system-destructive ones (shutdown / master_clear / factory_reset) (#87)
- searchContacts: URL-encode the query to prevent URI injection (#84)
- pairing code now sent as `Authorization: *** header on the relay WebSocket handshake instead of a `?token=` query parameter (which leaks into reverse-proxy access logs); `?token=` fallback fully removed from relay WS auth and plugin copy (#78, #83)
- redact PII-bearing fields (recipient, phone number, message/typed/clipboard text, intent extras) from relay debug body logs
- unauthenticated `/ping` no longer discloses the bridge version (#81)

### Fixed
- recycle intermediate ancestor AccessibilityNodeInfo nodes on the path to a match in ScreenReader.findNodeByTextDfs and ActionExecutor.findNodeByIdInTree — previously leaked on every tap_text/tap-by-id call, exhausting the accessibility node pool over long sessions

### Changed
- docs/install.md is now the single end-to-end install doc: added the on-phone permission-grant checklist and a "Persistent relay (systemd)" section documenting the contrib/ daemon + unit file; README points there and tool count fixed (36 → 38)
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
