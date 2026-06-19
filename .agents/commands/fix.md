---
description: "Run quality gates and fix all failures."
---
Run quality gates, fix every failure until green: Android bridge — `cd hermes-android-bridge && ./gradlew lint test`; Python tools — `pytest` (tests/). Re-run until clean. Update docs/CHANGELOG for visible changes. Confirm `git status -sb` clean and on expected branch.
