# AGENTS.md

READ ./.agents/AGENTS.base.md BEFORE ANYTHING (skip if missing).

Repo-specific hard rules only. Shared rules (Reviews, PR/CI, Git, Runtime Safety,
generic Project Defaults, Workflows) live in `AGENTS.base.md` — not duplicated here.

## Core
- Repo: hermes-android. Two components: Kotlin bridge app (`hermes-android-bridge/`) + Python toolset (`tools/`, `tests/`, `hermes-android-plugin/`).
- Python prod copy lives in hermes-agent repo; this repo = standalone dev/test. APK does NOT depend on Python.
- Branch `main`. pyproject version 0.3.0.
- Shipped = git tag (`latest-build` APK + version tag), not main merge.
- Confidentiality: this is a remote-control bridge — security-sensitive. Full device access once paired. Never expose pairing codes, server IPs, tokens, screen content, screenshots, contacts/SMS/location data outside the task. See SECURITY.md.

## Routing
- Screenshots/media: tools return `MEDIA:<path>` (temp files) — relay to user, don't persist.
- Secrets: `~/.hermes/.env` (`ANDROID_BRIDGE_URL`, `ANDROID_BRIDGE_TOKEN`). Never echo/dump env. Never log pairing codes or tokens.
- Test the bridge against a real device or relay; no test accounts baked in.
- Direct USB/LAN dev → phone Ktor server port 8765. Relay (default) → port 8766.

## Project Defaults (repo-specific)
- Runtimes: Gradle (Kotlin bridge), Python >=3.11 (toolset).
- Bug-fix regression tests → Python: `tests/`; Kotlin: bridge unit tests.
- New-dep health check sources: pyproject.toml / Gradle.
- PII: strip phone numbers, recipients, location from tool responses/logs (existing convention — keep it).

## PR / CI (repo-specific)
- Every push to `main` auto-publishes a debug APK to the `latest-build` release.

## Git (repo-specific)
- Common commit scope: `(bridge)`.

## Runtime Safety (repo-specific)
- Destructive on-device actions (purchases, sends, calls, deletions) → confirm with user before executing.
