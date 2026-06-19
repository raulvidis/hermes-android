# AGENTS.md

Work style: telegraph; noun-phrases ok; drop grammar; min tokens. Hard rules only — tool workflows live in skills/ + .agents/commands/.

## Core
- Repo: hermes-android. Two components: Kotlin bridge app (`hermes-android-bridge/`) + Python toolset (`tools/`, `tests/`, `hermes-android-plugin/`).
- Python prod copy lives in hermes-agent repo; this repo = standalone dev/test. APK does NOT depend on Python.
- Branch `main`. Conventional Commits. pyproject version 0.3.0.
- "Make a note" = terse AGENTS.md edit, not new doc.
- Shipped = git tag (`latest-build` APK + version tag), not main merge.
- Confidentiality: this is a remote-control bridge — security-sensitive. Full device access once paired. Never expose pairing codes, server IPs, tokens, screen content, screenshots, contacts/SMS/location data outside the task. See SECURITY.md.

## Routing
- Screenshots/media: tools return `MEDIA:<path>` (temp files) — relay to user, don't persist.
- Secrets: `~/.hermes/.env` (`ANDROID_BRIDGE_URL`, `ANDROID_BRIDGE_TOKEN`). Never echo/dump env. Never log pairing codes or tokens.
- Test the bridge against a real device or relay; no test accounts baked in.
- Direct USB/LAN dev → phone Ktor server port 8765. Relay (default) → port 8766.

## Project Defaults
- Runtimes: Gradle (Kotlin bridge), Python >=3.11 (toolset). Never swap build tool / package manager without approval.
- Bug fixes → add regression test (Python: `tests/`; Kotlin: bridge unit tests).
- Refactors → delete old paths by default.
- Read docs/index.md + relevant docs before coding; update docs for visible changes.
- New deps → quick health check first (pyproject.toml / Gradle).
- PII: strip phone numbers, recipients, location from tool responses/logs (existing convention — keep it).
- Session start + before coding: run $docs-list (`python3 .agents/skills/docs-list/scripts/docs-list.py`); read docs whose read_when matches.

## PR-CI
- Workflow: fix → test → changelog → review → merge.
- "fix ci" = consent to pull, commit, push, rerun until green.
- Cite fix + file/line in review comments.
- After landing: recap what landed (2-5 sentences).
- Contributor PRs: thank in CHANGELOG, preserve credit + `#PR`.
- Every push to `main` auto-publishes a debug APK to the `latest-build` release.

## Reviews
- Pre-commit / pre-land: run $autoreview until no actionable findings remain.
- $autoreview delegates to installed review skills (/code-review, superpowers) — don't hand-roll review.

## Git
- Safe by default (status/diff/log). Push only when asked.
- Destructive ops forbidden without explicit consent.
- Conventional Commits (feat|fix|refactor|build|ci|chore|docs|style|perf|test). Common scope: `(bridge)`.
- No amend unless asked.
- Stage explicit paths — never `git add .`.
- Unrecognized changes → assume other agent, keep going.

## Runtime Safety
- zsh gotchas (array splitting) — quote vars.
- Never inline shell snippets in GitHub bodies — use heredoc + file.
- Secrets: never run env/set/export or broad secret dumps.
- Destructive on-device actions (purchases, sends, calls, deletions) → confirm with user before executing.

## Workflows
Procedures in `.agents/commands/`. To run one, read the file and follow it.
- handoff · pickup · commit · fix · release
Shared skills in .agents/skills/: $docs-list, $autoreview.
