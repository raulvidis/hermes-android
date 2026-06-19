---
summary: "Convention for ephemeral refactor-tracking docs."
read_when:
  - "Starting a significant refactor"
  - "Finishing a refactor and cleaning up its tracking doc"
---

# Refactor Tracking

Ephemeral working scratchpads for in-progress refactors — **not** permanent documentation.

## Convention

- One file per refactor: `YYYY-MM-DD-topic.md` (e.g. `2026-06-19-relay-tls.md`).
- **Created** when starting a significant refactor.
- **Updated** as work progresses (scope, decisions, open questions, checklist).
- **Deleted** when the work lands. If something is worth keeping, migrate it into `architecture.md` or `spec.md` first.

The `.gitkeep` file keeps this directory present when empty.
