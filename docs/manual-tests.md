---
summary: "On-device manual test procedures for the bridge."
read_when:
  - "Before cutting a release"
  - "After changing the bridge app or relay"
---

# Manual Tests

Automated: `pytest tests/` (Python toolset) and `cd hermes-android-bridge && ./gradlew lint test` (Kotlin). The procedures below need a real device or AAOS head unit.

## Connection

1. Install fresh APK; grant Accessibility + overlay.
2. `android_setup("<code>")` → relay starts, returns `user_instructions`.
3. Enter server address + code in app → Connect.
4. `android_ping()` → reports phone connected.
5. Kill WiFi briefly → confirm auto-reconnect (exponential backoff).

## Core interaction

6. `android_open_app("com.android.settings")` then `android_read_screen()` → tree populated.
7. `android_tap_text(...)`, `android_type(...)`, `android_swipe(...)` → verify on overlay/screen.
8. `android_screenshot()` → returns `MEDIA:` path; image correct.
9. `android_wait(text=...)` resolves after navigation.

## Microphone (real device)

10. Grant the Hermes Bridge microphone permission, connect the relay, then press Home so the app UI is backgrounded.
11. From the agent host, call `android_mic_record(duration=3)`; it must return `starting` and show the foreground-service notification despite the app UI being backgrounded.
12. Wait for `android_mic_status()` to report `ready`, then call `android_mic_fetch()`; verify the returned `MEDIA:` file is a playable 16 kHz mono WAV with audible speech.
13. Start with `android_mic_record(duration=0)`, call `android_mic_stop()`, wait for `ready`, then verify `adb shell dumpsys activity services com.hermesandroid.bridge` no longer lists `MicrophoneRecorderService`.
14. Create more than 10 short recordings; `android_mic_status()` must report at most 10 completed WAVs and the newest recording must remain downloadable.

## Sensitive paths (verify PII stripping)

15. `android_send_sms` / `android_call` → success response contains NO phone number/recipient.
16. `android_location` → guarded if permission absent (#49).
17. `android_clipboard_write` response strips content per convention (#35).

## Auth / rate limiting

18. 5 wrong pairing codes within 60s → IP blocked 5 min, HTTP 429 (#51).
19. Correct code → connects.

## Keys and device state

20. Screen off → `android_press_key("wake")` turns the screen on and does **not** open the power dialog; `android_press_key("power")` still opens the long-press power menu (#101).
21. `android_press_key("lock_screen")` and `("take_screenshot")` succeed on Android 9+; on Android 8 they return "requires Android 9+ (API 28)".
22. `android_press_key("enter")` returns `success: false` with "not supported via AccessibilityService global actions" — same for `volume_up`, `volume_down`, `delete`, `tab`, `escape`, `search`.
23. `GET /battery` with the Bearer token → `{"batteryPercentage": 0-100, "charging": true|false}`, both keys always present, and the same body over the relay as over direct `:8765`. On a device with no usable battery service it returns 503.

## Guard rails

24. `android_send_intent("android.settings.WIFI_SETTINGS")` → blocked; `android_send_intent("android.intent.action.SENDTO", data_uri="smsto:<number>")` → blocked URI scheme; `android_broadcast("android.intent.action.MEDIA_MOUNTED")` → blocked. A custom-namespace action with an `https://` data URI still succeeds.
25. Clear the notification shade → `android_notifications()` omits the cleared items; `android_notifications(include_removed=true)` returns them with a `removedAt` field (#100 follow-up).
26. `GET /mic_file?name=../../etc/hosts` and `?name=/sdcard/x.wav` → 404, and no file outside the recordings directory is ever served.

## Microphone error paths

27. `android_mic_record(duration=-1)` and `android_mic_record(duration="abc")` → the **tool** refuses both with `{"error": "duration must be between 0 and 1800 seconds"}` and never calls the bridge; `POST /mic_start` directly with the same values → HTTP 400. A second `android_mic_record()` while one is active → 409; with the microphone permission revoked → 403.
28. `android_mic_status()` immediately after `android_mic_stop()` → re-poll until `phase` settles on `ready` (or `error`), then `android_mic_fetch()`; a good recording must not stay reported as `error`.

## Relay transport

29. With `ANDROID_RELAY_CERT` and `ANDROID_RELAY_KEY` exported before the relay starts, enter `https://<server>:8766` in the app → it connects over `wss://` and tools keep working. With a self-signed certificate the handshake must fail (platform trust store, no bypass).
30. Regenerate the pairing code in the app, re-run `android_setup("<new code>")`, then connect → the phone must be accepted. **Known failure:** a relay that is already running keeps validating the previous code and answers 403 until the process restarts; see `docs/refactor/2026-09-29-commit-review.md` (F2).

## Termux auto-revival (opt-in)

31. Fresh install → the "Termux Auto-Revival (opt-in)" switch is OFF. With it ON and an unreachable relay, the `RUN_COMMAND` intent fires only after 3 exhausted reconnect cycles and at most once per 10 minutes, a reconnect is attempted ~30s later, and a missing Termux permission surfaces as a visible status rather than a silent failure (#100).

## AAOS (if applicable)

32. SMS/calls/contacts return graceful errors; tap/type/screenshot work.
