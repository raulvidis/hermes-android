# Fork features (public)

This repository is a **community-friendly fork/extension** of
[raulvidis/hermes-android](https://github.com/raulvidis/hermes-android).

It remains a **remote Android control bridge** for [Hermes Agent](https://github.com/NousResearch/hermes-agent)
(AccessibilityService + WebSocket relay). It is **not** a chat client.

Upstream credit: all core bridge architecture, pairing model, and tool surface belong to the original project.
This fork adds media capture, remote TTS playback plumbing, relay robustness, and packaging notes.

## What this fork adds

### Bridge APK (`hermes-android-bridge`)

| Feature | Description |
|---|---|
| Background **microphone** capture | `POST /mic_record` → AAC/M4A clip (Foreground Service + `RECORD_AUDIO`) |
| Background **camera** capture | `POST /camera_record` → H.264/MP4 clip, `back`/`front`, optional mic |
| Remote audio playback | `POST /play_remote_audio` — play base64 audio (e.g. server-side TTS) on the device |
| Camera2 dual-looper | Avoids Samsung-style false `Camera open timed out` when callbacks share a blocked handler |
| Front camera orientation hint | Portrait front uses `270°` vs rear `90°` (OEM-dependent; still verify on device) |
| Env-based signing | Optional `HERMES_ANDROID_KEYSTORE*` env vars for reproducible release/debug distribution builds |

Android will show **camera/microphone indicators** during capture. Silent unrestricted recording without OS indicators is **not** supported on stock Android.

### Python plugin / tools (`hermes-android-plugin`, `tools/`)

| Feature | Description |
|---|---|
| `android_mic_record` | Tool wrapper → MEDIA m4a |
| `android_camera_record` | Tool wrapper → MEDIA mp4 |
| `android_speak_default_tts` | Optional: synthesize with ElevenLabs **if** `ELEVENLABS_API_KEY` is set, then play via `/play_remote_audio` |
| Relay pairing updates | Updating pairing on an already-running relay + clearing auth bans |
| `X-Forwarded-For` client IP | Correct rate-limit identity behind reverse proxies |
| Configurable auth rate limits | `ANDROID_AUTH_MAX_ATTEMPTS`, `ANDROID_AUTH_WINDOW_SECONDS`, `ANDROID_AUTH_BLOCK_SECONDS` |
| Live relay re-pair | `start_relay` updates pairing when already running; `clear_auth_blocks()` drops 429 bans |
| X-Forwarded-For client IP | Rate limits use real client IP behind reverse proxies |
| Public URL instructions | Prefer `ANDROID_PUBLIC_URL` (HTTPS reverse proxy) in setup instructions |

### Security / ops notes

- Pairing codes are short shared secrets — treat them like passwords; rotate on leak.
- Prefer TLS termination on a reverse proxy (`wss://`) with the relay bound to localhost.
- Do not commit keystores, `.env`, pairing tokens, phone numbers, screenshots, or personal hostnames.
- Destructive actions (SMS, calls) should still be confirmed by the operator/agent policy.

## Versioning

- APK `versionName` **0.3.0** marks the first public fork media line in this tree.
- Upstream APK versions and Python plugin versions may differ; see `CHANGELOG.md`.

## Relationship to upstream

```text
upstream  = raulvidis/hermes-android   (origin when forking)
fork      = this repo                  (media + relay hardening)
```

Recommended contribution style:

1. Keep **generic fixes** (Camera2 looper, XFF, pairing update) cherry-pickable as PRs upstream.
2. Keep **optional integrations** (ElevenLabs) behind env vars with no secrets in git.
3. Periodically `git fetch upstream && git merge upstream/main` (or rebase) and resolve conflicts carefully around plugin layout.

## Privacy

This document intentionally avoids personal IPs, domains, accounts, and keys.
Configure your own `ANDROID_PUBLIC_URL`, TLS certificates, and API keys in deployment env only.
