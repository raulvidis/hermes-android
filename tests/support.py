"""Shared helpers for the Python toolset tests.

Two rules shape everything in here:

* **No test may touch a device or the network.** Every bridge call is answered
  by ``responses`` (the convention set by tests/conftest.py) or by an in-process
  stub, and the relay's async handlers are driven with fake request/socket
  objects rather than a listening port.
* **Both copies of the toolset are under test.** ``tools/`` is the standalone
  dev copy and ``hermes-android-plugin/`` is what ships inside hermes-agent.
  They are maintained by hand (AGENTS.md), so the fixtures here hand the same
  test body each copy in turn — a divergence shows up as one param failing.

The plugin directory name contains dashes, so it cannot be imported normally;
``load_plugin_package()`` loads it under a synthetic package name and keeps the
relative imports inside it working.
"""

import importlib
import importlib.util
import json
import re
import sys
import types
from pathlib import Path

import requests
import responses

REPO_ROOT = Path(__file__).resolve().parents[1]
PLUGIN_DIR = REPO_ROOT / "hermes-android-plugin"
PLUGIN_PKG = "hermes_android_plugin_copy"

_plugin_loaded = False


# ── Loading the two copies ────────────────────────────────────────────────────


def load_plugin_package():
    """Import hermes-android-plugin/ as a package under a synthetic name.

    ``__init__.py`` only imports ``.android_tool``, so ``.android_relay`` is
    loaded explicitly — a test that exercises the plugin copy's relay must not
    accidentally get the ``tools/`` one.
    """
    global _plugin_loaded
    if _plugin_loaded:
        return sys.modules[PLUGIN_PKG]

    spec = importlib.util.spec_from_file_location(
        PLUGIN_PKG,
        PLUGIN_DIR / "__init__.py",
        submodule_search_locations=[str(PLUGIN_DIR)],
    )
    package = importlib.util.module_from_spec(spec)
    sys.modules[PLUGIN_PKG] = package
    spec.loader.exec_module(package)
    importlib.import_module(f"{PLUGIN_PKG}.android_relay")
    _plugin_loaded = True
    return package


def tools_copy():
    from tools import android_tool

    return android_tool


def plugin_tool_copy():
    load_plugin_package()
    return sys.modules[f"{PLUGIN_PKG}.android_tool"]


def tools_relay_copy():
    from tools import android_relay

    return android_relay


def plugin_relay_copy():
    load_plugin_package()
    return sys.modules[f"{PLUGIN_PKG}.android_relay"]


def reset_tool_module(module):
    """Drop per-module caches so one test cannot leak config into the next.

    ``_ENV_FILE_CACHE`` memoises the parsed ``~/.hermes/.env`` for the life of
    the process; without this reset a test that points HERMES_HOME somewhere
    would silently keep serving the previous test's values.
    """
    module._ENV_FILE_CACHE = None


def relay_module_for(tool_module):
    """The android_relay copy that a given android_tool copy imports at call time.

    android_setup does a relative import, so the tools/ copy binds
    tools.android_relay while the plugin copy binds its own — patching the wrong
    one would leave the test starting a real relay.
    """
    return importlib.import_module(f"{tool_module.__package__}.android_relay")


def loaded_tool_copies():
    """android_tool modules already imported — never forces the plugin to load."""
    modules = [sys.modules["tools.android_tool"]] if "tools.android_tool" in sys.modules else []
    plugin = sys.modules.get(f"{PLUGIN_PKG}.android_tool")
    if plugin is not None:
        modules.append(plugin)
    return modules


# ── The tool surface, as a table ──────────────────────────────────────────────
#
# (name, http method, bridge path, callable taking the module under test)
#
# android_setup and android_macro are deliberately absent: setup starts the
# relay and macro dispatches to other tools, so both get their own tests.
# test_the_error_table_covers_every_registered_tool asserts the table plus those
# two is exactly _HANDLERS, so a new tool cannot slip through untested.

TOOL_CALLS = [
    ("android_ping", "GET", "/ping", lambda m: m.android_ping()),
    ("android_read_screen", "GET", "/screen", lambda m: m.android_read_screen()),
    ("android_screenshot", "GET", "/screenshot", lambda m: m.android_screenshot()),
    ("android_get_apps", "GET", "/apps", lambda m: m.android_get_apps()),
    ("android_current_app", "GET", "/current_app", lambda m: m.android_current_app()),
    ("android_clipboard_read", "GET", "/clipboard", lambda m: m.android_clipboard_read()),
    (
        "android_notifications",
        "GET",
        "/notifications",
        lambda m: m.android_notifications(limit=5),
    ),
    ("android_events", "GET", "/events", lambda m: m.android_events(limit=5)),
    ("android_screen_hash", "GET", "/screen_hash", lambda m: m.android_screen_hash()),
    ("android_location", "GET", "/location", lambda m: m.android_location()),
    ("android_read_widgets", "GET", "/widgets", lambda m: m.android_read_widgets()),
    ("android_mic_status", "GET", "/mic_status", lambda m: m.android_mic_status()),
    ("android_tap", "POST", "/tap", lambda m: m.android_tap(10, 20)),
    ("android_tap_text", "POST", "/tap_text", lambda m: m.android_tap_text("Send")),
    ("android_type", "POST", "/type", lambda m: m.android_type("hunter2")),
    ("android_swipe", "POST", "/swipe", lambda m: m.android_swipe("up")),
    (
        "android_open_app",
        "POST",
        "/open_app",
        lambda m: m.android_open_app("com.example.app"),
    ),
    ("android_press_key", "POST", "/press_key", lambda m: m.android_press_key("back")),
    ("android_scroll", "POST", "/scroll", lambda m: m.android_scroll("down")),
    ("android_wait", "POST", "/wait", lambda m: m.android_wait(text="Send")),
    ("android_long_press", "POST", "/long_press", lambda m: m.android_long_press(10, 20)),
    ("android_drag", "POST", "/drag", lambda m: m.android_drag(1, 2, 3, 4)),
    (
        "android_describe_node",
        "POST",
        "/describe_node",
        lambda m: m.android_describe_node("n1"),
    ),
    ("android_find_nodes", "POST", "/find_nodes", lambda m: m.android_find_nodes(text="Send")),
    (
        "android_diff_screen",
        "POST",
        "/diff_screen",
        lambda m: m.android_diff_screen("deadbeef"),
    ),
    ("android_pinch", "POST", "/pinch", lambda m: m.android_pinch(100, 200)),
    (
        "android_send_sms",
        "POST",
        "/send_sms",
        lambda m: m.android_send_sms("+15551234567", "on my way"),
    ),
    ("android_call", "POST", "/call", lambda m: m.android_call("+15551234567")),
    ("android_media", "POST", "/media", lambda m: m.android_media("play")),
    (
        "android_send_intent",
        "POST",
        "/intent",
        lambda m: m.android_send_intent("android.settings.WIFI_SETTINGS"),
    ),
    (
        "android_broadcast",
        "POST",
        "/broadcast",
        lambda m: m.android_broadcast("com.example.PING"),
    ),
    ("android_speak", "POST", "/speak", lambda m: m.android_speak("hello")),
    ("android_speak_stop", "POST", "/stop_speaking", lambda m: m.android_speak_stop()),
    (
        "android_screen_record",
        "POST",
        "/screen_record",
        lambda m: m.android_screen_record(500),
    ),
    (
        "android_event_stream",
        "POST",
        "/events/stream",
        lambda m: m.android_event_stream(True),
    ),
    ("android_mic_record", "POST", "/mic_start", lambda m: m.android_mic_record(5)),
    ("android_mic_stop", "POST", "/mic_stop", lambda m: m.android_mic_stop()),
    (
        "android_clipboard_write",
        "POST",
        "/clipboard",
        lambda m: m.android_clipboard_write("clipboard-secret"),
    ),
    (
        "android_search_contacts",
        "GET",
        "/contacts",
        lambda m: m.android_search_contacts("Jane Doe"),
    ),
    ("android_mic_fetch", "GET", "/mic_file", lambda m: m.android_mic_fetch()),
]

TOOL_CALL_NAMES = [entry[0] for entry in TOOL_CALLS]
NON_TABLE_TOOLS = ("android_setup", "android_macro")


# The bridge host used by the transport-error fixtures. 203.0.113.0/24 is
# TEST-NET-3 (RFC 5737) — documentation space, never a real device.
FAKE_BRIDGE_HOST = "203.0.113.7"
FAKE_BRIDGE_PORT = 8766


def _connection_error_message(path="/contacts?query=Jane%20Doe&limit=20"):
    """The message requests really produces for an unreachable bridge.

    It carries the host, the port and the path *with its query string*, exactly
    as requests percent-encoded it — which is what makes it the right input for
    the redaction tests: whatever ends up in a tool response came out of this
    string, and a GET tool's arguments are in it.
    """
    return (
        f"HTTPConnectionPool(host='{FAKE_BRIDGE_HOST}', port={FAKE_BRIDGE_PORT}): "
        f"Max retries exceeded with url: {path} (Caused by NewConnectionError("
        f"\"HTTPConnection(host='{FAKE_BRIDGE_HOST}', port={FAKE_BRIDGE_PORT}): "
        'Failed to establish a new connection: [Errno 111] Connection refused"))'
    )


def add_transport_error(rsps, message=None, exception=None):
    """Answer every URL with a connection error (no socket is ever opened)."""
    message = _connection_error_message() if message is None else message
    error = exception or requests.exceptions.ConnectionError(message)
    rsps.add(responses.GET, re.compile(r".*"), body=error)
    rsps.add(responses.POST, re.compile(r".*"), body=error)
    return error


def add_timeout_error(rsps):
    return add_transport_error(
        rsps,
        exception=requests.exceptions.ReadTimeout(_timeout_error_message()),
    )


def _timeout_error_message():
    """A read-timeout message in the shape requests/urllib3 actually produce."""
    return (
        "HTTPConnectionPool(host='203.0.113.7', port=8766): Read timed out. "
        "(read timeout=5)"
    )


# ── Result-shape helpers ──────────────────────────────────────────────────────


def as_json(result):
    """json.loads a tool result; None when it is a ``MEDIA:`` text response."""
    try:
        return json.loads(result)
    except (TypeError, ValueError):
        return None


def is_error(result) -> bool:
    """True when a tool result reports failure.

    Tools disagree on the shape: most return ``{"error": ...}``, android_ping
    returns ``{"status": "error", "message": ...}``, and the three that hand
    back a file return plain text with a ``MEDIA:`` line on success.
    """
    data = as_json(result)
    if not isinstance(data, dict):
        return False
    return "error" in data or data.get("status") == "error"


# ── MEDIA: helpers ────────────────────────────────────────────────────────────

_MEDIA_RE = re.compile(r"^MEDIA:(?P<path>.+)$", re.MULTILINE)


def media_path(result: str):
    """Return the path from a ``MEDIA:<path>`` line, or None."""
    match = _MEDIA_RE.search(result or "")
    return match.group("path") if match else None


def collect_media_paths(results):
    return [p for p in (media_path(r) for r in results) if p]


def remove_paths(paths):
    """Delete temp files a test caused the toolset to create."""
    for path in paths:
        try:
            Path(path).unlink()
        except OSError:
            pass


# ── Stub HTTP layer for the streaming download ────────────────────────────────


class StubStreamResponse:
    """Stand-in for the ``requests`` response object android_mic_fetch uses.

    Supports the whole surface the tool touches: context-manager protocol,
    ``status_code``, ``headers``, ``json()``, ``text`` and ``iter_content()``.
    ``iter_content`` can raise mid-stream, which is how the incomplete-download
    and oversize-chunk paths are reached without a real socket.
    """

    def __init__(
        self,
        status_code=200,
        headers=None,
        chunks=(),
        json_body=None,
        text="",
        iter_error=None,
        json_error=None,
    ):
        self.status_code = status_code
        self.headers = dict(headers or {})
        self._chunks = list(chunks)
        self._json_body = json_body
        self.text = text
        self._iter_error = iter_error
        self._json_error = json_error
        self.request_url = None
        self.request_params = None

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False

    def iter_content(self, chunk_size=None):
        for chunk in self._chunks:
            yield chunk
        if self._iter_error is not None:
            raise self._iter_error

    def json(self):
        if self._json_error is not None:
            raise self._json_error
        if self._json_body is None:
            raise ValueError("No JSON body")
        return self._json_body


def install_stub_get(module, monkeypatch, response):
    """Route ``module.requests.get`` at a StubStreamResponse.

    Only the module under test is patched (its own ``requests`` name is
    rebound), so the real requests library — and any ``responses`` mock in
    flight — is left alone.
    """
    shim = types.SimpleNamespace(
        exceptions=requests.exceptions,
        get=lambda url, **kwargs: _record(response, url, kwargs),
        post=module.requests.post,
    )
    monkeypatch.setattr(module, "requests", shim)
    return response


def _record(response, url, kwargs):
    response.request_url = url
    response.request_params = kwargs.get("params")
    return response


# ── Fakes for the relay's async handlers ──────────────────────────────────────


class FakeRequest:
    """The slice of aiohttp's web.Request the relay handlers actually read."""

    def __init__(
        self,
        method="GET",
        headers=None,
        query=None,
        json_body=None,
        remote="203.0.113.9",
    ):
        self.method = method
        self.headers = dict(headers or {})
        self.query = dict(query or {})
        self.remote = remote
        self._json_body = json_body

    async def json(self):
        if isinstance(self._json_body, Exception):
            raise self._json_body
        if self._json_body is None:
            raise ValueError("no JSON body")
        return self._json_body


class FakePhoneSocket:
    """Stands in for the phone's WebSocket."""

    def __init__(self, closed=False, send_error=None):
        self.closed = closed
        self.sent = []
        self.close_calls = []
        self._send_error = send_error

    async def send_json(self, payload):
        if self._send_error is not None:
            raise self._send_error
        self.sent.append(payload)

    async def close(self, code=None, message=None):
        self.closed = True
        self.close_calls.append((code, message))


class FakeStreamResponse:
    """Stands in for aiohttp's web.StreamResponse (records instead of sending)."""

    def __init__(self, status=200, headers=None):
        self.status = status
        self.headers = dict(headers or {})
        self.prepared = False
        self.chunks = []
        self.eof = False
        self.forced_close = False

    async def prepare(self, request):
        self.prepared = True

    async def write(self, data):
        self.chunks.append(data)

    async def write_eof(self):
        self.eof = True

    def force_close(self):
        self.forced_close = True

    @property
    def body(self):
        return b"".join(self.chunks)


def fake_site_factory():
    """Build a web.TCPSite stand-in plus the list it records into.

    Lets _serve() run — routes registered, warning logged, shutdown awaited —
    without binding a port.
    """
    created = []

    class FakeSite:
        def __init__(self, runner, host=None, port=None, **kwargs):
            self.runner = runner
            self.host = host
            self.port = port
            self.kwargs = kwargs
            self.started = False
            created.append(self)

        async def start(self):
            self.started = True

    return FakeSite, created


class WebShim:
    """Proxy over aiohttp.web with selected attributes overridden.

    Patching ``module.web`` (rather than ``aiohttp.web``) keeps the substitution
    local to the copy under test.
    """

    def __init__(self, real, **overrides):
        self._real = real
        self._overrides = overrides

    def __getattr__(self, name):
        if name in self._overrides:
            return self._overrides[name]
        return getattr(self._real, name)


def _response_body(response) -> str:
    """Text body of an aiohttp response object built by web.json_response."""
    body = response.body
    if isinstance(body, bytes):
        return body.decode("utf-8")
    return "" if body is None else str(body)


def response_json(response):
    """Decoded body of an aiohttp response built by web.json_response."""
    return json.loads(_response_body(response))


def stream_frame(request_id: str, payload: bytes) -> bytes:
    """Encode a phone binary frame: uint16 id length, UTF-8 id, payload."""
    encoded = request_id.encode("utf-8")
    return len(encoded).to_bytes(2, "big") + encoded + payload


def make_relay_state(module, pairing_code="PAIRCODE", port=19999, socket=None, socket_code=None):
    """Build a _RelayState wired for in-loop use (locks + no real server).

    Must be called from inside the running event loop: ``_handle_http`` creates
    its pending futures on ``state.loop``, and a future bound to a different
    loop can never be resolved by the handler under test.
    """
    import asyncio

    state = module._RelayState(pairing_code=pairing_code, port=port)
    state.phone_ws_lock = asyncio.Lock()
    state.pending_lock = asyncio.Lock()
    state.streams_lock = asyncio.Lock()
    state.shutdown_event = asyncio.Event()
    state.loop = asyncio.get_running_loop()
    state.phone_ws = socket
    # F21: the relay records the pairing code each session authenticated with, so
    # a rotation can tell a stale session from a current one. A socket handed in
    # without an explicit code is a session that proved the current one.
    if socket is not None and socket_code is None:
        socket_code = pairing_code
    state.phone_ws_code = socket_code
    return state


def wav_bytes(payload: bytes = b"\x01\x02" * 20) -> bytes:
    """A minimal but structurally valid WAV header + payload."""
    return b"RIFF" + (36 + len(payload)).to_bytes(4, "little") + b"WAVE" + payload
