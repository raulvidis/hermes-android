import os

import pytest
import responses

from tests.support import (
    loaded_tool_copies,
    plugin_relay_copy,
    plugin_tool_copy,
    reset_tool_module,
    tools_copy,
    tools_relay_copy,
)


@pytest.fixture
def bridge_url():
    return "http://localhost:8766"


@pytest.fixture(autouse=True)
def mock_env(monkeypatch, bridge_url):
    monkeypatch.setenv("ANDROID_BRIDGE_URL", bridge_url)
    monkeypatch.setenv("ANDROID_BRIDGE_TIMEOUT", "5")


@pytest.fixture(autouse=True)
def hermetic_home(monkeypatch, tmp_path):
    """Point HOME and HERMES_HOME at a throwaway directory for every test.

    ``android_setup()`` persists the pairing code through
    ``Path.home() / ".hermes" / ".env"`` — it does not consult HERMES_HOME — so
    without this the test suite rewrites the developer's real credentials file
    on every run (and CI's, and whoever pip-installs the package and runs
    pytest). ``_env_file_vars()`` reads the same path as its fallback for
    ANDROID_BRIDGE_TOKEN, which also meant a test run could pick up a real
    pairing token from the machine it happened to run on.

    Restoring ANDROID_* afterwards matters for the same reason: android_setup()
    assigns to os.environ directly, so a token it wrote would otherwise stay
    visible to every later test in the session.
    """
    home = tmp_path / "home"
    hermes_home = home / ".hermes"
    hermes_home.mkdir(parents=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("HERMES_HOME", str(hermes_home))

    android_vars = {k: v for k, v in os.environ.items() if k.startswith("ANDROID_")}
    yield hermes_home
    for key in [k for k in os.environ if k.startswith("ANDROID_")]:
        if key in android_vars:
            os.environ[key] = android_vars[key]
        else:
            os.environ.pop(key, None)
    _reset_module_caches()


def _reset_module_caches():
    """Drop the parsed-.env memo from every toolset copy already imported."""
    for module in loaded_tool_copies():
        reset_tool_module(module)


@pytest.fixture
def mock_bridge(bridge_url):
    """Activate responses mock and return the bridge_url for registering mocks."""
    with responses.RequestsMock() as rsps:
        yield rsps, bridge_url


@pytest.fixture
def bridge(bridge_url):
    """Like mock_bridge, but a registered route may go uncalled.

    Tests that answer a whole tool table register one mock per verb; requiring
    every registration to be hit would fail on any tool that legitimately uses
    only one of them.
    """
    with responses.RequestsMock(assert_all_requests_are_fired=False) as rsps:
        yield rsps, bridge_url


@pytest.fixture(params=["tools", "plugin"], ids=["tools", "plugin"])
def tool_mod(request):
    """Each android_tool copy in turn: tools/ (dev) and the plugin (shipped)."""
    module = tools_copy() if request.param == "tools" else plugin_tool_copy()
    reset_tool_module(module)
    yield module
    reset_tool_module(module)


@pytest.fixture(params=["tools", "plugin"], ids=["tools", "plugin"])
def relay_mod(request):
    """Each android_relay copy in turn, with its auth state wiped either side.

    The rate limiter keeps module-level dicts, so a failure recorded by one test
    would otherwise still be counting against the next one.
    """
    module = tools_relay_copy() if request.param == "tools" else plugin_relay_copy()
    _reset_relay_auth(module)
    yield module
    _reset_relay_auth(module)


def _reset_relay_auth(module):
    with module._auth_lock:
        module._auth_blocked.clear()
        module._auth_failures.clear()
    module._auth_last_cleanup = 0.0
