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
- Repeated auth failures → IP blocked 5 min after 5 fails/60s. Wait or use a fresh IP.
- Pairing code is case-sensitive (#43) — match exactly as shown.

## Tools fail or return empty

- Always `android_read_screen()` before tapping — never guess coordinates.
- Accessibility tree empty/limited (canvas apps: Maps, Tinder, Spotify) → use `android_screenshot()` + coordinates.
- App blocks accessibility taps (some Uber builds) → fall back to screenshot + coordinates.
- `android_tap_text` misses → element text differs across OEM/locale; read screen to find actual label.

## Permissions

- "Service not enabled" → Settings > Accessibility > Hermes Bridge > ON.
- `android_location` errors → grant Location runtime perm (guarded since #49).
- `android_send_sms` / `android_call` / `android_search_contacts` error → grant SMS/Phone/Contacts; on AAOS these are unavailable by design.
- `android_notifications` empty → enable Notification access (Special app access).
- `android_screen_record` fails → grant MediaProjection; capped at 30s (#37); restricted on some OEM/AAOS.

## Relay / logging

- Body logging is DEBUG-only and truncated (#33, #47) — raise to DEBUG to inspect commands; never paste raw bodies into public issues (may contain screen content).

## Install warnings

- Play Protect warns on the unsigned debug APK — expected; not yet on a store.
