"""One source of truth for the version.

Review F8: `071753f` — the commit titled `release: v0.5.0` — bumped
pyproject.toml, plugin.yaml and build.gradle.kts and left setup.py at 0.4.1;
`304f078` fixed it one commit later. Nothing in CI checked either, so a release
cut from a tree where they disagree publishes metadata whose version depends on
the build path: `python setup.py --version` reported setup.py's value while a
PEP 517 build read `[project]` from pyproject.toml. A user pinning
`hermes-android==0.5.0` and a `/plugins` banner could therefore disagree.

pyproject.toml is now the only place the Python version is declared — setup.py
keeps just the packaging hints pyproject does not express (packages,
package_data) and inherits the rest. These tests pin the three remaining
declarations to it, including the APK's versionName, which the release checklist
did not used to mention at all.
"""

import pathlib
import re
import tomllib
from importlib import metadata

ROOT = pathlib.Path(__file__).resolve().parent.parent


def _pyproject_version() -> str:
    with (ROOT / "pyproject.toml").open("rb") as fh:
        return tomllib.load(fh)["project"]["version"]


def _plugin_yaml_version() -> str:
    text = (ROOT / "hermes-android-plugin" / "plugin.yaml").read_text(encoding="utf-8")
    match = re.search(r"^version:\s*['\"]?([0-9][0-9A-Za-z.\-]*)", text, re.MULTILINE)
    assert match, "no `version:` line in plugin.yaml"
    return match.group(1)


def _gradle_version_name() -> str:
    text = (ROOT / "hermes-android-bridge" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    assert match, "no versionName in build.gradle.kts"
    return match.group(1)


class TestSingleSourceOfVersion:
    def test_installed_metadata_matches_pyproject(self):
        """The build path must read [project], not a second declaration."""
        assert metadata.version("hermes-android") == _pyproject_version()

    def test_plugin_yaml_matches_pyproject(self):
        assert _plugin_yaml_version() == _pyproject_version()

    def test_apk_version_name_matches_pyproject(self):
        assert _gradle_version_name() == _pyproject_version()

    def test_setup_py_does_not_pin_a_version(self):
        """Regression guard for F8 itself.

        A `version=` in setup.py is a second source of truth that nothing has to
        remember to bump. If this fails, delete the argument rather than syncing
        it — setuptools reads [project] from pyproject.toml.
        """
        text = (ROOT / "setup.py").read_text(encoding="utf-8")
        assert not re.search(r"^\s*version\s*=", text, re.MULTILINE), (
            "setup.py pins a version; pyproject.toml [project].version is the "
            "single source of truth — remove it from setup.py"
        )

    def test_setup_py_does_not_duplicate_pyproject_metadata(self):
        """install_requires/python_requires duplicated dependencies too, so a
        new dependency added in one place silently went missing from the other."""
        text = (ROOT / "setup.py").read_text(encoding="utf-8")
        for field in ("install_requires", "python_requires", "requires_python"):
            assert not re.search(rf"^\s*{field}\s*=", text, re.MULTILINE), (
                f"setup.py duplicates {field}; declare it in pyproject.toml only"
            )
