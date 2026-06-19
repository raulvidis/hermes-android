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

## Sensitive paths (verify PII stripping)

10. `android_send_sms` / `android_call` → success response contains NO phone number/recipient.
11. `android_location` → guarded if permission absent (#49).
12. `android_clipboard_write` response strips content per convention (#35).

## Auth / rate limiting

13. 5 wrong pairing codes within 60s → IP blocked 5 min, HTTP 429 (#51).
14. Correct code → connects.

## AAOS (if applicable)

15. SMS/calls/contacts return graceful errors; tap/type/screenshot work.
