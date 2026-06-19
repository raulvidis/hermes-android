---
summary: "Design constraints — goals, non-goals, and compatibility commitments."
read_when:
  - "Scoping a new feature"
  - "Deciding whether something belongs in this repo"
  - "Reasoning about cross-version stability"
---

# Spec

## Goals

- Give an AI agent reliable, remote control of an Android device via AccessibilityService.
- NAT-friendly connectivity: the phone connects **out** to a relay — no port forwarding, VPN, or USB required for normal use.
- Simple pairing: a 6-character code, nothing else for the user to configure.
- Broad device coverage: phones and Android Automotive (AAOS) head units, degrading gracefully where hardware is absent.
- Two clean integration paths: hermes-agent plugin (preferred) and legacy `tools/` import, sharing one implementation.

## Non-goals

- Not a granular permission system — once paired, the agent has full device access. Scope is handled by trust, not by per-tool ACLs.
- Not encrypted transport (prototype): connections are `ws://`. TLS is delegated to a reverse proxy (nginx/caddy), not built in.
- Not a persistent command audit log (only stdout logging at INFO/DEBUG).
- Not iOS (future idea, not committed).
- The Android app does not depend on the Python toolset — Python is server-side only.

## Compatibility commitments

- Tool names (`android_*`) and their core argument shapes are the stable public surface for hermes-agent; additive changes preferred, removals are breaking.
- Command envelope (`request_id`/`method`/`path`/`params`/`body` ↔ `request_id`/`result`/`status`) is the bridge↔relay contract.
- Ports: phone Ktor server `8765`, relay `8766` (overridable via env).
- Pairing alphabet excludes confusable chars (0/O/1/I); codes are case-sensitive.

## Security posture

This is a remote-control bridge — treat like remote desktop access. See `../SECURITY.md`. PII (phone numbers, recipients, location, clipboard, screen content) must be stripped from responses and kept out of logs and public issues.

## Roadmap

The forward-looking roadmap (multi-device, scheduled automations, voice, on-device models, iOS) lives in `README.md`. This spec governs what is committed and stable today.
