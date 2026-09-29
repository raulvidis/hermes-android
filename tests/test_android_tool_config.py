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

        tool_mod.android_setup("PA2RCDE")

        written = hermetic_home / ".env"
        assert written.is_file()
        assert "ANDROID_BRIDGE_TOKEN=PA2RCDE" in written.read_text(encoding="utf-8")

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

    def test_relay_port_honours_the_env_file(self, tool_mod, env_file, no_os_environ):
        """Was `xfail(strict)` — "BUG: _relay_port() reads os.getenv directly".

        F5 fixed it: every documented `ANDROID_*` variable now goes through
        `_env()`, so a port set only in `~/.hermes/.env` is honoured. This is the
        regression test for that fix.
        """
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

        as_json(tool_mod.android_setup("PA2RCDE"))

        assert self.started == [("PA2RCDE", 9100)]

    def test_waits_before_checking_whether_the_phone_arrived(self, tool_mod):
        """The phone connects asynchronously, so setup gives it a second; the
        wait is asserted because removing it makes the 'connected' answer a coin
        flip on a slow network."""
        as_json(tool_mod.android_setup("PA2RCDE"))

        assert self.slept == [1]

    def test_reports_a_phone_that_is_already_connected(self, tool_mod):
        self.connected = True

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["status"] == "ok"
        assert result["phone_connected"] is True
        assert result["message"] == "Phone is connected and ready!"
        assert result["server_address"] == f"ws://{FAKE_IP}:8766"
        assert "user_instructions" not in result

    def test_tells_the_user_what_to_type_when_no_phone_yet(self, tool_mod):
        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["status"] == "ok"
        assert result["phone_connected"] is False
        assert result["server_address"] == f"ws://{FAKE_IP}:8766"
        assert FAKE_IP in result["user_instructions"]
        assert "PA2RCDE" in result["user_instructions"]

    # ── TRANS-1: the printed address must carry a scheme the app can dial ─────
    #
    # RelayClient honours an explicit scheme and otherwise dials plaintext ws://,
    # while the relay only terminates TLS when ANDROID_RELAY_CERT/KEY are set. A
    # bare `ip:port` therefore either fails the handshake (TLS relay) or connects
    # in cleartext and draws the app's red plaintext warning. Setup knows which
    # transport it just started, so it says so.

    def test_the_printed_address_is_explicitly_plaintext_without_a_certificate(
        self, tool_mod
    ):
        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["server_address"] == f"ws://{FAKE_IP}:8766"
        assert f"Server: ws://{FAKE_IP}:8766" in result["user_instructions"]
        assert f"Server: {FAKE_IP}:8766" not in result["user_instructions"], (
            "a bare host:port is what made the documented setup undialable"
        )

    def test_the_printed_address_is_wss_when_the_relay_terminates_tls(
        self, tool_mod, monkeypatch
    ):
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/fullchain.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")
        self.connected = True

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["server_address"] == f"wss://{FAKE_IP}:8766"

    def test_the_printed_address_follows_the_configured_port(self, tool_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_PORT", "9100")

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["server_address"] == f"ws://{FAKE_IP}:9100"

    @pytest.mark.parametrize(
        "tls,expected", [(False, "ws"), (True, "wss")], ids=["plaintext", "tls"]
    )
    def test_the_address_helper_picks_the_scheme(self, tool_mod, tls, expected):
        assert (
            tool_mod._relay_server_address(FAKE_IP, 8766, tls)
            == f"{expected}://{FAKE_IP}:8766"
        )

    def test_exports_the_url_and_token_into_the_process(self, tool_mod):
        as_json(tool_mod.android_setup("PA2RCDE"))

        assert os.environ["ANDROID_BRIDGE_TOKEN"] == "PA2RCDE"
        assert os.environ["ANDROID_BRIDGE_URL"] == "http://localhost:8766"

    def test_the_exported_url_is_immediately_usable(self, tool_mod, bridge):
        """The point of the export: the next tool call in the same process must
        reach the relay that was just started, not a stale URL."""
        rsps, _ = bridge
        as_json(tool_mod.android_setup("PA2RCDE"))
        rsps.add(responses.GET, "http://localhost:8766/ping", json={"phone_connected": True})

        assert as_json(tool_mod.android_ping())["status"] == "ok"

    def test_uses_hermes_cli_config_when_available(self, tool_mod, hermes_cli, monkeypatch):
        """No port configured anywhere, so setup records the one it chose."""
        monkeypatch.delenv("ANDROID_RELAY_PORT", raising=False)

        as_json(tool_mod.android_setup("PA2RCDE"))

        assert hermes_cli == [
            ("ANDROID_BRIDGE_URL", "http://localhost:8766"),
            ("ANDROID_BRIDGE_TOKEN", "PA2RCDE"),
            ("ANDROID_RELAY_PORT", "8766"),
        ]

    def test_a_port_the_user_already_set_is_not_written_back(
        self, tool_mod, hermes_cli, monkeypatch
    ):
        """F5: an explicit ANDROID_RELAY_PORT is left alone.

        Writing it back is a no-op at best; before the fix setup wrote the
        *default* it had just read over the user's value, because `_relay_port()`
        never consulted the .env.
        """
        monkeypatch.setenv("ANDROID_RELAY_PORT", "9100")

        as_json(tool_mod.android_setup("PA2RCDE"))

        assert hermes_cli == [
            ("ANDROID_BRIDGE_URL", "http://localhost:9100"),
            ("ANDROID_BRIDGE_TOKEN", "PA2RCDE"),
        ]

    def test_falls_back_to_writing_the_env_file(self, tool_mod, hermetic_home):
        assert "hermes_cli.config" not in sys.modules, (
            "a real hermes_cli on the path would make this test pass without "
            "exercising the fallback"
        )

        as_json(tool_mod.android_setup("PA2RCDE"))

        written = (hermetic_home / ".env").read_text(encoding="utf-8")
        assert "ANDROID_BRIDGE_URL=http://localhost:8766" in written
        assert "ANDROID_BRIDGE_TOKEN=PA2RCDE" in written
        assert "ANDROID_RELAY_PORT=8766" in written

    def test_the_written_env_file_is_owner_only(self, tool_mod, hermetic_home):
        as_json(tool_mod.android_setup("PA2RCDE"))

        mode = (hermetic_home / ".env").stat().st_mode & 0o777
        assert oct(mode) == "0o600"

    def test_repairing_replaces_the_previous_token(self, tool_mod, hermetic_home):
        as_json(tool_mod.android_setup("PREVCDE"))
        as_json(tool_mod.android_setup("NEWCDE"))

        written = (hermetic_home / ".env").read_text(encoding="utf-8")
        assert "PREVCDE" not in written
        assert written.count("ANDROID_BRIDGE_TOKEN=") == 1
        assert "ANDROID_BRIDGE_TOKEN=NEWCDE" in written

    def test_a_missing_relay_module_is_reported(self, tool_mod, monkeypatch):
        """The plugin ships android_relay next to android_tool; if the import
        still fails the agent needs to hear 'not installed', not a traceback."""
        monkeypatch.setitem(sys.modules, f"{tool_mod.__package__}.android_relay", None)

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["status"] == "error"
        assert "android_relay module not found" in result["message"]

    def test_a_relay_that_refuses_to_start_is_reported(self, tool_mod, monkeypatch):
        def refuse(pairing_code, port):
            raise RuntimeError("Relay failed to start")

        monkeypatch.setattr(self.relay, "start_relay", refuse)

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result == {"status": "error", "message": "Relay failed to start"}

    def test_an_unwritable_env_file_is_reported(self, tool_mod, hermetic_home, monkeypatch):
        (hermetic_home / ".env").unlink(missing_ok=True)
        (hermetic_home / ".env").mkdir()  # a directory: open() for write fails

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["status"] == "error"
        assert not self.started, "the relay must not start when the config could not be saved"

    def test_a_bad_relay_port_is_reported(self, tool_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_PORT", "not-a-port")

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["status"] == "error"
        assert not self.started

    def test_setup_never_calls_the_bridge(self, tool_mod, bridge):
        """check_fn exempts android_setup for exactly this reason: it is what
        creates the connection, so requiring one would deadlock pairing."""
        rsps, _ = bridge

        tool_mod.android_setup("PA2RCDE")

        assert not rsps.calls

    def test_the_pairing_code_is_not_logged(self, tool_mod, caplog):
        import logging

        with caplog.at_level(logging.DEBUG):
            tool_mod.android_setup("SECRET2345")

        assert "SECRET2345" not in caplog.text

    def test_setup_keeps_a_port_configured_only_in_the_env_file(
        self, tool_mod, env_file, no_os_environ, hermetic_home
    ):
        """Was `xfail(strict)` — the user-side view of the same F5 bug.

        android_setup used to start the relay on the 8766 default and then
        rewrite ANDROID_RELAY_PORT in the .env, discarding the port the user had
        configured there. It now reads the port through `_env()` and leaves an
        already-configured value alone.
        """
        env_file("ANDROID_RELAY_PORT=9100\n")

        tool_mod.android_setup("PA2RCDE")

        assert self.started == [("PA2RCDE", 9100)]
        assert "ANDROID_RELAY_PORT=9100" in env_file.read()

    # ── F19: the saved bridge URL must use the transport the relay serves ──────
    #
    # With ANDROID_RELAY_CERT/KEY set, aiohttp serves the *whole* site over TLS —
    # the /ws route the phone uses and the HTTP routes the tools use are one
    # TCPSite. So http://localhost:8766 stops speaking HTTP and every tool call
    # fails, while android_setup had just written that URL to ~/.hermes/.env and
    # exported it into the process.

    def test_the_saved_bridge_url_is_https_when_the_relay_terminates_tls(
        self, tool_mod, monkeypatch
    ):
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/fullchain.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")

        as_json(tool_mod.android_setup("PA2RCDE"))

        assert os.environ["ANDROID_BRIDGE_URL"] == "https://localhost:8766"

    def test_the_env_file_gets_the_same_https_url(self, tool_mod, hermetic_home, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/fullchain.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")

        as_json(tool_mod.android_setup("PA2RCDE"))

        written = (hermetic_home / ".env").read_text(encoding="utf-8")
        assert "ANDROID_BRIDGE_URL=https://localhost:8766" in written

    def test_the_saved_bridge_url_stays_http_without_a_certificate(self, tool_mod):
        as_json(tool_mod.android_setup("PA2RCDE"))

        assert os.environ["ANDROID_BRIDGE_URL"] == "http://localhost:8766"

    @pytest.mark.parametrize("present", ["cert", "key"], ids=["cert_only", "key_only"])
    def test_half_a_tls_configuration_stays_http(self, tool_mod, monkeypatch, present):
        """Matches the relay: one path is not TLS, so the URL must not claim
        https:// against a listener that is serving plaintext."""
        monkeypatch.setenv(
            "ANDROID_RELAY_CERT" if present == "cert" else "ANDROID_RELAY_KEY", "/tmp/x.pem"
        )

        as_json(tool_mod.android_setup("PA2RCDE"))

        assert os.environ["ANDROID_BRIDGE_URL"] == "http://localhost:8766"

    def test_the_tls_predicate_agrees_with_the_relay(self, tool_mod, monkeypatch):
        """android_setup decides the scheme before it imports the relay, so it
        reads the two variables itself. Two predicates that can drift is how
        RELAY-2 happened, so pin them together."""
        relay = relay_module_for(tool_mod)
        for cert, key in [
            (None, None),
            ("/c.pem", None),
            (None, "/k.pem"),
            ("/c.pem", "/k.pem"),
        ]:
            for var, value in (("ANDROID_RELAY_CERT", cert), ("ANDROID_RELAY_KEY", key)):
                if value is None:
                    monkeypatch.delenv(var, raising=False)
                else:
                    monkeypatch.setenv(var, value)
            assert tool_mod._relay_tls_enabled() is relay.is_tls_configured(), (
                f"the two TLS predicates disagree for cert={cert!r} key={key!r}"
            )

    # ── F24: a TLS address has to be a name the certificate covers ────────────
    #
    # The phone validates with the platform trust store, which means hostname
    # verification against whatever it dialed. Certificates are issued for DNS
    # names — Let's Encrypt will not issue one for a bare IP — so printing the
    # detected public IP produced a wss:// address that always failed the
    # handshake, i.e. the documented TLS setup did not work.

    def test_a_tls_address_uses_the_configured_public_host(self, tool_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/fullchain.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")
        monkeypatch.setenv("ANDROID_RELAY_PUBLIC_HOST", "relay.example.com")

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["server_address"] == "wss://relay.example.com:8766"
        assert "server_address_note" not in result
        assert "Server: wss://relay.example.com:8766" in result["user_instructions"]

    def test_a_tls_address_that_is_still_an_ip_comes_with_a_warning(
        self, tool_mod, monkeypatch
    ):
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/fullchain.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")
        monkeypatch.delenv("ANDROID_RELAY_PUBLIC_HOST", raising=False)

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["server_address"] == f"wss://{FAKE_IP}:8766"
        note = result["server_address_note"]
        assert "ANDROID_RELAY_PUBLIC_HOST" in note
        assert "certificate" in note.lower()
        assert note in result["user_instructions"], (
            "the note has to reach the human, not just the JSON"
        )

    def test_a_plaintext_address_needs_no_host_note(self, tool_mod, monkeypatch):
        """An IP is a perfectly good ws:// host; only TLS verification cares."""
        monkeypatch.delenv("ANDROID_RELAY_PUBLIC_HOST", raising=False)

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["server_address"] == f"ws://{FAKE_IP}:8766"
        assert "server_address_note" not in result

    def test_the_public_host_alone_does_not_change_a_plaintext_address(
        self, tool_mod, monkeypatch
    ):
        monkeypatch.setenv("ANDROID_RELAY_PUBLIC_HOST", "relay.example.com")

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["server_address"] == "ws://relay.example.com:8766"
        assert "server_address_note" not in result

    @pytest.mark.parametrize(
        "configured,expected",
        [(None, FAKE_IP), ("", FAKE_IP), ("relay.example.com", "relay.example.com")],
        ids=["unset", "empty", "set"],
    )
    def test_the_public_host_helper_falls_back_to_the_detected_ip(
        self, tool_mod, monkeypatch, configured, expected
    ):
        if configured is None:
            monkeypatch.delenv("ANDROID_RELAY_PUBLIC_HOST", raising=False)
        else:
            monkeypatch.setenv("ANDROID_RELAY_PUBLIC_HOST", configured)

        assert tool_mod._relay_public_host(FAKE_IP) == expected

    def test_the_public_host_does_not_change_where_the_relay_binds(
        self, tool_mod, monkeypatch
    ):
        """ANDROID_RELAY_HOST is the bind address and must stay that way; the new
        variable only changes what is printed."""
        relay = relay_module_for(tool_mod)
        monkeypatch.setenv("ANDROID_RELAY_HOST", "0.0.0.0")
        monkeypatch.setenv("ANDROID_RELAY_PUBLIC_HOST", "relay.example.com")

        assert relay._relay_host() == "0.0.0.0"
        assert tool_mod._relay_public_host(FAKE_IP) == "relay.example.com"

    # ── F34: do not pay for IP detection the answer to which is discarded ─────

    def test_a_configured_public_host_skips_ip_detection(self, tool_mod, monkeypatch):
        """`_get_public_ip()` is three external HTTPS calls at 3 s each plus a DNS
        fallback, and with `ANDROID_RELAY_PUBLIC_HOST` set every one of their answers
        is thrown away. It also hands this gateway's address to a third-party echo
        service on every setup, for nothing."""
        monkeypatch.setenv("ANDROID_RELAY_PUBLIC_HOST", "relay.example.com")
        calls = []

        def detect():
            calls.append("called")
            return FAKE_IP

        monkeypatch.setattr(tool_mod, "_get_public_ip", detect)

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert calls == [], "IP detection ran even though the host is configured"
        assert result["server_address"] == "ws://relay.example.com:8766"

    def test_a_configured_public_host_skips_ip_detection_under_tls_too(
        self, tool_mod, monkeypatch
    ):
        monkeypatch.setenv("ANDROID_RELAY_PUBLIC_HOST", "relay.example.com")
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/fullchain.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")
        calls = []
        monkeypatch.setattr(
            tool_mod, "_get_public_ip", lambda: calls.append("called") or FAKE_IP
        )

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert calls == []
        assert result["server_address"] == "wss://relay.example.com:8766"
        assert "server_address_note" not in result

    def test_ip_detection_still_runs_without_a_configured_host(self, tool_mod, monkeypatch):
        """The other half: skipping must not become "never detect"."""
        monkeypatch.delenv("ANDROID_RELAY_PUBLIC_HOST", raising=False)
        calls = []

        def detect():
            calls.append("called")
            return FAKE_IP

        monkeypatch.setattr(tool_mod, "_get_public_ip", detect)

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert calls == ["called"]
        assert result["server_address"] == f"ws://{FAKE_IP}:8766"

    def test_an_empty_public_host_still_detects(self, tool_mod, monkeypatch):
        """`_env()` treats an empty value as unset, so a blank variable in .env must
        not turn the printed address into `ws://:8766`."""
        monkeypatch.setenv("ANDROID_RELAY_PUBLIC_HOST", "")
        calls = []
        monkeypatch.setattr(
            tool_mod, "_get_public_ip", lambda: calls.append("called") or FAKE_IP
        )

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert calls == ["called"]
        assert result["server_address"] == f"ws://{FAKE_IP}:8766"

    def test_the_https_setup_still_prints_a_wss_address_for_the_phone(
        self, tool_mod, monkeypatch
    ):
        """Two different transports: the phone dials the WebSocket route, the
        tools call the HTTP one. Both have to follow the relay."""
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/fullchain.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")

        result = as_json(tool_mod.android_setup("PA2RCDE"))

        assert result["server_address"] == f"wss://{FAKE_IP}:8766"
        assert os.environ["ANDROID_BRIDGE_URL"] == "https://localhost:8766"


class TestBridgeTlsVerification:
    """F19: an https:// bridge URL also has to be *usable*, and the relay's
    certificate is issued for its public name — never for "localhost", so the
    handshake cannot pass hostname verification on the loopback URL the tools use.

    Verification is relaxed for a loopback bridge URL only. That traffic never
    leaves the machine, and an attacker positioned to intercept it already has
    local code execution and can read ~/.hermes/.env — the file holding the token
    — directly, so nothing is given up that was not already theirs. Any other
    https:// bridge URL keeps full verification.
    """

    @pytest.mark.parametrize(
        "url,expected",
        [
            ("http://localhost:8766", True),
            ("http://127.0.0.1:8766", True),
            ("https://localhost:8766", False),
            ("https://127.0.0.1:8766", False),
            ("https://[::1]:8766", False),
            ("https://LOCALHOST:8766", False),
            ("https://relay.example.com:8766", True),
            ("https://203.0.113.7:8766", True),
        ],
        ids=[
            "http-loopback",
            "http-127",
            "https-localhost",
            "https-127",
            "https-ipv6-loopback",
            "https-uppercase",
            "https-public-name",
            "https-public-ip",
        ],
    )
    def test_verify_follows_the_url(self, tool_mod, monkeypatch, url, expected):
        monkeypatch.setenv("ANDROID_BRIDGE_URL", url)

        assert tool_mod._bridge_tls_verify() is expected

    def test_every_call_site_passes_the_verify_setting(
        self, tool_mod, monkeypatch, bridge
    ):
        """A call site that drops it makes the https:// URL setup just wrote fail
        on that one tool, which is worse than failing on all of them."""
        rsps, _ = bridge
        rsps.add(responses.GET, "https://localhost:8766/ping", json={"status": "ok"})
        rsps.add(responses.GET, "https://localhost:8766/screen_hash", json={"hash": "x"})
        rsps.add(responses.POST, "https://localhost:8766/tap", json={"success": True})
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "https://localhost:8766")
        seen = []
        real_get, real_post = requests.get, requests.post

        def spy_get(target, **kwargs):
            seen.append(kwargs.get("verify", "MISSING"))
            return real_get(target, **kwargs)

        def spy_post(target, **kwargs):
            seen.append(kwargs.get("verify", "MISSING"))
            return real_post(target, **kwargs)

        monkeypatch.setattr(requests, "get", spy_get)
        monkeypatch.setattr(requests, "post", spy_post)

        tool_mod.android_ping()
        tool_mod.android_screen_hash()
        tool_mod.android_tap(1, 2)

        assert len(seen) == 3, f"expected three HTTP calls, saw {seen}"
        assert seen == [False, False, False]

    # ── F37: one narrow, module-level filter instead of a per-call context ─────
    #
    # F33's `warnings.catch_warnings()` was correctly scoped but not thread-safe:
    # it swaps the *process-wide* `warnings.filters` list for the duration of the
    # block, and every swap bumps the filter version, invalidating every module's
    # `__warningregistry__` cache. In a gateway that can serve tool calls on several
    # threads, one call's context could also un-suppress — or suppress — another
    # call's warning. A single `filterwarnings` entry whose *message* is pinned to
    # the loopback hosts is both narrower and installed once.

    @staticmethod
    def _urllib3_message(host):
        """The exact text urllib3 2.x warns with (connectionpool.py, `_validate_conn`).

        Spelled out rather than triggered, because triggering it needs a real TLS
        handshake against a real unverified socket. The host is what varies, and it
        comes from `parse_url(url).host` — bracketed for an IPv6 literal, which is
        the easy way to get this pin wrong.
        """
        return (
            f"Unverified HTTPS request is being made to host '{host}'. "
            "Adding certificate verification is strongly advised. See: "
            "https://urllib3.readthedocs.io/en/latest/advanced-usage.html#tls-warnings"
        )

    def test_the_pin_covers_every_host_form_urllib3_puts_in_the_message(self, tool_mod):
        from urllib3.util import parse_url

        for url in [
            "https://localhost:8766/ping",
            "https://127.0.0.1:8766/ping",
            "https://[::1]:8766/ping",
            "https://LOCALHOST:8766/ping",
        ]:
            host = parse_url(url).host
            assert self._suppressed(tool_mod, host), (
                f"{url} reaches urllib3 as host {host!r}, which the filter must cover"
            )

    def test_a_non_loopback_host_still_warns(self, tool_mod):
        """The whole point of pinning the message: an unverified request to anywhere
        else must still be heard, including a host that merely starts with a
        loopback name."""
        for host in [
            "relay.example.com",
            "203.0.113.7",
            "localhost.example.com",
            "127.0.0.1.evil.test",
            "::2",
            "10.0.0.5",
        ]:
            assert not self._suppressed(tool_mod, host), f"{host!r} must still warn"

    def test_the_filter_is_installed_once_and_idempotently(self, tool_mod):
        import warnings

        from urllib3.exceptions import InsecureRequestWarning

        def matching():
            return [
                entry
                for entry in warnings.filters
                if entry[2] is InsecureRequestWarning
                and getattr(entry[1], "pattern", None)
                == tool_mod._LOOPBACK_INSECURE_REQUEST_MESSAGE
            ]

        for _ in range(3):
            tool_mod._install_loopback_warning_filter()

        assert len(matching()) == 1, (
            "repeated installs must not stack duplicate filters and bump the "
            f"warning-filter version each time: {len(matching())} entries"
        )
        assert matching()[0][0] == "ignore"

    def test_no_blanket_insecure_request_suppression_is_installed(self, tool_mod, bridge, monkeypatch):
        """F33's actual complaint, still true under the new mechanism: nothing this
        module does may silence InsecureRequestWarning as a category."""
        import warnings

        from urllib3.exceptions import InsecureRequestWarning

        rsps, _ = bridge
        rsps.add(responses.GET, "https://localhost:8766/ping", json={"status": "ok"})
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "https://localhost:8766")

        tool_mod.android_ping()

        blanket = [
            entry
            for entry in warnings.filters
            if entry[0] == "ignore"
            and entry[2] is InsecureRequestWarning
            and getattr(entry[1], "pattern", None)
            != tool_mod._LOOPBACK_INSECURE_REQUEST_MESSAGE
        ]
        assert not blanket, f"a blanket InsecureRequestWarning ignore was installed: {blanket}"

    def test_the_per_call_context_manager_is_gone(self, tool_mod):
        """Pinned so it cannot come back: `catch_warnings` mutates the process-wide
        filter list and is documented as not thread-safe."""
        assert not hasattr(tool_mod, "_loopback_tls_warnings_suppressed")

    def test_a_verified_bridge_call_installs_no_filter(self, tool_mod, monkeypatch, bridge):
        """The common case — plaintext or a public https relay — never relaxes
        verification, so it must not touch the warning filters either."""
        import warnings

        rsps, _ = bridge
        rsps.add(responses.GET, "http://localhost:8766/ping", json={"status": "ok"})
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://localhost:8766")
        before = list(warnings.filters)

        tool_mod.android_ping()

        assert warnings.filters == before

    def _suppressed(self, tool_mod, host):
        """True when a warning for *host* is swallowed by the module's filter."""
        import warnings

        from urllib3.exceptions import InsecureRequestWarning

        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            warnings.filterwarnings(
                "ignore",
                category=InsecureRequestWarning,
                message=tool_mod._LOOPBACK_INSECURE_REQUEST_MESSAGE,
            )
            warnings.warn(self._urllib3_message(host), InsecureRequestWarning)
        return not caught

    def test_a_public_https_bridge_stays_fully_verified(self, tool_mod, monkeypatch, bridge):
        rsps, _ = bridge
        rsps.add(responses.GET, "https://relay.example.com:8766/ping", json={"status": "ok"})
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "https://relay.example.com:8766")
        seen = []
        real_get = requests.get

        def spy_get(target, **kwargs):
            seen.append(kwargs.get("verify", "MISSING"))
            return real_get(target, **kwargs)

        monkeypatch.setattr(requests, "get", spy_get)

        tool_mod.android_ping()

        assert seen == [True]
