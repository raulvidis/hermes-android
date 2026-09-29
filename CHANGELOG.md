# Changelog

All notable changes to this project are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/); this project adheres to Conventional Commits.

## [Unreleased]

### Added
- JVM unit coverage for previously untested bridge classes: `EventStore` (ring-buffer eviction and orientation, newest-first ordering, the `since` cursor, streaming-flag semantics, concurrent add/read), `BatteryMonitor` (an unreadable, out-of-range or throwing battery service must read as `null` — never a guessed number), `ScreenRecorder` (precondition ordering, single-use projection-token handling, the 30 s duration cap, temp-file cleanup, serialised handler-thread state machine), `BridgeNotificationListener` (instance publication on connect/disconnect, the 300-entry cap, parse-or-drop on post, mark-not-delete on remove) and `ScreenNode`/`NodeBounds.computeHash`. 117 tests.
- Three latent defects surfaced by that coverage, deliberately **not** fixed here — each carries an `@Ignore("BUG: ...")` test with the assertion the fix should satisfy:
  - `EventStore.add()` calls `removeLast()` on an empty deque when `maxCapacity <= 0`, so the first event after such an assignment throws `NoSuchElementException` out of `onAccessibilityEvent` instead of being dropped. Not reachable today (nothing in the app writes `maxCapacity`), but the knob is public.
  - `ScreenRecorder.record()` never releases the `MediaRecorder` when its configuration or `prepare()` throws: `recorder = mr` is assigned only after the `apply {}` block returns, so `cleanupRecording()` in the catch sees a null field and the native recorder (encoder + projection client) leaks. Reachable whenever `prepare()` fails — codec unavailable, storage full, invalid surface.
  - `computeHash()` joins fields with `|` without escaping the separator, so field content containing `|` shifts the layout and two genuinely different nodes hash identically — a hole in the value `/screen_diff` uses to decide nothing changed. Any fix changes every hash, so it needs its own decision.
- `ScreenRecorder` gained an `internal maxDurationMs` testability seam (default unchanged at 30 s), same pattern as `CommandDispatcher.batteryContext`: the cap is otherwise only observable by waiting out a full capped recording.

### Added
- `GET /battery` — battery percentage and charge state straight from `BatteryManager` (`getIntProperty(BATTERY_PROPERTY_CAPACITY)`, API 21; `isCharging()`, API 23) instead of scraping the status-bar accessibility node, which mis-reads across OEM skins and device languages. Neither value depends on the accessibility service running, and at `minSdk 26` neither needs a version gate. Fixed response shape — `batteryPercentage` and `charging`, both always present — because the HTTP and relay transports serialise nulls differently (#103).

### Fixed
- Relay route allowlist lifted out of `_serve()` to module level (`_ROUTES`) so tests can assert on it. `docs/architecture.md` is now pinned to the allowlist in both directions, and the two `android_relay.py` copies are pinned byte-identical. An endpoint added to `CommandDispatcher` without the matching allowlist entry used to fail silently: reachable over direct USB/LAN, refused over the relay — which is the default transport (#103).

## [0.5.0] - 2026-09-05

### Added
- Termux auto-revival watchdog (#100): after repeated relay failures, an **opt-in** (default OFF) switch fires the Termux `RUN_COMMAND` intent to restart the stack, then schedules a delayed reconnect. Pure `RevivalPolicy` gate/counter/cooldown logic with unit tests; `SecurityException` surfaces visibly instead of being swallowed.
- `include_removed` option on `android_notifications` (#100): dismissed notifications are retained on-device but excluded by default — callers must opt in explicitly to read notifications the user has cleared.
- Notification buffer raised from 100 to 300 entries (#100).
- `android_press_key("wake")` turns the screen on via a short auto-releasing wake lock. `power` is unchanged and still opens the long-press power dialog (#101).
- `android_*` tools fall back to reading `ANDROID_BRIDGE_URL`/`ANDROID_BRIDGE_TOKEN` from `~/.hermes/.env` when the gateway process does not export them (`os.environ` still takes precedence) (#101).
- Four microphone tools (`android_mic_record`, `android_mic_stop`, `android_mic_status`, `android_mic_fetch`) with relay-routed binary WAV streaming and `MEDIA:` delivery.
- Recorder state reporting and atomic `.part` → `.wav` finalization so interrupted recordings are never advertised as complete.
- Keep-last-10 retention for completed WAV recordings.

### Fixed
- Mic recorder state machine can no longer stick at `starting` after an early STOP or service destruction — `/mic_start` returned 409 until app restart before (#98).
- Plugin now registers the android usage skill via `ctx.register_skill()`, so agents discover the accessibility-tree-first guidance instead of defaulting to screenshot + vision. Screenshot tool description defers to `android_read_screen`/`android_find_nodes`.
- Use one canonical microphone directory for recording, status, and download.
- Use the on-device-tested `VOICE_RECOGNITION` source with saturating 2.5x PCM gain and stop the recorder service after a STOP command.
- Register microphone routes, schemas, and handlers in both the standalone toolset and installable plugin.
- Replace deprecated aiohttp bare route handlers and add end-to-end binary-stream regression coverage.

### Security
- `android_mic_fetch` connection errors no longer echo the bridge host:port from `requests` exception text (#99).
- Regression coverage for the security-critical mic paths (#99): `MicrophoneRecordingFiles.resolve()` traversal/symlink defense, relay binary-stream negative paths (checksum/length/oversize/disconnect), and the 30-minute sample cap.
- Remove device-specific connection details and legacy SCP instructions from microphone tooling.
- Stop logging notification content and pairing-token prefixes, including in debug builds.

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

[Unreleased]: https://github.com/raulvidis/hermes-android/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/raulvidis/hermes-android/releases/tag/v0.5.0
[0.4.1]: https://github.com/raulvidis/hermes-android/releases/tag/v0.4.1
[0.4.0]: https://github.com/raulvidis/hermes-android/releases/tag/v0.4.0
[0.3.0]: https://github.com/raulvidis/hermes-android/releases/tag/v0.3.0
