---
summary: "Release checklist — Python pyproject version bump + APK latest-build tag flow."
read_when:
  - "Cutting a new release"
  - "Verifying what 'shipped' means for this repo"
---

# Releasing

"Shipped" = a release **git tag**, not a merge to `main`. The debug APK is published continuously to the `latest-build` release on every push to `main`; a versioned release is the explicit, tagged cut.

## Checklist

1. **CHANGELOG.md** — move items from `## [Unreleased]` into a new `## [X.Y.Z]` section (dated). Reset `[Unreleased]` to empty. One bullet per entry; preserve `#PR` + contributor credit.
2. **Bump version** in `pyproject.toml` (`[project] version = "X.Y.Z"`). Keep `setup.py` consistent if it pins a version.
3. **Bump version** in plugin metadata (`hermes-android-plugin/plugin.yaml`) and any `vX.Y.Z` strings in README if present.
4. **Commit:** `chore(release): vX.Y.Z` (explicit paths only).
5. **Tag:** `git tag vX.Y.Z`.
6. **GitHub Release:** create release `vX.Y.Z` with the CHANGELOG section as the body. The `latest-build` APK already carries the current build; attach `hermes-android-<version>.apk` to the versioned release if a pinned artifact is wanted.
7. **Verify:** tag exists, GitHub release exists, plugin version shows correctly via `/plugins`.
8. **Reopen `[Unreleased]`** at the top of CHANGELOG.md.

## Guardrails

- Do NOT push or publish without explicit confirmation.
- Update compare/tag links at the bottom of CHANGELOG.md.
- Run quality gates (`/fix`) before tagging.
