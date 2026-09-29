"""The Python CI workflow must run for every change that can break the Python side.

`.github/workflows/python.yml` filters on `paths`, so a trigger list that omits a
path silently runs no check at all for a PR touching only that path (review F13:
the workflow file itself was missing from `pull_request.paths`, so a PR editing
only the matrix or the pytest invocation was untested until it landed on main).

Parsed line-by-line rather than with a YAML library — PyYAML is not a dev
dependency, and the same technique already pins docs/architecture.md to the
relay allowlist in tests/test_android_relay.py.
"""

import pathlib

WORKFLOW = (
    pathlib.Path(__file__).resolve().parent.parent
    / ".github"
    / "workflows"
    / "python.yml"
)


def _trigger_paths(text: str, trigger: str) -> list:
    """Return the `paths:` list under `on.<trigger>:`."""
    lines = text.splitlines()
    in_trigger = False
    in_paths = False
    indent = None
    found = []
    for line in lines:
        stripped = line.strip()
        if not in_trigger:
            if stripped == f"{trigger}:":
                in_trigger = True
            continue
        if stripped.startswith("#") or not stripped:
            continue
        if stripped == "paths:":
            in_paths = True
            indent = len(line) - len(line.lstrip())
            continue
        if in_paths:
            if stripped.startswith("- "):
                found.append(stripped[2:].strip().strip("'\""))
                continue
            # Dedent (or a sibling key) ends the list.
            if len(line) - len(line.lstrip()) <= indent:
                break
    return found


class TestPythonWorkflowTriggers:
    def test_pull_request_covers_every_push_path(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        push_paths = set(_trigger_paths(text, "push"))
        pr_paths = set(_trigger_paths(text, "pull_request"))

        assert push_paths, "no push paths parsed — is the workflow format still line-based?"
        assert pr_paths, "no pull_request paths parsed — is the workflow format still line-based?"
        assert push_paths - pr_paths == set(), (
            "paths that trigger on push but not on pull_request are untested in review: "
            f"{sorted(push_paths - pr_paths)}"
        )

    def test_the_workflow_file_itself_is_a_pull_request_trigger(self):
        """Regression for F13 specifically — the file that defines the check
        must be able to trigger the check."""
        text = WORKFLOW.read_text(encoding="utf-8")

        assert ".github/workflows/python.yml" in _trigger_paths(text, "pull_request")
