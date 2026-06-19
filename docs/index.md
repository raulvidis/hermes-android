---
summary: "Index of all docs — what exists and when to read each."
read_when:
  - "Starting a session and orienting in the repo"
  - "Looking for which doc covers a topic"
---

# Documentation Index

Each doc carries YAML frontmatter (`summary` + `read_when`). Read the one whose `read_when` matches your task.

| Doc | Summary | Read when |
|-----|---------|-----------|
| [architecture.md](architecture.md) | Kotlin bridge ↔ Python toolset ↔ WebSocket relay; 36 `android_*` tools. | Changing relay/bridge/tool wiring; understanding data flow |
| [quickstart.md](quickstart.md) | Get a phone controlled in ~5 minutes. | First-time setup; demoing the system |
| [install.md](install.md) | Full install of plugin + bridge APK. | Installing from scratch; debugging install |
| [configuration.md](configuration.md) | All env vars and config options. | Changing ports, URLs, tokens, timeouts |
| [RELEASING.md](RELEASING.md) | Release checklist: pyproject version + APK `latest-build` tag flow. | Cutting a release |
| [manual-tests.md](manual-tests.md) | On-device manual test procedures. | Before a release; after bridge changes |
| [troubleshooting.md](troubleshooting.md) | Known issues + workarounds. | Connection/permission/tool failures |
| [spec.md](spec.md) | Goals, non-goals, compatibility commitments. | Scoping features; deciding what belongs |
| [refactor/README.md](refactor/README.md) | Ephemeral refactor-tracking convention. | Starting/finishing a large refactor |
