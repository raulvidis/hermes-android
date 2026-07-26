# About / О репозитории

## English

**hermes-android-media** is a **public fork line** of [raulvidis/hermes-android](https://github.com/raulvidis/hermes-android).

It keeps the original design: a companion **Hermes Bridge** Android app + Python tools for [Hermes Agent](https://github.com/NousResearch/hermes-agent) so an agent can control a phone over a WebSocket relay (AccessibilityService).

**This fork adds (among other things):**

- Background **microphone** and **camera** recording (Foreground Service; Android shows OS indicators)
- **Remote audio playback** for server-side TTS (optional ElevenLabs if configured)
- Relay hardening: update pairing on a live relay, clear auth rate-limit bans, `X-Forwarded-For` for client IP, env-tunable rate limits, prefer `ANDROID_PUBLIC_URL` in setup instructions

**Not included in git:** keystores, API keys, personal servers, pairing codes.

**Credit:** core architecture and tooling belong to the upstream project. See [FORK_FEATURES.md](FORK_FEATURES.md), [DEPLOYMENT_WSS.md](DEPLOYMENT_WSS.md), [BUILDING_APK.md](BUILDING_APK.md).

**Safety:** use only on devices you own or administer with informed consent. Remote control is powerful.

---

## Русский

**hermes-android-media** — **публичная линия форка** [raulvidis/hermes-android](https://github.com/raulvidis/hermes-android).

Идея та же: приложение **Hermes Bridge** на Android + Python-инструменты для [Hermes Agent](https://github.com/NousResearch/hermes-agent), чтобы агент управлял телефоном через WebSocket relay (AccessibilityService).

**В этом форке, в частности:**

- Фоновая запись **микрофона** и **камеры** (Foreground Service; система показывает индикаторы)
- **Удалённое воспроизведение аудио** для TTS на стороне сервера (опционально ElevenLabs)
- Hardening relay: обновление pairing на уже запущенном relay, сброс 429-банов, IP из `X-Forwarded-For`, лимиты через env, в инструкции — `ANDROID_PUBLIC_URL`

**В git нет:** keystore, API-ключей, личных серверов, pairing-кодов.

**Кредит:** ядро архитектуры — у upstream. См. [FORK_FEATURES.md](FORK_FEATURES.md), [DEPLOYMENT_WSS.md](DEPLOYMENT_WSS.md), [BUILDING_APK.md](BUILDING_APK.md).

**Безопасность:** только свои устройства / с согласия владельца. Remote control — серьёзный доступ.
