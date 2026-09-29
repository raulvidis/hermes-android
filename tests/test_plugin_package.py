"""The shipped plugin package: registration, self-containment and parity with
the tools/ copy.

hermes-android-plugin/ is what lands in ~/.hermes/plugins/hermes-android, where
there is no `tools` package on the path and nothing imports the module for you —
hermes-agent calls register(ctx). That makes three things worth testing that the
tools/ copy cannot show:

* register() wires all 42 tools to *its own* handlers, with a check_fn that hides
  them when no phone is connected (and never hides android_setup, which is the
  tool that connects one);
* the package is self-contained — a leftover absolute `tools.` import works in
  this repo and fails on the user's machine;
* the two android_tool copies have not drifted. They are maintained by hand and
  the plugin copy is the one that ships, so a fix applied only to tools/ is a
  fix that never reaches anyone.

The directory name has dashes, so it cannot be imported normally; tests/support.py
loads it under a synthetic package name.
"""

import ast
import re
import runpy
import sys
import tomllib
import types
from pathlib import Path

import pytest

from tests.support import (
    PLUGIN_DIR,
    REPO_ROOT,
    load_plugin_package,
    plugin_relay_copy,
    plugin_tool_copy,
    tools_relay_copy,
)


@pytest.fixture(scope="module")
def plugin():
    return load_plugin_package()


@pytest.fixture(scope="module")
def plugin_tool():
    return plugin_tool_copy()


class RecordingContext:
    """Stands in for hermes-agent's plugin context."""

    def __init__(self, skill_error=None):
        self.tools = []
        self.skills = []
        self._skill_error = skill_error

    def register_tool(self, **kwargs):
        self.tools.append(kwargs)

    def register_skill(self, **kwargs):
        if self._skill_error is not None:
            raise self._skill_error
        self.skills.append(kwargs)


class NoSkillApiContext:
    """A hermes-agent older than register_skill: the attribute does not exist."""

    def __init__(self):
        self.tools = []

    def register_tool(self, **kwargs):
        self.tools.append(kwargs)


class TestRegistration:
    def test_every_tool_is_registered(self, plugin, plugin_tool):
        ctx = RecordingContext()

        plugin.register(ctx)

        assert len(ctx.tools) == 42
        assert {entry["name"] for entry in ctx.tools} == set(plugin_tool._SCHEMAS)

    def test_tools_land_in_the_android_toolset(self, plugin):
        ctx = RecordingContext()

        plugin.register(ctx)

        assert {entry["toolset"] for entry in ctx.tools} == {"android"}

    def test_each_tool_gets_its_own_schema_and_handler(self, plugin, plugin_tool):
        ctx = RecordingContext()

        plugin.register(ctx)

        by_name = {entry["name"]: entry for entry in ctx.tools}
        for name, schema in plugin_tool._SCHEMAS.items():
            assert by_name[name]["schema"] is schema
            assert by_name[name]["handler"] is plugin_tool._HANDLERS[name]

    def test_the_handlers_are_the_plugin_copys_own(self, plugin, plugin_tool):
        """Registering tools/ handlers from the plugin package would work in this
        repo and break on a machine where only the plugin was installed."""
        from tools import android_tool as tools_copy

        ctx = RecordingContext()
        plugin.register(ctx)

        assert plugin_tool is not tools_copy
        for entry in ctx.tools:
            assert entry["handler"] is not tools_copy._HANDLERS[entry["name"]], (
                f"{entry['name']} was registered from the tools/ copy"
            )

    def test_the_schema_declares_the_name_it_is_registered_under(self, plugin):
        ctx = RecordingContext()

        plugin.register(ctx)

        for entry in ctx.tools:
            assert entry["schema"]["name"] == entry["name"]

    def test_android_setup_is_available_without_a_bridge(self, plugin):
        """It is the tool that starts the relay, so gating it on a connected phone
        would make pairing impossible."""
        ctx = RecordingContext()

        plugin.register(ctx)

        setup = next(e for e in ctx.tools if e["name"] == "android_setup")
        assert setup["check_fn"]() is True

    def test_every_other_tool_is_gated_on_a_connected_phone(self, plugin, plugin_tool):
        ctx = RecordingContext()

        plugin.register(ctx)

        for entry in ctx.tools:
            if entry["name"] == "android_setup":
                continue
            assert entry["check_fn"] is plugin_tool._check_requirements, (
                f"{entry['name']} has its own check_fn"
            )

    def test_the_setup_gate_is_independent_of_the_others(self, plugin, plugin_tool):
        """A lambda, not the shared _check_requirements: if the two were the same
        object the exemption above would be a coincidence."""
        ctx = RecordingContext()

        plugin.register(ctx)

        setup = next(e for e in ctx.tools if e["name"] == "android_setup")
        assert setup["check_fn"] is not plugin_tool._check_requirements


class TestSkillRegistration:
    def test_the_usage_skill_is_registered(self, plugin):
        ctx = RecordingContext()

        plugin.register(ctx)

        assert len(ctx.skills) == 1
        assert ctx.skills[0]["name"] == "android"

    def test_the_skill_points_at_a_file_that_ships_with_the_plugin(self, plugin):
        ctx = RecordingContext()

        plugin.register(ctx)

        path = Path(ctx.skills[0]["path"])
        assert path.name == "skill.md"
        assert path.parent == PLUGIN_DIR.resolve()
        assert path.is_file(), f"{path} is registered but does not exist"
        assert len(path.read_text(encoding="utf-8")) > 1000

    def test_the_skill_has_a_description(self, plugin):
        ctx = RecordingContext()

        plugin.register(ctx)

        description = ctx.skills[0]["description"]
        assert len(description) > 40
        assert "accessibility tree" in description, (
            "the point of the skill is to steer agents away from screenshot+vision"
        )

    def test_a_hermes_without_the_skill_api_still_gets_the_tools(self, plugin):
        ctx = NoSkillApiContext()

        plugin.register(ctx)

        assert len(ctx.tools) == 42

    def test_a_skill_api_that_rejects_the_arguments_still_gets_the_tools(self, plugin):
        ctx = RecordingContext(skill_error=TypeError("unexpected keyword argument 'path'"))

        plugin.register(ctx)

        assert len(ctx.tools) == 42
        assert ctx.skills == []

    def test_a_broken_skill_registration_does_not_take_the_tools_with_it(self, plugin):
        ctx = RecordingContext(skill_error=AttributeError("no such method"))

        plugin.register(ctx)

        assert len(ctx.tools) == 42

    def test_a_failing_register_tool_is_not_swallowed(self, plugin):
        """Only the skill call is guarded. If the tool API itself is missing the
        plugin is useless and must fail loudly at load time."""

        class BrokenContext(RecordingContext):
            def register_tool(self, **kwargs):
                raise AttributeError("register_tool() got an unexpected keyword 'check_fn'")

        with pytest.raises(AttributeError):
            plugin.register(BrokenContext())

    def test_every_tool_name_mentioned_in_the_skill_is_a_real_tool(self, plugin_tool):
        """The skill is the agent's manual; a renamed tool leaves it steering
        agents at something that no longer exists."""
        text = (PLUGIN_DIR / "skill.md").read_text(encoding="utf-8")
        mentioned = set(re.findall(r"android_[a-z_]+", text))

        unknown = sorted(mentioned - set(plugin_tool._SCHEMAS))

        assert mentioned, "the skill mentions no tools at all"
        assert not unknown, f"skill.md documents tools that are not registered: {unknown}"


@pytest.fixture(scope="module")
def sources():
    """Every .py file in the plugin package, by filename."""
    return {
        path.name: path.read_text(encoding="utf-8") for path in sorted(PLUGIN_DIR.glob("*.py"))
    }


@pytest.fixture(scope="module")
def manifest():
    """plugin.yaml as flat key/value pairs (it has one nested-free level)."""
    text = (PLUGIN_DIR / "plugin.yaml").read_text(encoding="utf-8")
    parsed = {}
    for line in text.splitlines():
        if ":" in line and not line.startswith((" ", "\t", "#")):
            key, _, value = line.partition(":")
            parsed[key.strip()] = value.strip().strip('"')
    return parsed


class TestSelfContained:
    def test_no_absolute_tools_imports(self, sources):
        """`from tools.android_relay import ...` resolves in this repo and raises
        ModuleNotFoundError in ~/.hermes/plugins/hermes-android."""
        offenders = {
            name: re.findall(r"^\s*(?:from|import)\s+tools[\s.].*$", source, re.MULTILINE)
            for name, source in sources.items()
        }
        offenders = {name: hits for name, hits in offenders.items() if hits}

        assert not offenders, f"absolute tools imports in the plugin: {offenders}"

    def test_no_registry_trailer(self, sources):
        """The plugin registers through ctx; the tools/ copy's registry import is
        the one piece that must not be copied across."""
        assert all("tools.registry" not in source for source in sources.values())

    def test_android_setup_imports_the_relay_relatively(self, sources):
        assert "from .android_relay import" in sources["android_tool.py"]

    def test_the_package_imports_the_tool_module_itself(self, sources):
        assert "from .android_tool import" in sources["__init__.py"]

    def test_the_loading_module_does_not_import_aiohttp_or_requests(self, sources):
        """__init__.py runs at plugin discovery; keeping it light means a broken
        dependency shows up when a tool is called, not when hermes starts."""
        assert "import aiohttp" not in sources["__init__.py"]
        assert "import requests" not in sources["__init__.py"]


class TestPluginManifest:
    def test_the_manifest_names_the_plugin(self, manifest):
        assert manifest["name"] == "hermes-android"

    def test_the_manifest_version_matches_the_package(self, manifest):
        """Three files declare the version (pyproject, setup.py, plugin.yaml) and
        nothing checks them against each other."""
        pyproject = tomllib.loads((REPO_ROOT / "pyproject.toml").read_text(encoding="utf-8"))

        assert manifest["version"] == pyproject["project"]["version"]

    def test_the_manifest_declares_a_description(self, manifest):
        assert len(manifest["description"]) > 20

    def test_no_environment_is_required_to_load(self, manifest):
        """android_setup creates the configuration, so requiring it up front would
        make the plugin unloadable on a fresh install."""
        assert manifest["requires_env"] == "[]"


class TestRegistryTrailer:
    """The tools/ copy registers itself into hermes-agent's registry at import
    time; the plugin copy hands the same 42 tools to ctx.register(). Both answer
    "how does a host discover the tools", so they are tested side by side."""

    @staticmethod
    def _load_tools_copy(monkeypatch, registry):
        module = types.ModuleType("tools.registry")
        module.registry = registry
        monkeypatch.setitem(sys.modules, "tools.registry", module)
        return runpy.run_path(str(REPO_ROOT / "tools" / "android_tool.py"))

    def test_every_tool_is_registered_with_its_schema_and_handler(self, monkeypatch):
        registered = []
        namespace = self._load_tools_copy(
            monkeypatch, types.SimpleNamespace(register=lambda **kw: registered.append(kw))
        )

        assert len(registered) == 42
        assert {entry["name"] for entry in registered} == set(namespace["_SCHEMAS"])
        for entry in registered:
            assert entry["toolset"] == "android"
            assert entry["schema"] is namespace["_SCHEMAS"][entry["name"]]
            assert entry["handler"] is namespace["_HANDLERS"][entry["name"]]
            assert entry["requires_env"] == [], (
                "ANDROID_BRIDGE_URL has a default, so no tool may require env"
            )

    def test_android_setup_is_exempt_from_the_connectivity_check(self, monkeypatch):
        registered = []
        namespace = self._load_tools_copy(
            monkeypatch, types.SimpleNamespace(register=lambda **kw: registered.append(kw))
        )

        by_name = {entry["name"]: entry for entry in registered}
        assert by_name["android_setup"]["check_fn"]() is True
        for name, entry in by_name.items():
            if name == "android_setup":
                continue
            assert entry["check_fn"] is namespace["_check_requirements"]

    def test_a_missing_registry_module_is_not_an_error(self, monkeypatch):
        """This repo has no tools.registry — importing the module standalone (and
        every test in this suite) depends on that trailer failing quietly."""
        monkeypatch.setitem(sys.modules, "tools.registry", None)

        namespace = runpy.run_path(str(REPO_ROOT / "tools" / "android_tool.py"))

        assert len(namespace["_SCHEMAS"]) == 42
        assert len(namespace["_HANDLERS"]) == 42


class TestCopyParity:
    """The two android_tool copies are hand-maintained. android_relay.py is
    already pinned byte-for-byte by test_android_relay.py; the tool copy is
    allowed to differ, but only where it has to."""

    @staticmethod
    def _top_level(path):
        """Split a module into (definitions, assignments, everything else).

        Imports and the module docstring are left out: they are the two places
        the copies are meant to differ.
        """
        tree = ast.parse(path.read_text(encoding="utf-8"))
        definitions = {}
        assignments = {}
        other = []
        for node in tree.body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                definitions[node.name] = ast.dump(node)
            elif isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name):
                assignments[node.targets[0].id] = ast.dump(node)
            elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
                assignments[node.target.id] = ast.dump(node)
            elif isinstance(node, (ast.Import, ast.ImportFrom)):
                continue
            elif isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant):
                continue  # docstring
            else:
                other.append(node)
        return definitions, assignments, other

    def test_the_same_functions_exist_in_both(self):
        tools_defs, _, _ = self._top_level(REPO_ROOT / "tools" / "android_tool.py")
        plugin_defs, _, _ = self._top_level(PLUGIN_DIR / "android_tool.py")

        assert set(tools_defs) == set(plugin_defs)

    def test_every_function_is_identical_except_android_setup(self):
        tools_defs, _, _ = self._top_level(REPO_ROOT / "tools" / "android_tool.py")
        plugin_defs, _, _ = self._top_level(PLUGIN_DIR / "android_tool.py")

        drifted = sorted(
            name for name in tools_defs if tools_defs[name] != plugin_defs[name]
        )

        assert drifted == ["android_setup"], (
            "the copies have drifted; apply the same change to both "
            "(android_setup is the only function allowed to differ, because it "
            f"imports the relay relatively): {drifted}"
        )

    def test_the_module_level_data_is_identical(self):
        """_SCHEMAS and _HANDLERS are the tool surface; drift here means the two
        copies expose different tools."""
        _, tools_assigns, _ = self._top_level(REPO_ROOT / "tools" / "android_tool.py")
        _, plugin_assigns, _ = self._top_level(PLUGIN_DIR / "android_tool.py")

        assert set(tools_assigns) == set(plugin_assigns)
        drifted = sorted(
            name for name in tools_assigns if tools_assigns[name] != plugin_assigns[name]
        )
        assert not drifted, f"module-level data drifted: {drifted}"

    def test_only_the_tools_copy_has_the_registry_trailer(self):
        _, _, tools_other = self._top_level(REPO_ROOT / "tools" / "android_tool.py")
        _, _, plugin_other = self._top_level(PLUGIN_DIR / "android_tool.py")

        assert len(tools_other) == 1 and isinstance(tools_other[0], ast.Try)
        assert plugin_other == []

    def test_android_setup_differs_only_in_how_it_finds_the_relay(self):
        """The plugin imports .android_relay, tools/ imports tools.android_relay,
        and the two error messages name their own package. Nothing else."""
        tools_source = (REPO_ROOT / "tools" / "android_tool.py").read_text(encoding="utf-8")
        plugin_source = (PLUGIN_DIR / "android_tool.py").read_text(encoding="utf-8")

        def setup_statements(source):
            tree = ast.parse(source)
            function = next(
                node
                for node in tree.body
                if isinstance(node, ast.FunctionDef) and node.name == "android_setup"
            )
            return {ast.unparse(node) for node in ast.walk(function)}

        only_in_tools = sorted(setup_statements(tools_source) - setup_statements(plugin_source))
        only_in_plugin = sorted(setup_statements(plugin_source) - setup_statements(tools_source))

        assert only_in_tools and only_in_plugin, (
            "android_setup is now identical in both copies — the exemption in "
            "test_every_function_is_identical_except_android_setup can go"
        )
        assert len(only_in_tools) == len(only_in_plugin)
        for line in only_in_tools + only_in_plugin:
            assert "android_relay" in line or "hermes-android" in line, (
                f"android_setup differs somewhere other than the relay import "
                f"and its error message: {line!r}"
            )

    def test_both_copies_call_the_same_bridge_paths(self):
        """Every path a tool requests must be in that copy's relay allowlist, or
        the endpoint exists on the phone and is silently unreachable through the
        relay (the bug 92b4204 fixed for /battery)."""
        for tool_path, relay_name in (
            (REPO_ROOT / "tools" / "android_tool.py", "tools.android_relay"),
            (PLUGIN_DIR / "android_tool.py", "hermes_android_plugin_copy.android_relay"),
        ):
            source = tool_path.read_text(encoding="utf-8")
            requested = {
                match.group(2)
                for match in re.finditer(r'_(post|get)\(\s*f?"([^"?]+)', source)
            }
            relay = (
                tools_relay_copy()
                if relay_name.startswith("tools")
                else plugin_relay_copy()
            )

            missing = sorted(requested - set(relay._ROUTES))
            assert not missing, (
                f"{tool_path} calls {missing}, which {relay_name} does not allowlist"
            )
