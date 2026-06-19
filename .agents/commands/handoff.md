---
description: "Package current state for the next agent."
argument-hint: "[optional focus area]"
---
Read AGENTS.md. Produce a concise handoff for the next agent, in order:
1) Scope/status — doing, done, pending, blockers  2) Working tree — summarize !`git status -sb` + unpushed commits  3) Branch/PR — branch, PR#/URL, CI  4) Running processes — sessions/servers + attach commands  5) Tests/checks — ran, results, still-to-run  6) Next steps — ordered bullets  7) Risks/gotchas. Focus: $ARGUMENTS
