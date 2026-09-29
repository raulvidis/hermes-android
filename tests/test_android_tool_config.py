"""Configuration paths: the ~/.hermes/.env fallback, .env rewriting, public-IP
detection and android_setup.

This is the plumbing that decides *where* tool calls go and *with which token*,
so a wrong answer here is not a degraded response — it is an unauthenticated
relay, a silently reset port, or a pairing code written to a world-readable
file. Both copies are exercised because the plugin copy is the one that runs
inside hermes-agent, where the .env fallback is the only thing that works (the
gateway does not export every variable into os.environ).

No test here touches the network: _get_public_ip is answered with ``responses``,
and android_setup's relay start is stubbed.
"""

import os
import socket
import sys
import time
import types
from pathlib import Path

import pytest
import requests
import responses

from tests.support import as_json, relay_module_for

FAKE_IP = "203.0.113.42"


class EnvFile:
    """Handle on the throwaway ~/.hermes/.env for one test.

    Callable so a test can write it in one expression (``env_file("TOKEN=1")``)
    and read it back without repeating the encoding.
    """

    def __init__(self, path: Path):
        self.path = path

    def __call__(self, text: str) -> "EnvFile":
        self.path.write_text(text, encoding="utf-8")
        return self

    def write(self, text: str) -> "EnvFile":
        return self(text)

    def read(self) -> str:
        return self.path.read_text(encoding="utf-8")

    def exists(self) -> bool:
        return self.path.exists()


@pytest.fixture
def env_file(hermetic_home):
    """The .env that HERMES_HOME points at (already isolated by conftest)."""
    path = hermetic_home / ".env"
    path.write_text("", encoding="utf-8")
    return EnvFile(path)


@pytest.fixture
def no_os_environ(monkeypatch):
    """Remove every ANDROID_* variable so only the .env fallback can answer."""
    for key in [k for k in os.environ if k.startswith("ANDROID_")]:
        monkeypatch.delenv(key, raising=False)


class TestHermeticHome:
    def test_the_suite_never_points_at_the_real_home(self, hermetic_home):
        """android_setup() persists the pairing code through
        ``Path.home() / ".hermes" / ".env"``, and _env_file_vars() reads the same
        path as its token fallback. conftest redirects HOME and HERMES_HOME into
        pytest's tmp_path; if that ever stops happening, every test run rewrites
        the developer's real credentials file — and CI's.

        pwd is used rather than $HOME because $HOME is exactly what the fixture
        overrides.
        """
        pwd = pytest.importorskip("pwd")
        real_home = Path(pwd.getpwuid(os.getuid()).pw_dir).resolve()

        assert not hermetic_home.resolve().is_relative_to(real_home), (
            f"tests are writing into the real home directory: {hermetic_home} "
            f"is inside {real_home}"
        )

    def test_setup_writes_under_the_redirected_home(self, tool_mod, hermetic_home, monkeypatch):
        """The positive half: the file android_setup produces is the one inside
        the throwaway home."""
        monkeypatch.setattr(tool_mod, "_get_public_ip", lambda: FAKE_IP)
        monkeypatch.setattr(time, "sleep", lambda seconds: None)
        relay = relay_module_for(tool_mod)
        monkeypatch.setattr(relay, "start_relay", lambda pairing_code, port: None)
        monkeypatch.setattr(relay, "is_phone_connected", lambda: False)

        tool_mod.android_setup("PAIRCODE")

        written = hermetic_home / ".env"
        assert written.is_file()
        assert "ANDROID_BRIDGE_TOKEN=PAIRCODE" in written.read_text(encoding="utf-8")

    def test_the_redirect_survives_home_being_unset(
        self, tool_mod, hermetic_home, monkeypatch
    ):
        """The redirect must not depend on the environment cooperating.

        ``Path.home()`` and ``os.path.expanduser("~")`` fall back to
        ``pwd.getpwuid(os.getuid()).pw_dir`` — the *real* home — whenever HOME is
        absent from os.environ. Redirecting only $HOME therefore leaves
        android_setup's ``Path.home() / ".hermes" / ".env"`` one ``delenv`` away
        from rewriting the developer's real credentials file, and
        _env_file_vars()'s token fallback reading it. Patching Path.home and
        expanduser themselves is what closes that.
        """
        pwd = pytest.importorskip("pwd")
        real_home = Path(pwd.getpwuid(os.getuid()).pw_dir).resolve()
        throwaway_home = hermetic_home.parent.resolve()

        monkeypatch.delenv("HOME", raising=False)
        monkeypatch.delenv("HERMES_HOME", raising=False)

        for label, resolved in (
            ("Path.home()", Path.home().resolve()),
            ("os.path.expanduser('~')", Path(os.path.expanduser("~")).resolve()),
            (
                "android_setup's .env",
                (Path.home() / ".hermes" / ".env").resolve(),
            ),
            (
                "_env_file_vars()'s .env",
                Path(
                    os.environ.get("HERMES_HOME", os.path.expanduser("~/.hermes")),
                    ".env",
                ).resolve(),
            ),
        ):
            assert not resolved.is_relative_to(real_home), (
                f"{label} escaped into the real home: {resolved}"
            )
            assert resolved.is_relative_to(throwaway_home), (
                f"{label} is not inside the throwaway home: {resolved}"
            )

        (hermetic_home / ".env").write_text(
            "ANDROID_BRIDGE_TOKEN=THROWAWAYTOKEN\n", encoding="utf-8"
        )
        assert tool_mod._env_file_vars()["ANDROID_BRIDGE_TOKEN"] == "THROWAWAYTOKEN"


class TestEnvFileParsing:
    def test_reads_keys_and_values(self, tool_mod, env_file, no_os_environ):
        env_file("ANDROID_BRIDGE_TOKEN=FILETOKEN\nANDROID_BRIDGE_URL=http://file:8766\n")

        assert tool_mod._env_file_vars() == {
            "ANDROID_BRIDGE_TOKEN": "FILETOKEN",
            "ANDROID_BRIDGE_URL": "http://file:8766",
        }

    def test_ignores_comments_blank_lines_and_lines_without_equals(
        self, tool_mod, env_file, no_os_environ
    ):
        env_file(
            "# a comment\n"
            "\n"
            "   \n"
            "THISLINEHASNOEQUALSSIGN\n"
            "ANDROID_BRIDGE_TOKEN=KEEPME\n"
        )

        assert tool_mod._env_file_vars() == {"ANDROID_BRIDGE_TOKEN": "KEEPME"}

    @pytest.mark.parametrize(
        "line,expected",
        [
            ('TOKEN="quoted"', "quoted"),
            ("TOKEN='single'", "single"),
            ("TOKEN=  spaced  ", "spaced"),
            ("  TOKEN = padded ", "padded"),
            ("TOKEN=a=b", "a=b"),
            ("TOKEN=", ""),
        ],
        ids=["double_quoted", "single_quoted", "spaced_value", "padded", "equals_in_value", "empty"],
    )
    def test_value_normalisation(self, tool_mod, env_file, no_os_environ, line, expected):
        env_file(line + "\n")
        assert tool_mod._env_file_vars()["TOKEN"] == expected

    def test_an_export_prefix_is_not_understood(self, tool_mod, env_file, no_os_environ):
        """Pinned as-is: the parser splits on the first '=' and strips nothing
        else, so `export FOO=bar` lands under the key "export FOO" and is
        unreachable through _env(). Shell-style .env files are therefore only
        partly supported."""
        env_file("export ANDROID_BRIDGE_TOKEN=SHELLSTYLE\n")

        assert tool_mod._bridge_token() is None
        assert tool_mod._env_file_vars() == {"export ANDROID_BRIDGE_TOKEN": "SHELLSTYLE"}

    def test_the_file_is_read_once_and_memoised(self, tool_mod, env_file, no_os_environ):
        """A long-lived gateway process will not pick up a re-paired phone unless
        the cache is dropped; that is the documented trade-off, so it is pinned."""
        env_file("ANDROID_BRIDGE_TOKEN=FIRST\n")
        first = tool_mod._env_file_vars()

        env_file("ANDROID_BRIDGE_TOKEN=SECOND\n")

        assert tool_mod._env_file_vars() is first
        assert tool_mod._bridge_token() == "FIRST"

        tool_mod._ENV_FILE_CACHE = None
        assert tool_mod._bridge_token() == "SECOND"

    def test_a_missing_file_is_not_an_error(self, tool_mod, hermetic_home, no_os_environ):
        (hermetic_home / ".env").unlink(missing_ok=True)
        assert tool_mod._env_file_vars() == {}

    def test_an_unreadable_file_is_not_an_error(self, tool_mod, hermetic_home, no_os_environ):
        """A directory where the .env should be makes open() raise; the fallback
        must swallow that rather than take the whole toolset down."""
        (hermetic_home / ".env").unlink(missing_ok=True)
        (hermetic_home / ".env").mkdir()

        assert tool_mod._env_file_vars() == {}
        assert tool_mod._bridge_token() is None

    def test_hermes_home_redirects_the_lookup(self, tool_mod, monkeypatch, tmp_path, no_os_environ):
        other = tmp_path / "elsewhere"
        other.mkdir()
        (other / ".env").write_text("ANDROID_BRIDGE_TOKEN=OTHERHOME\n", encoding="utf-8")
        monkeypatch.setenv("HERMES_HOME", str(other))
        tool_mod._ENV_FILE_CACHE = None

        assert tool_mod._bridge_token() == "OTHERHOME"

    def test_the_default_home_is_used_when_hermes_home_is_unset(
        self, tool_mod, monkeypatch, tmp_path, no_os_environ
    ):
        """Without HERMES_HOME the fallback is $HOME/.hermes — which conftest
        points at a throwaway directory, so this asserts the composition rather
        than touching a real credentials file."""
        monkeypatch.delenv("HERMES_HOME", raising=False)
        home = tmp_path / "fake-home"
        (home / ".hermes").mkdir(parents=True)
        (home / ".hermes" / ".env").write_text("ANDROID_BRIDGE_TOKEN=DEFAULTHOME\n", encoding="utf-8")
        monkeypatch.setenv("HOME", str(home))
        tool_mod._ENV_FILE_CACHE = None

        assert tool_mod._bridge_token() == "DEFAULTHOME"


class TestEnvLookup:
    def test_os_environ_wins_over_the_file(self, tool_mod, env_file, monkeypatch):
        env_file("ANDROID_BRIDGE_TOKEN=FROMFILE\n")
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "FROMENV")

        assert tool_mod._env("ANDROID_BRIDGE_TOKEN") == "FROMENV"

    def test_the_file_answers_when_os_environ_is_empty(self, tool_mod, env_file, no_os_environ):
        env_file("ANDROID_BRIDGE_TOKEN=FROMFILE\n")

        assert tool_mod._env("ANDROID_BRIDGE_TOKEN") == "FROMFILE"

    def test_an_unset_key_is_none(self, tool_mod, env_file, no_os_environ):
        env_file("SOMETHING_ELSE=1\n")
        assert tool_mod._env("ANDROID_BRIDGE_TOKEN") is None

    def test_an_empty_os_environ_value_falls_through_to_the_file(
        self, tool_mod, env_file, monkeypatch
    ):
        """`if val:` rather than `is not None`, so ANDROID_BRIDGE_TOKEN= in the
        process environment does not mask the file. Pinned because it decides
        what an explicitly-blanked variable means."""
        env_file("ANDROID_BRIDGE_TOKEN=FROMFILE\n")
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "")

        assert tool_mod._bridge_token() == "FROMFILE"

    def test_the_bridge_url_default_is_the_local_relay(self, tool_mod, env_file, no_os_environ):
        env_file("")
        assert tool_mod._bridge_url() == "http://localhost:8766"

    def test_the_bridge_url_prefers_the_file_over_the_default(self, tool_mod, env_file, no_os_environ):
        env_file("ANDROID_BRIDGE_URL=http://198.51.100.5:8765\n")
        assert tool_mod._bridge_url() == "http://198.51.100.5:8765"

    def test_the_token_becomes_a_bearer_header(self, tool_mod, env_file, no_os_environ):
        env_file("ANDROID_BRIDGE_TOKEN=FILETOKEN\n")
        assert tool_mod._auth_headers() == {"Authorization": "Bearer FILETOKEN"}

    def test_no_token_means_no_headers(self, tool_mod, env_file, no_os_environ):
        env_file("")
        assert tool_mod._auth_headers() == {}

    def test_the_timeout_defaults_to_30_seconds(self, tool_mod, monkeypatch):
        monkeypatch.delenv("ANDROID_BRIDGE_TIMEOUT", raising=False)
        assert tool_mod._timeout() == 30.0

    def test_the_timeout_is_configurable(self, tool_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_BRIDGE_TIMEOUT", "2.5")
        assert tool_mod._timeout() == 2.5

    def test_the_relay_port_defaults_to_8766(self, tool_mod, monkeypatch):
        monkeypatch.delenv("ANDROID_RELAY_PORT", raising=False)
        assert tool_mod._relay_port() == 8766

    def test_the_relay_port_is_configurable(self, tool_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_PORT", "9100")
        assert tool_mod._relay_port() == 9100

    def test_a_garbage_timeout_raises_out_of_the_helper(self, tool_mod, monkeypatch):
        """Callers wrap this in try/except, so the ValueError becomes an error
        result rather than a crash — pinned so that stays true."""
        monkeypatch.setenv("ANDROID_BRIDGE_TIMEOUT", "soon")
        with pytest.raises(ValueError):
            tool_mod._timeout()

    @pytest.mark.parametrize("key", ["ANDROID_RELAY_PORT", "ANDROID_BRIDGE_TIMEOUT"])
    def test_env_can_see_a_port_and_timeout_set_only_in_the_file(
        self, tool_mod, env_file, no_os_environ, key
    ):
        """_env() finds both keys in the .env — the two helpers below just do not
        ask it."""
        env_file(f"{key}=9100\n")
        assert tool_mod._env(key) == "9100"

    @pytest.mark.xfail(
        strict=True,
        reason=(
            "BUG: _relay_port() (and _timeout()) read os.getenv directly instead "
            "of going through _env(), so a value set only in ~/.hermes/.env is "
            "ignored. That is the same situation the .env fallback was added for "
            "in 4234d92 — the gateway does not export every variable — and "
            "_bridge_url()/_bridge_token() do honour it. android_setup persists "
            "ANDROID_RELAY_PORT to that very file, so a custom port configured "
            "only there is silently reset to the 8766 default on the next setup."
        ),
    )
    def test_relay_port_honours_the_env_file(self, tool_mod, env_file, no_os_environ):
        env_file("ANDROID_RELAY_PORT=9100\n")

        assert tool_mod._relay_port() == 9100


class TestUpdateEnvFile:
    def test_creates_the_file_when_absent(self, tool_mod, tmp_path):
        target = tmp_path / "nested" / ".env"
        target.parent.mkdir()

        tool_mod._update_env_file(target, "ANDROID_BRIDGE_TOKEN", "CODE1")

        assert target.read_text(encoding="utf-8") == "ANDROID_BRIDGE_TOKEN=CODE1\n"

    def test_the_file_is_owner_only(self, tool_mod, tmp_path):
        """The .env holds the pairing token, which is full device control
        (SECURITY.md) — 0644 would hand it to every local user."""
        target = tmp_path / ".env"

        tool_mod._update_env_file(target, "ANDROID_BRIDGE_TOKEN", "CODE1")

        assert oct(target.stat().st_mode & 0o777) == "0o600"

    def test_appends_a_new_key_and_keeps_existing_lines(self, tool_mod, tmp_path):
        target = tmp_path / ".env"
        target.write_text("# comment\nOTHER=1\n", encoding="utf-8")

        tool_mod._update_env_file(target, "ANDROID_BRIDGE_TOKEN", "CODE1")

        assert target.read_text(encoding="utf-8") == (
            "# comment\nOTHER=1\nANDROID_BRIDGE_TOKEN=CODE1\n"
        )

    def test_adds_the_missing_newline_before_appending(self, tool_mod, tmp_path):
        target = tmp_path / ".env"
        target.write_text("OTHER=1", encoding="utf-8")

        tool_mod._update_env_file(target, "TOKEN", "CODE1")

        assert target.read_text(encoding="utf-8") == "OTHER=1\nTOKEN=CODE1\n"

    def test_replaces_an_existing_key_in_place(self, tool_mod, tmp_path):
        target = tmp_path / ".env"
        target.write_text("A=1\nTOKEN=OLD\nB=2\n", encoding="utf-8")

        tool_mod._update_env_file(target, "TOKEN", "NEW")

        assert target.read_text(encoding="utf-8") == "A=1\nTOKEN=NEW\nB=2\n"

    def test_only_the_first_occurrence_is_replaced(self, tool_mod, tmp_path):
        """Pinned: a duplicated key leaves the second line alone, so the file can
        still hold a stale value. Last-one-wins would be friendlier, but the
        parser reads top-down and stops at the first match."""
        target = tmp_path / ".env"
        target.write_text("TOKEN=FIRST\nTOKEN=SECOND\n", encoding="utf-8")

        tool_mod._update_env_file(target, "TOKEN", "NEW")

        assert target.read_text(encoding="utf-8") == "TOKEN=NEW\nTOKEN=SECOND\n"

    def test_a_key_that_is_a_prefix_of_another_is_not_touched(self, tool_mod, tmp_path):
        target = tmp_path / ".env"
        target.write_text("TOKEN_EXTRA=keepme\n", encoding="utf-8")

        tool_mod._update_env_file(target, "TOKEN", "NEW")

        assert target.read_text(encoding="utf-8") == "TOKEN_EXTRA=keepme\nTOKEN=NEW\n"

    def test_an_empty_file_gets_the_key(self, tool_mod, tmp_path):
        target = tmp_path / ".env"
        target.write_text("", encoding="utf-8")

        tool_mod._update_env_file(target, "TOKEN", "NEW")

        assert target.read_text(encoding="utf-8") == "TOKEN=NEW\n"

    def test_a_value_with_spaces_and_equals_survives(self, tool_mod, tmp_path):
        target = tmp_path / ".env"

        tool_mod._update_env_file(target, "URL", "http://1.2.3.4:8766?a=b")

        assert target.read_text(encoding="utf-8") == "URL=http://1.2.3.4:8766?a=b\n"

    def test_a_chmod_failure_is_swallowed(self, tool_mod, tmp_path, monkeypatch):
        """Some filesystems refuse chmod; losing the token would be worse than
        storing it with the default mode."""
        target = tmp_path / ".env"

        def refuse(*args, **kwargs):
            raise OSError("chmod not supported")

        monkeypatch.setattr(Path, "chmod", refuse)

        tool_mod._update_env_file(target, "TOKEN", "NEW")

        assert target.read_text(encoding="utf-8") == "TOKEN=NEW\n"

    def test_undecodable_bytes_do_not_destroy_the_file(self, tool_mod, tmp_path):
        """errors='replace' on read: a stray binary byte costs one line, not the
        whole credentials file."""
        target = tmp_path / ".env"
        target.write_bytes(b"KEEP=1\nBROKEN=\xff\xfe\n")

        tool_mod._update_env_file(target, "TOKEN", "NEW")

        written = target.read_text(encoding="utf-8", errors="replace")
        assert "KEEP=1" in written
        assert "TOKEN=NEW" in written


class TestGetPublicIp:
    SERVICES = ("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com")

    def test_the_first_service_answers(self, tool_mod):
        with responses.RequestsMock(assert_all_requests_are_fired=False) as rsps:
            rsps.add(responses.GET, self.SERVICES[0], body=f"{FAKE_IP}\n")

            assert tool_mod._get_public_ip() == FAKE_IP

    def test_a_non_200_falls_through_to_the_next_service(self, tool_mod):
        with responses.RequestsMock(assert_all_requests_are_fired=False) as rsps:
            rsps.add(responses.GET, self.SERVICES[0], body="rate limited", status=429)
            rsps.add(responses.GET, self.SERVICES[1], body=FAKE_IP)

            assert tool_mod._get_public_ip() == FAKE_IP

    def test_a_connection_error_falls_through_to_the_next_service(self, tool_mod):
        with responses.RequestsMock(assert_all_requests_are_fired=False) as rsps:
            rsps.add(
                responses.GET,
                self.SERVICES[0],
                body=ConnectionError("dns failure"),
            )
            rsps.add(responses.GET, self.SERVICES[1], body=FAKE_IP)

            assert tool_mod._get_public_ip() == FAKE_IP

    def test_all_services_failing_falls_back_to_the_hostname(self, tool_mod, monkeypatch):
        with responses.RequestsMock(assert_all_requests_are_fired=False) as rsps:
            for service in self.SERVICES:
                rsps.add(responses.GET, service, body=ConnectionError("offline"))
            monkeypatch.setattr(socket, "gethostbyname", lambda host: "198.51.100.7")
            monkeypatch.setattr(socket, "gethostname", lambda: "hermes-server")

            assert tool_mod._get_public_ip() == "198.51.100.7"

    def test_an_unresolvable_hostname_yields_the_placeholder(self, tool_mod, monkeypatch):
        with responses.RequestsMock(assert_all_requests_are_fired=False) as rsps:
            for service in self.SERVICES:
                rsps.add(responses.GET, service, body=ConnectionError("offline"))

            def unresolvable(host):
                raise socket.gaierror("Name or service not known")

            monkeypatch.setattr(socket, "gethostbyname", unresolvable)

            assert tool_mod._get_public_ip() == "<your-server-ip>"

    def test_the_hostname_fallback_can_return_a_loopback_address(self, tool_mod, monkeypatch):
        """Pinned, and worth knowing: on a host whose name resolves to loopback
        (the common /etc/hosts default) the fallback reports 127.0.1.1, which is
        what android_setup then tells the user to type into the phone app. The
        placeholder branch above is only reached when resolution *fails*, so an
        unreachable-but-resolvable name is presented as a real address."""
        with responses.RequestsMock(assert_all_requests_are_fired=False) as rsps:
            for service in self.SERVICES:
                rsps.add(responses.GET, service, body=ConnectionError("offline"))
            monkeypatch.setattr(socket, "gethostbyname", lambda host: "127.0.1.1")

            assert tool_mod._get_public_ip() == "127.0.1.1"

    def test_uses_a_short_timeout_per_service(self, tool_mod, monkeypatch):
        """Three services at 3s each is already 9s of an agent's turn; the cap is
        part of the contract."""
        seen = []
        real_get = requests.get

        def spy(target, **kwargs):
            seen.append(kwargs.get("timeout"))
            return real_get(target, **kwargs)

        monkeypatch.setattr(requests, "get", spy)

        with responses.RequestsMock(assert_all_requests_are_fired=False) as rsps:
            rsps.add(responses.GET, self.SERVICES[0], body=FAKE_IP)
            tool_mod._get_public_ip()

        assert seen == [3]


class TestAndroidSetup:
    """android_setup is the one tool allowed to run with no bridge: it is what
    creates the connection. It writes credentials, mutates the process
    environment and starts a background server, so each side effect is checked."""

    @pytest.fixture(autouse=True)
    def fast_and_offline(self, tool_mod, monkeypatch):
        monkeypatch.setattr(tool_mod, "_get_public_ip", lambda: FAKE_IP)
        self.slept = []
        monkeypatch.setattr(time, "sleep", lambda seconds: self.slept.append(seconds))
        self.relay = relay_module_for(tool_mod)
        self.started = []
        self.connected = False
        monkeypatch.setattr(
            self.relay,
            "start_relay",
            lambda pairing_code, port: self.started.append((pairing_code, port)),
        )
        monkeypatch.setattr(self.relay, "is_phone_connected", lambda: self.connected)

    @pytest.fixture
    def hermes_cli(self, monkeypatch):
        """Stand in for hermes-agent's config writer, which the tool prefers."""
        saved = []
        package = types.ModuleType("hermes_cli")
        config = types.ModuleType("hermes_cli.config")
        config.save_env_value = lambda key, value: saved.append((key, value))
        package.config = config
        monkeypatch.setitem(sys.modules, "hermes_cli", package)
        monkeypatch.setitem(sys.modules, "hermes_cli.config", config)
        return saved

    def test_starts_the_relay_with_the_pairing_code_and_port(self, tool_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_PORT", "9100")

        as_json(tool_mod.android_setup("PAIRCODE"))

        assert self.started == [("PAIRCODE", 9100)]

    def test_waits_before_checking_whether_the_phone_arrived(self, tool_mod):
        """The phone connects asynchronously, so setup gives it a second; the
        wait is asserted because removing it makes the 'connected' answer a coin
        flip on a slow network."""
        as_json(tool_mod.android_setup("PAIRCODE"))

        assert self.slept == [1]

    def test_reports_a_phone_that_is_already_connected(self, tool_mod):
        self.connected = True

        result = as_json(tool_mod.android_setup("PAIRCODE"))

        assert result["status"] == "ok"
        assert result["phone_connected"] is True
        assert result["message"] == "Phone is connected and ready!"
        assert result["server_address"] == f"{FAKE_IP}:8766"
        assert "user_instructions" not in result

    def test_tells_the_user_what_to_type_when_no_phone_yet(self, tool_mod):
        result = as_json(tool_mod.android_setup("PAIRCODE"))

        assert result["status"] == "ok"
        assert result["phone_connected"] is False
        assert result["server_address"] == f"{FAKE_IP}:8766"
        assert FAKE_IP in result["user_instructions"]
        assert "PAIRCODE" in result["user_instructions"]

    def test_exports_the_url_and_token_into_the_process(self, tool_mod):
        as_json(tool_mod.android_setup("PAIRCODE"))

        assert os.environ["ANDROID_BRIDGE_TOKEN"] == "PAIRCODE"
        assert os.environ["ANDROID_BRIDGE_URL"] == "http://localhost:8766"

    def test_the_exported_url_is_immediately_usable(self, tool_mod, bridge):
        """The point of the export: the next tool call in the same process must
        reach the relay that was just started, not a stale URL."""
        rsps, _ = bridge
        as_json(tool_mod.android_setup("PAIRCODE"))
        rsps.add(responses.GET, "http://localhost:8766/ping", json={"phone_connected": True})

        assert as_json(tool_mod.android_ping())["status"] == "ok"

    def test_uses_hermes_cli_config_when_available(self, tool_mod, hermes_cli, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_PORT", "9100")

        as_json(tool_mod.android_setup("PAIRCODE"))

        assert hermes_cli == [
            ("ANDROID_BRIDGE_URL", "http://localhost:9100"),
            ("ANDROID_BRIDGE_TOKEN", "PAIRCODE"),
            ("ANDROID_RELAY_PORT", "9100"),
        ]

    def test_falls_back_to_writing_the_env_file(self, tool_mod, hermetic_home):
        assert "hermes_cli.config" not in sys.modules, (
            "a real hermes_cli on the path would make this test pass without "
            "exercising the fallback"
        )

        as_json(tool_mod.android_setup("PAIRCODE"))

        written = (hermetic_home / ".env").read_text(encoding="utf-8")
        assert "ANDROID_BRIDGE_URL=http://localhost:8766" in written
        assert "ANDROID_BRIDGE_TOKEN=PAIRCODE" in written
        assert "ANDROID_RELAY_PORT=8766" in written

    def test_the_written_env_file_is_owner_only(self, tool_mod, hermetic_home):
        as_json(tool_mod.android_setup("PAIRCODE"))

        mode = (hermetic_home / ".env").stat().st_mode & 0o777
        assert oct(mode) == "0o600"

    def test_repairing_replaces_the_previous_token(self, tool_mod, hermetic_home):
        as_json(tool_mod.android_setup("OLDCODE"))
        as_json(tool_mod.android_setup("NEWCODE"))

        written = (hermetic_home / ".env").read_text(encoding="utf-8")
        assert "OLDCODE" not in written
        assert written.count("ANDROID_BRIDGE_TOKEN=") == 1
        assert "ANDROID_BRIDGE_TOKEN=NEWCODE" in written

    def test_a_missing_relay_module_is_reported(self, tool_mod, monkeypatch):
        """The plugin ships android_relay next to android_tool; if the import
        still fails the agent needs to hear 'not installed', not a traceback."""
        monkeypatch.setitem(sys.modules, f"{tool_mod.__package__}.android_relay", None)

        result = as_json(tool_mod.android_setup("PAIRCODE"))

        assert result["status"] == "error"
        assert "android_relay module not found" in result["message"]

    def test_a_relay_that_refuses_to_start_is_reported(self, tool_mod, monkeypatch):
        def refuse(pairing_code, port):
            raise RuntimeError("Relay failed to start")

        monkeypatch.setattr(self.relay, "start_relay", refuse)

        result = as_json(tool_mod.android_setup("PAIRCODE"))

        assert result == {"status": "error", "message": "Relay failed to start"}

    def test_an_unwritable_env_file_is_reported(self, tool_mod, hermetic_home, monkeypatch):
        (hermetic_home / ".env").unlink(missing_ok=True)
        (hermetic_home / ".env").mkdir()  # a directory: open() for write fails

        result = as_json(tool_mod.android_setup("PAIRCODE"))

        assert result["status"] == "error"
        assert not self.started, "the relay must not start when the config could not be saved"

    def test_a_bad_relay_port_is_reported(self, tool_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_PORT", "not-a-port")

        result = as_json(tool_mod.android_setup("PAIRCODE"))

        assert result["status"] == "error"
        assert not self.started

    def test_setup_never_calls_the_bridge(self, tool_mod, bridge):
        """check_fn exempts android_setup for exactly this reason: it is what
        creates the connection, so requiring one would deadlock pairing."""
        rsps, _ = bridge

        tool_mod.android_setup("PAIRCODE")

        assert not rsps.calls

    def test_the_pairing_code_is_not_logged(self, tool_mod, caplog):
        import logging

        with caplog.at_level(logging.DEBUG):
            tool_mod.android_setup("PAIRCODE-DO-NOT-LOG")

        assert "PAIRCODE-DO-NOT-LOG" not in caplog.text

    @pytest.mark.xfail(
        strict=True,
        reason=(
            "BUG: same root cause as _relay_port() ignoring ~/.hermes/.env, seen "
            "from the user's side — android_setup starts the relay on the 8766 "
            "default and then rewrites ANDROID_RELAY_PORT in the .env, "
            "discarding the port the user configured there."
        ),
    )
    def test_setup_keeps_a_port_configured_only_in_the_env_file(
        self, tool_mod, env_file, no_os_environ, hermetic_home
    ):
        env_file("ANDROID_RELAY_PORT=9100\n")

        tool_mod.android_setup("PAIRCODE")

        assert self.started == [("PAIRCODE", 9100)]
        assert "ANDROID_RELAY_PORT=9100" in env_file.read()
