"""The plugin must report why the android skill failed to register.

Review F14: `register()` wrapped `ctx.register_skill(...)` in
`try/except (AttributeError, TypeError): pass`, justified as "hermes-agent older
than the register_skill API". But AttributeError is also what a missing
`skill.md` produces, and TypeError is also what a renamed `path=` kwarg produces
— and the handler was silent. The plugin folder is copied by hand
(`install.sh` / the `cp -r` steps in README.md), so a copy without `skill.md` is
a realistic outcome: tools register, `/plugins` shows
`✓ hermes-android v0.5.0 (42 tools)`, and the accessibility-tree-first guidance
the feature exists to deliver never loads. The agent silently regresses to
screenshot + vision, which is the behaviour the original commit claimed to fix.

The plugin directory is not importable under its own name (it contains a dash),
so the package is loaded by file location with submodule_search_locations, which
makes `from .android_tool import ...` resolve.
"""

import importlib.util
import logging
import pathlib
import sys

import pytest

PLUGIN_DIR = pathlib.Path(__file__).resolve().parents[1] / "hermes-android-plugin"
_MODULE_NAME = "hermes_android_plugin_under_test"


@pytest.fixture(scope="session")
def plugin():
    if _MODULE_NAME in sys.modules:
        return sys.modules[_MODULE_NAME]
    spec = importlib.util.spec_from_file_location(
        _MODULE_NAME,
        PLUGIN_DIR / "__init__.py",
        submodule_search_locations=[str(PLUGIN_DIR)],
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[_MODULE_NAME] = module
    spec.loader.exec_module(module)
    return module


class CtxWithSkillApi:
    """hermes-agent new enough to have register_skill."""

    def __init__(self, raises=None):
        self.tools = []
        self.skills = []
        self.raises = raises

    def register_tool(self, **kwargs):
        self.tools.append(kwargs["name"])

    def register_skill(self, **kwargs):
        if self.raises:
            raise self.raises
        self.skills.append(kwargs)


class CtxWithoutSkillApi:
    """hermes-agent older than the register_skill API — no such attribute."""

    def __init__(self):
        self.tools = []

    def register_tool(self, **kwargs):
        self.tools.append(kwargs["name"])


class TestRegisterSkillIsHonest:
    def test_registers_tools_and_the_skill_on_a_current_agent(self, plugin):
        ctx = CtxWithSkillApi()

        plugin.register(ctx)

        assert len(ctx.tools) == 42
        assert len(ctx.skills) == 1
        assert ctx.skills[0]["name"] == "android"
        assert pathlib.Path(ctx.skills[0]["path"]).name == "skill.md"

    def test_old_agent_without_the_api_is_skipped_with_a_reason(self, plugin, caplog):
        ctx = CtxWithoutSkillApi()

        with caplog.at_level(logging.INFO):
            plugin.register(ctx)

        assert len(ctx.tools) == 42, "tools must still register"
        assert "register_skill" in caplog.text, (
            "silently skipping is what made this undiagnosable; say why"
        )

    def test_missing_skill_file_is_reported_not_swallowed(self, plugin, monkeypatch, tmp_path, caplog):
        """The AttributeError this actually raised used to be caught and dropped."""
        ctx = CtxWithSkillApi()
        monkeypatch.setattr(plugin, "_SKILL_PATH", tmp_path / "absent-skill.md")

        with caplog.at_level(logging.WARNING):
            plugin.register(ctx)

        assert ctx.skills == [], "must not register a path that does not exist"
        assert len(ctx.tools) == 42, "a missing skill must not cost the tools"
        assert "absent-skill.md" in caplog.text
        assert any(r.levelno >= logging.WARNING for r in caplog.records), (
            "a missing skill.md is a broken install, not an info-level aside"
        )

    def test_an_unexpected_signature_error_is_logged(self, plugin, caplog):
        """TypeError also covers a renamed kwarg. Tools must survive, but the
        error has to be visible rather than passed over."""
        ctx = CtxWithSkillApi(raises=TypeError("register_skill() got an unexpected keyword argument 'path'"))

        with caplog.at_level(logging.ERROR):
            plugin.register(ctx)

        assert len(ctx.tools) == 42
        assert "unexpected keyword argument" in caplog.text
        assert any(r.levelno >= logging.ERROR for r in caplog.records)
