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

## AAOS (if applicable)

20. SMS/calls/contacts return graceful errors; tap/type/screenshot work.
