# Community projects

Projects built by the community around hermes-android. These are third-party
and **not reviewed or maintained** by this repo — read their code and security
notes before pointing your phone or relay at them.

Want your project listed? Open a PR adding it below.

## Tools

- **[Sentinel Phone Console](https://github.com/rozeappletree/phone-control-dashboard)** by [@rozeappletree](https://github.com/rozeappletree)
  A LAN dashboard and WebSocket interposer between the phone and the relay: live plan/execution feed, phone screenshots, a server-enforced Pause / Step / Resume gate (starts paused), and an emergency connection kill.
  Writes local, redacted JSONL session logs with a metadata-only training-data exporter (no screenshots, accessibility trees, or typed text).
  Plain HTTP — trusted Wi-Fi only. Depends on the relay's WebSocket protocol, so relay changes may break it. (#110)
