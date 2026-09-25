import sys

import pytest
import responses


@pytest.fixture
def bridge_url():
    return "http://localhost:8766"


@pytest.fixture(autouse=True)
def isolated_home(monkeypatch, tmp_path_factory):
    """Point HOME and HERMES_HOME at a throwaway directory for every test.

    android_setup persists the pairing token to the Hermes .env; a test that
    reaches it must never touch the developer's real ~/.hermes/.env. Also
    hide hermes_cli so the save path is always the in-repo fallback writer
    (which honours HERMES_HOME), and drop the tool's .env cache so nothing
    parsed from a previous test's home leaks into the next.
    """
    home = tmp_path_factory.mktemp("home")
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("HERMES_HOME", str(home / ".hermes"))
    monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
    monkeypatch.delenv("ANDROID_RELAY_PORT", raising=False)
    monkeypatch.setitem(sys.modules, "hermes_cli", None)
    monkeypatch.setitem(sys.modules, "hermes_cli.config", None)
    try:
        from tools import android_tool
    except ImportError:
        pass
    else:
        monkeypatch.setattr(android_tool, "_ENV_FILE_CACHE", None)
    return home


@pytest.fixture(autouse=True)
def mock_env(monkeypatch, bridge_url):
    monkeypatch.setenv("ANDROID_BRIDGE_URL", bridge_url)
    monkeypatch.setenv("ANDROID_BRIDGE_TIMEOUT", "5")


@pytest.fixture
def mock_bridge(bridge_url):
    """Activate responses mock and return the bridge_url for registering mocks."""
    with responses.RequestsMock() as rsps:
        yield rsps, bridge_url
