from __future__ import annotations

import tomllib
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


def test_plugin_manifest_version_matches_distribution_version():
    project = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    manifest = yaml.safe_load(
        (ROOT / "hermes-android-plugin" / "plugin.yaml").read_text(encoding="utf-8")
    )

    assert manifest["version"] == project["project"]["version"]
