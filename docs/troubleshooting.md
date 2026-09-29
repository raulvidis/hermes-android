---
summary: "Known issues and workarounds for connection, permissions, and tool failures."
read_when:
  - "A connection, permission, or tool call is failing"
  - "Diagnosing flaky or surprising on-device behavior"
---

# Troubleshooting

## Phone won't connect

- `android_ping()` shows no phone → confirm the app shows "Connected"; re-enter server address + code.
- Behind NAT? Relay mode is correct (phone connects out). Don't port-forward.
- Direct USB: run `adb forward tcp:8765 tcp:8765`, set `ANDROID_BRIDGE_URL=http://localhost:8765`.
- Repeated auth failures → IP blocked 5 min after 5 fails/60s. Wait or use a fresh IP. The bridge enforces the same policy on `:8765`, so a brute-forced direct connection is throttled too.
- Pairing code is case-sensitive (#43) — match exactly as shown.
- Regenerated the code in the app? A relay that is already running keeps validating the **previous** code and answers `403`; restart the hermes process (or the systemd relay unit) and re-run `android_setup` with the new code. Tracked in `docs/refactor/2026-09-29-commit-review.md` (F2).
- TLS on? The app must be given an `https://`/`wss://` address and the certificate must be CA-trusted — the bridge uses the platform trust store, so a self-signed cert fails the handshake with no bypass. Drop `ANDROID_RELAY_CERT`/`ANDROID_RELAY_KEY` to serve plaintext again.

## Tools fail or return empty

- Always `android_read_screen()` before tapping — never guess coordinates.
- Accessibility tree empty/limited (canvas apps: Maps, Tinder, Spotify) → use `android_screenshot()` + coordinates.
- App blocks accessibility taps (some Uber builds) → fall back to screenshot + coordinates.
- `android_tap_text` misses → element text differs across OEM/locale; read screen to find actual label.
- `android_press_key` returns "Key 'x' is not supported via AccessibilityService global actions" → only `back`, `home`, `recents`, `notifications`, `quick_settings`, `power`, `lock_screen`, `take_screenshot` and `wake` exist. Volume and keyboard keys (`volume_up`, `volume_down`, `enter`, `delete`, `tab`, `escape`, `search`) are rejected by design — tap the on-screen control instead.
- `lock_screen` / `take_screenshot` report "requires Android 9+ (API 28)" → expected below API 28; `minSdk` is 26.
- `wake` fails with "Accessibility service not running" → the wake path goes through `press_key`, which needs the service even though the wake lock itself does not. Re-enable Accessibility.
- "Unknown command: GET /x" with a 404 → the path is not in the relay's `_ROUTES` allowlist (`tools/android_relay.py`), so the relay refuses it before the phone ever sees it. Direct USB/LAN on `:8765` would answer it; the relay will not until the route is allowlisted and `docs/architecture.md` updated (a test pins the two together). A 405 means the path is allowlisted but not for that method.
- `504` "Phone did not respond within 30s" → the phone is connected but the command never came back (long gesture, dead accessibility service, device asleep). `502` means the send failed or the phone dropped mid-command; `503` means no phone is connected at all. The tool-side timeout (`ANDROID_BRIDGE_TIMEOUT`, also 30s by default) fires at about the same time, so raise both together when a command legitimately needs longer.
- Battery reads return 503 "Battery state unavailable on this device" → `BatteryManager` gave no usable value (some emulators and AAOS units). `GET /battery` has no `android_*` tool wrapper; call it over raw HTTP.
- `android_notifications` looks empty although the shade has entries → dismissed notifications are excluded by default; pass `include_removed=true` to see retained ones (they carry `removedAt`).

## Permissions

- "Service not enabled" → Settings > Accessibility > Hermes Bridge > ON.
- `android_location` errors → grant Location runtime perm (guarded since #49).
- `android_send_sms` / `android_call` / `android_search_contacts` error → grant SMS/Phone/Contacts; on AAOS these are unavailable by design.
- `android_notifications` empty → enable Notification access (Special app access).
- `android_screen_record` fails → grant MediaProjection; capped at 30s (#37); restricted on some OEM/AAOS.
- `android_send_intent` / `android_broadcast` return "blocked for safety" → intended. Intents: package install/uninstall, factory reset/shutdown, direct `CALL`, every `android.settings.*` and `android.provider.action.*` action, and the `intent`, `market`, `smsto`, `mmsto`, `tel` URI schemes plus `content://settings` / `content://com.android.contacts`. Broadcasts: media mounted/unmounted/eject/scanner, package added/removed/replaced/data-cleared, storage low/ok, shutdown, master clear, factory reset. Use the dedicated tools (`android_send_sms`, `android_call`, `android_open_app`) or navigate Settings by tapping.
- `android_send_sms` / `android_call` on a device without telephony → `success: false` with "not available on this device" at status 200 (capability-gated, not an error).

## Termux auto-revival

Only relevant with the opt-in **Termux Auto-Revival** switch on. See `docs/configuration.md` for the full contract.

- Status shows "Revival blocked — allow Termux RUN_COMMAND (Termux > Allow external apps)" → `startService` threw `SecurityException`. Grant Termux > Settings > *Allow external apps*; declaring `com.termux.permission.RUN_COMMAND` in the manifest is not enough.
- Status shows "Stack unreachable — firing Termux revival (1/3)…" but nothing restarts → the script is not at the path the bridge uses, `RelayClient.DEFAULT_REVIVAL_SCRIPT` = `/data/data/com.termux/files/home/.termux/boot/start-hermes.sh`. The path is not configurable and has no UI, so create that exact file (absolute, inside Termux's home, executable) or leave the switch off. Check Termux's own log for the `RUN_COMMAND` error.
- Status shows "Revival stopped after 3 attempts — check the server URL and pairing code, then tap Connect" → the fire budget is spent. This is deliberate: the trigger is "the relay is unreachable", which a wrong server URL, a rotated pairing code or a server that is simply down all look like, and restarting Termux cannot fix any of them. Fix the cause, then reconnect — a session that opens refills the budget.
- The relay is unreachable and the watchdog never fires → an HTTP 401/403 is excluded on purpose (a credential problem, not a dead stack). So is a disabled switch, which is the default.

## Microphone

- "duration must be between 0 and 1800 seconds" → `duration` must be an integer number of seconds, `0` (record until `android_mic_stop`) through `1800`. `android_mic_record` checks this client-side and never calls the bridge; the bridge answers `400` (adding "duration must be an integer number of seconds" for a non-numeric value) when `/mic_start` is called directly.
- `403 Microphone permission is not granted` → grant it in Settings > Apps > Hermes Bridge > Permissions.
- `409 A microphone recording is already active` → call `android_mic_stop()` first. If it persists after a crash, the recorder state is stuck; restarting the app clears it (#98 fixed the two paths that caused this).
- `android_mic_status()` shows `phase: error` right after a stop → transient while the service finalises; re-poll, then `android_mic_fetch()` — the completed WAV is listed under `latest` regardless of phase.
- `android_mic_fetch(remote_path=...)` says "remote_path must be a WAV filename, not a path" → the tool rejects anything that is not a bare `.wav` basename before it calls the bridge.
- `android_mic_fetch()` returns `{"error": "Recording not found"}` (HTTP 404) → that name is not a completed recording on the device: it left the 10-newest retention window, or it is still a `.part` file. The bridge resolves only generated `.wav` basenames inside its recordings directory; paths, `..`, absolute names and non-`.wav` names are refused.
- `android_mic_fetch()` returns "Could not download microphone recording from the bridge" → connection-level failure (bridge unreachable or timed out). Host and port are deliberately not echoed.
- "Microphone recording exceeds the download limit" → over 256 MB, either by `Content-Length` or by bytes actually received.

## Relay / logging

- Body logging is DEBUG-only and truncated (#33, #47) — raise to DEBUG to inspect commands; never paste raw bodies into public issues (may contain screen content).

## Install warnings

- Play Protect warns on the unsigned debug APK — expected; not yet on a store.
