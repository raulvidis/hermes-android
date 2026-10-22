"""Relay paths that need no listening socket: startup and teardown, the TLS
context, the auth window, the WebSocket handler, phone-message framing and the
HTTP forwarding handler.

The existing tests in test_android_relay.py drive a real relay over loopback,
which is the right way to prove the whole thing works end to end. These go the
other way: every handler is called directly with fake request/socket objects and
a fake TCPSite, so a branch that only triggers on a malformed frame, a dead
phone or an expired rate-limit window can be reached deterministically and in
milliseconds. Nothing here binds a port.

Both copies are exercised; android_relay.py is byte-identical between them (an
existing test asserts that), so a param failing only for "plugin" would mean the
loader, not the code.
"""

import asyncio
import hashlib
import json
import logging
import ssl
import threading
import time
import types
import uuid

import aiohttp
import pytest

from tests.support import (
    FakePhoneSocket,
    FakeRequest,
    FakeStreamResponse,
    WebShim,
    fake_site_factory,
    make_relay_state,
    response_json,
    stream_frame,
)

LOGGER = "android_relay"
TOKEN = "Bearer PAIRCODE"
AUTH = {"Authorization": TOKEN}


def run(coro):
    return asyncio.run(coro)


# ── Startup and teardown ──────────────────────────────────────────────────────


class _FakeThreading:
    """threading stand-in for start_relay's readiness handshake.

    A real Event by default, so start_relay really does wait for the (fake) loop
    to report ready — pre-setting it would let start_relay return before the
    thread had recorded anything, which is a race the tests would only lose
    occasionally.

    ``never_ready=True`` gives the "it never came up" branch without spending the
    real 10 seconds waiting for it.
    """

    Lock = threading.Lock
    Thread = threading.Thread

    def __init__(self, never_ready=False):
        self._never_ready = never_ready
        self.events = []

    def Event(self):  # noqa: N802 - mirrors the threading API
        event = threading.Event()
        if self._never_ready:
            event.wait = lambda timeout=None: False
        self.events.append(event)
        return event


class TestStartRelay:
    @pytest.fixture(autouse=True)
    def no_real_server(self, relay_mod, monkeypatch):
        """Never bind: the loop body is replaced, and the module's threading is
        swapped so the readiness wait is instant and observable."""
        self.started_states = []
        self.release = threading.Event()

        def fake_run_loop(state, ready):
            self.started_states.append(state)
            state.loop = None
            ready.set()
            # A real relay thread lives until it is stopped; a second
            # start_relay() only no-ops while this one is still alive.
            self.release.wait(10)

        monkeypatch.setattr(relay_mod, "_run_loop", fake_run_loop)
        self.threading = _FakeThreading()
        monkeypatch.setattr(relay_mod, "threading", self.threading)
        yield
        self.release.set()
        relay_mod._relay_instance = None

    def test_the_port_defaults_to_the_env_var(self, relay_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_PORT", "9100")

        relay_mod.start_relay(pairing_code="PAIRCODE", port=0)

        assert self.started_states[0].port == 9100

    def test_the_port_default_is_8766_when_unset(self, relay_mod, monkeypatch):
        monkeypatch.delenv("ANDROID_RELAY_PORT", raising=False)

        relay_mod.start_relay(pairing_code="PAIRCODE")

        assert self.started_states[0].port == 8766

    def test_an_explicit_port_wins_over_the_env_var(self, relay_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_PORT", "9100")

        relay_mod.start_relay(pairing_code="PAIRCODE", port=19555)

        assert self.started_states[0].port == 19555

    def test_the_pairing_code_reaches_the_state(self, relay_mod):
        relay_mod.start_relay(pairing_code="PAIRCODE", port=19555)
        assert self.started_states[0].pairing_code == "PAIRCODE"

    def test_a_second_start_is_a_noop(self, relay_mod, caplog):
        relay_mod.start_relay(pairing_code="PAIRCODE", port=19555)
        first = relay_mod._relay_instance

        with caplog.at_level(logging.INFO, logger=LOGGER):
            relay_mod.start_relay(pairing_code="OTHER", port=19556)

        assert relay_mod._relay_instance is first
        assert len(self.started_states) == 1
        assert "already running" in caplog.text

    def test_a_relay_that_never_becomes_ready_raises(self, relay_mod, monkeypatch, caplog):
        """The port is taken, or the loop died: start_relay must fail loudly
        rather than leave the toolset pointing at nothing."""
        self.threading._never_ready = True

        def dead_loop(state, ready):
            # The real _run_loop logs "Relay event loop crashed" and closes its
            # loop; either way `ready` is never set, which is what start_relay
            # has to notice.
            state.loop = None

        monkeypatch.setattr(relay_mod, "_run_loop", dead_loop)

        with caplog.at_level(logging.ERROR, logger=LOGGER):
            with pytest.raises(RuntimeError, match="Relay failed to start"):
                relay_mod.start_relay(pairing_code="PAIRCODE", port=19555)

        assert "failed to start within 10 seconds" in caplog.text

    def test_a_failed_start_rolls_the_instance_back(self, relay_mod, monkeypatch):
        """The dead state must not stay reachable through the public API. Leaving
        it registered is what makes the next stop_relay() — the teardown every
        caller wraps around a start — reach for a loop the relay thread has
        already closed."""
        self.threading._never_ready = True
        monkeypatch.setattr(relay_mod, "_run_loop", lambda state, ready: None)

        with pytest.raises(RuntimeError):
            relay_mod.start_relay(pairing_code="PAIRCODE", port=19555)

        assert relay_mod._relay_instance is None
        assert relay_mod.is_relay_running() is False

    def test_stop_after_a_failed_start_does_not_raise(self, relay_mod, monkeypatch):
        self.threading._never_ready = True
        loop = asyncio.new_event_loop()
        loop.close()

        def dead_loop(state, ready):
            state.loop = loop
            state.shutdown_event = asyncio.Event()

        monkeypatch.setattr(relay_mod, "_run_loop", dead_loop)

        with pytest.raises(RuntimeError):
            relay_mod.start_relay(pairing_code="PAIRCODE", port=19555)

        relay_mod.stop_relay()  # must be a no-op, not an exception

        assert relay_mod._relay_instance is None


class TestRunLoopCrash:
    def test_a_crashing_serve_is_logged_and_the_loop_is_closed(self, relay_mod, monkeypatch, caplog):
        """The relay runs on a daemon thread; if _serve dies the loop must still
        be closed and the failure must be in the log, or the relay disappears
        without a trace."""
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=19555)
        ready = threading.Event()

        async def crashing_serve(state, ready):
            raise RuntimeError("kaboom")

        monkeypatch.setattr(relay_mod, "_serve", crashing_serve)

        with caplog.at_level(logging.ERROR, logger=LOGGER):
            relay_mod._run_loop(state, ready)

        assert state.loop.is_closed() is True
        assert "Relay event loop crashed" in caplog.text
        # It died before listening, so start_relay() — blocked on `ready` — is
        # woken with the cause instead of waiting out its 10 s timeout.
        assert ready.is_set() is True
        assert str(state.start_error) == "kaboom"

    def test_a_crash_after_listening_is_not_reported_as_a_start_error(self, relay_mod, monkeypatch):
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=19555)
        ready = threading.Event()

        async def crash_later(state, ready):
            ready.set()
            raise RuntimeError("kaboom")

        monkeypatch.setattr(relay_mod, "_serve", crash_later)

        relay_mod._run_loop(state, ready)

        assert state.start_error is None

    def test_the_loop_primitives_are_created_before_serving(self, relay_mod, monkeypatch):
        """_serve and the handlers assume the locks exist; a handler that runs
        before they are set would raise on `async with None`."""
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=19555)
        seen = {}

        async def capture(state, ready):
            seen.update(
                loop=state.loop,
                phone_ws_lock=state.phone_ws_lock,
                pending_lock=state.pending_lock,
                streams_lock=state.streams_lock,
                shutdown_event=state.shutdown_event,
            )
            ready.set()

        monkeypatch.setattr(relay_mod, "_serve", capture)
        ready = threading.Event()

        relay_mod._run_loop(state, ready)

        assert ready.is_set()
        assert all(value is not None for value in seen.values())
        assert isinstance(seen["loop"], asyncio.AbstractEventLoop)


class TestStopRelay:
    def test_stopping_a_relay_that_was_never_started_is_a_noop(self, relay_mod):
        relay_mod._relay_instance = None
        relay_mod.stop_relay()
        assert relay_mod._relay_instance is None

    def test_the_instance_is_cleared_before_the_thread_is_joined(self, relay_mod, monkeypatch):
        """A concurrent start_relay must not see the old instance and no-op."""
        order = []
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)

        class FakeLoop:
            def call_soon_threadsafe(self, callback):
                order.append("signal")
                callback()

        class FakeThread:
            def join(self, timeout=None):
                order.append("join")
                assert relay_mod._relay_instance is None

        state.loop = FakeLoop()
        state.thread = FakeThread()
        shutdown = asyncio.Event()
        state.shutdown_event = shutdown
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        relay_mod.stop_relay()

        assert order == ["signal", "join"]
        assert shutdown.is_set()

    def test_a_state_with_no_loop_skips_the_shutdown_signal(self, relay_mod, monkeypatch):
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)
        state.loop = None
        state.shutdown_event = None

        class FakeThread:
            joined = False

            def join(self, timeout=None):
                FakeThread.joined = True

        state.thread = FakeThread()
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        relay_mod.stop_relay()

        assert FakeThread.joined is True

    def test_a_state_with_no_thread_skips_the_join(self, relay_mod, monkeypatch):
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)
        state.loop = None
        state.thread = None
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        relay_mod.stop_relay()

        assert relay_mod._relay_instance is None

    def test_a_loop_that_already_finished_does_not_raise(self, relay_mod, monkeypatch):
        """_run_loop closes its loop in a finally, so a stop that lands after the
        thread died — a crashed serve, or the teardown a caller wraps around a
        failed start — hits call_soon_threadsafe() on a closed loop. That raises
        RuntimeError("Event loop is closed"), which used to escape stop_relay()
        and take the caller's cleanup with it.

        start_relay() now rolls the dead instance back, so this state is reached
        by a loop that dies *after* a successful start rather than by a failed
        one; it is covered directly because the rollback hides it from the
        start/stop test above.
        """
        loop = asyncio.new_event_loop()
        loop.close()
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)
        state.loop = loop
        state.shutdown_event = asyncio.Event()
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        relay_mod.stop_relay()

        assert relay_mod._relay_instance is None

    def test_a_loop_that_closes_between_the_check_and_the_signal_does_not_raise(
        self, relay_mod, monkeypatch
    ):
        """The race the is_closed() answer cannot cover: the loop is open when
        stop_relay() looks and closed by the time it calls."""

        class LoopClosingUnderUs:
            def call_soon_threadsafe(self, callback):
                raise RuntimeError("Event loop is closed")

        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)
        state.loop = LoopClosingUnderUs()
        state.shutdown_event = asyncio.Event()
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        relay_mod.stop_relay()

        assert relay_mod._relay_instance is None


class TestPublicState:
    def test_is_relay_running_is_false_for_a_dead_thread(self, relay_mod, monkeypatch):
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)
        thread = threading.Thread(target=lambda: None)
        thread.start()
        thread.join()
        state.thread = thread
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        assert relay_mod.is_relay_running() is False

    def test_is_relay_running_needs_a_thread(self, relay_mod, monkeypatch):
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        assert relay_mod.is_relay_running() is False

    def test_is_phone_connected_is_false_without_a_socket(self, relay_mod, monkeypatch):
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        assert relay_mod.is_phone_connected() is False

    def test_is_phone_connected_is_true_for_an_open_socket(self, relay_mod, monkeypatch):
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)
        state.phone_ws = FakePhoneSocket(closed=False)
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        assert relay_mod.is_phone_connected() is True

    def test_is_phone_connected_is_false_when_no_relay_was_ever_started(
        self, relay_mod, monkeypatch
    ):
        monkeypatch.setattr(relay_mod, "_relay_instance", None)

        assert relay_mod.is_phone_connected() is False

    def test_is_phone_connected_is_false_for_a_closed_socket(self, relay_mod, monkeypatch):
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=1)
        state.phone_ws = FakePhoneSocket(closed=True)
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        assert relay_mod.is_phone_connected() is False

    def test_get_relay_url_reports_the_running_port(self, relay_mod, monkeypatch):
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=19555)
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        assert relay_mod.get_relay_url() == "http://localhost:19555"

    def test_set_pairing_code_updates_a_running_relay(self, relay_mod, monkeypatch, caplog):
        """Re-pairing without a restart: the phone reconnects with a new code and
        the relay has to accept it."""
        state = relay_mod._RelayState(pairing_code="OLDCODE", port=1)
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        with caplog.at_level(logging.INFO, logger=LOGGER):
            relay_mod.set_pairing_code("NEWCODE")

        assert state.pairing_code == "NEWCODE"
        assert "Pairing code updated" in caplog.text
        assert "NEWCODE" not in caplog.text

    def test_set_pairing_code_without_a_relay_is_a_noop(self, relay_mod, monkeypatch):
        monkeypatch.setattr(relay_mod, "_relay_instance", None)
        relay_mod.set_pairing_code("NEWCODE")
        assert relay_mod._relay_instance is None


class TestSslContext:
    def test_no_configuration_means_plaintext(self, relay_mod, monkeypatch):
        monkeypatch.delenv("ANDROID_RELAY_CERT", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_KEY", raising=False)

        assert relay_mod._ssl_context() is None

    @pytest.mark.parametrize("present", ["cert", "key"], ids=["cert_only", "key_only"])
    def test_half_a_configuration_is_ignored(self, relay_mod, monkeypatch, present):
        """TLS needs both halves; silently serving plaintext because one path was
        misspelled would be worse than failing, but that is what happens today."""
        monkeypatch.delenv("ANDROID_RELAY_CERT", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_KEY", raising=False)
        monkeypatch.setenv(
            "ANDROID_RELAY_CERT" if present == "cert" else "ANDROID_RELAY_KEY", "/tmp/x.pem"
        )

        assert relay_mod._ssl_context() is None

    def test_both_paths_build_a_server_context(self, relay_mod, monkeypatch):
        loaded = []
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/cert.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")
        monkeypatch.setattr(
            ssl.SSLContext,
            "load_cert_chain",
            lambda self, certfile, keyfile: loaded.append((certfile, keyfile)),
        )

        context = relay_mod._ssl_context()

        assert isinstance(context, ssl.SSLContext)
        assert loaded == [("/certs/cert.pem", "/certs/key.pem")]

    def test_unreadable_certificate_files_raise(self, relay_mod, monkeypatch):
        """Pinned: a wrong path is not swallowed here, so it surfaces as a failed
        relay start rather than a silently plaintext relay."""
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/nonexistent/cert.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/nonexistent/key.pem")

        with pytest.raises((FileNotFoundError, OSError, ssl.SSLError)):
            relay_mod._ssl_context()


class TestRotationDuringHandshake:
    """Review F21: a handshake accepted with a code that rotates mid-flight.

    `_handle_ws` validates the token, then `await ws.prepare(request)`, and only
    afterwards publishes `state.phone_ws`. `set_pairing_code()` runs on the
    caller's thread and needs only `_relay_lock`, which `_handle_ws` never holds —
    so the code can change inside that await. F17's drop targeted the socket that
    was current *when the code changed*, and a handshake in flight was not current
    yet, so it published afterwards and survived: a session holding full device
    control on a pairing code that no longer exists, which is precisely what
    AUTH-1's rotation exists to end.
    """

    @pytest.fixture
    def hookable_websocket(self, relay_mod, monkeypatch):
        """A fake WebSocketResponse whose prepare() runs a test hook — the seam a
        concurrent rotation lands in."""
        created = []
        self.messages = []
        self.on_prepare = None
        outer = self

        def factory(**kwargs):
            class Hooked(FakeWebSocket):
                async def prepare(self, request):
                    await super().prepare(request)
                    if outer.on_prepare is not None:
                        outer.on_prepare()

            socket = Hooked(messages=outer.messages, **kwargs)
            created.append(socket)
            return socket

        monkeypatch.setattr(
            relay_mod, "web", WebShim(aiohttp.web, WebSocketResponse=factory)
        )
        return created

    def test_a_handshake_accepted_with_a_rotated_code_is_closed_not_published(
        self, relay_mod, hookable_websocket, caplog
    ):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="OLDCODE")
            # The rotation lands between the token check and publication.
            self.on_prepare = lambda: setattr(state, "pairing_code", "NEWCODE")
            request = FakeRequest(headers={"Authorization": "Bearer OLDCODE"})
            with caplog.at_level(logging.INFO, logger=LOGGER):
                returned = await relay_mod._handle_ws(request, state)
            return state, returned

        state, returned = run(scenario())

        assert state.phone_ws is None, "a session on a dead code must never be published"
        assert returned.closed is True, "and the socket must be closed, not left half-open"
        assert "rotated" in caplog.text
        # The code is a credential: it grants full device control.
        assert "OLDCODE" not in caplog.text
        assert "NEWCODE" not in caplog.text

    def test_an_unchanged_code_still_publishes_and_records_it(
        self, relay_mod, hookable_websocket, monkeypatch
    ):
        seen = {}

        async def spy(state, raw):
            # The message loop is the only window in which the session is
            # published, so it is where the recorded code can be observed.
            seen["ws"] = state.phone_ws
            seen["code"] = state.phone_ws_code

        monkeypatch.setattr(relay_mod, "_on_phone_message", spy)
        self.messages = [ws_message(aiohttp.WSMsgType.TEXT, '{"request_id":"r1"}')]

        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            returned = await relay_mod._handle_ws(FakeRequest(headers=AUTH), state)
            return state, returned

        state, returned = run(scenario())

        assert seen["code"] == "PAIRCODE", "the session must record the code it proved"
        assert seen["ws"] is returned

    def test_a_rotation_between_the_two_code_reads_cannot_publish_the_session(
        self, relay_mod, hookable_websocket, monkeypatch
    ):
        """Review F31: the handler read `state.pairing_code` twice — once to compare
        the token against, once to record what the session proved. A rotation
        landing between the two recorded the NEW code for a session that had proved
        the OLD one, so the post-prepare re-check compared NEW against NEW, passed,
        and published a session that no later rotation drop would ever close: it
        looks current. F21 closed the window between the check and `prepare`; this
        closes the one inside the check itself.
        """
        real = relay_mod._tokens_equal
        comparisons = []

        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="OLDCODE")
            seen = {}

            def rotating_equal(a, b):
                comparisons.append((a, b))
                result = real(a, b)
                if len(comparisons) == 1:
                    # The rotation lands after the token check and before the
                    # handler records which code this handshake proved.
                    state.pairing_code = "NEWCODE"
                return result

            async def spy(published_state, raw):
                seen["code"] = published_state.phone_ws_code

            monkeypatch.setattr(relay_mod, "_tokens_equal", rotating_equal)
            monkeypatch.setattr(relay_mod, "_on_phone_message", spy)
            self.messages = [ws_message(aiohttp.WSMsgType.TEXT, '{"request_id":"r1"}')]

            returned = await relay_mod._handle_ws(
                FakeRequest(headers={"Authorization": "Bearer OLDCODE"}), state
            )
            return state, returned, seen

        state, returned, seen = run(scenario())

        assert comparisons, "the token was never compared"
        assert seen == {}, (
            "the session was published and served a command on a code that no "
            f"longer exists (recorded as {seen.get('code')!r})"
        )
        assert state.phone_ws_code is None
        assert returned.closed is True
        assert returned.close_calls, "the socket must be closed, not left half-open"
        assert returned.close_calls[0][0] == aiohttp.WSCloseCode.POLICY_VIOLATION

    def test_a_rejected_handshake_never_reaches_the_recheck(self, relay_mod, hookable_websocket):
        """The re-check is not a second authentication: a wrong code is still
        refused up front, and the hook never runs because prepare() is not
        reached."""
        calls = []

        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            self.on_prepare = lambda: calls.append("prepared")
            with pytest.raises(Exception):
                await relay_mod._handle_ws(
                    FakeRequest(headers={"Authorization": "Bearer WRONGCODE"}), state
                )
            return state

        state = run(scenario())

        assert calls == []
        assert state.phone_ws is None


class TestPairingRotationDrop:
    """The rotation drop, decided by the code each session authenticated with.

    F17 made the drop target a socket captured when the code changed, so a session
    that re-authenticated with the NEW code in the meantime survived. F21 showed
    the capture is not enough on its own — a handshake in flight is not captured at
    all — so the rule is now "close whatever proved a code that is no longer
    current", which needs no capture and covers both.
    """

    def test_a_session_on_the_old_code_is_closed(self, relay_mod):
        async def scenario():
            state = make_relay_state(
                relay_mod,
                pairing_code="NEWCODE",
                socket=FakePhoneSocket(),
                socket_code="OLDCODE",
            )
            stale = state.phone_ws
            await relay_mod._drop_phone_after_rotation(state)
            return state, stale

        state, stale = run(scenario())

        assert stale.closed is True, "the session that proved the dead code must go"
        assert state.phone_ws is None
        assert state.phone_ws_code is None

    def test_a_session_on_the_current_code_survives_with_its_inflight_work(self, relay_mod):
        """F17 and F20 in one: a session that proved the live code keeps its socket,
        stays registered, and keeps its pending request."""
        async def scenario():
            state = make_relay_state(
                relay_mod, pairing_code="NEWCODE", socket=FakePhoneSocket(), socket_code="NEWCODE"
            )
            future = state.loop.create_future()
            async with state.pending_lock:
                state.pending["rid-live"] = future
            await relay_mod._drop_phone_after_rotation(state)
            async with state.pending_lock:
                still_pending = dict(state.pending)
            return state, future, still_pending

        state, future, still_pending = run(scenario())

        assert state.phone_ws.closed is False
        assert state.phone_ws is not None
        assert not future.done(), "a live session's in-flight request must not be failed"
        assert list(still_pending) == ["rid-live"]
        future.cancel()

    def test_a_session_of_unknown_provenance_is_closed(self, relay_mod):
        """Fail closed. If the code a session proved was never recorded, dropping
        it is the safe answer: the alternative is leaving a possibly-stale session
        with full device control."""
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="NEWCODE")
            state.phone_ws = FakePhoneSocket()  # published without a recorded code
            await relay_mod._drop_phone_after_rotation(state)
            return state

        assert run(scenario()).phone_ws is None

    def test_nothing_connected_is_a_noop(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="NEWCODE")
            await relay_mod._drop_phone_after_rotation(state)
            return state

        state = run(scenario())

        assert state.phone_ws is None
        assert state.phone_ws_code is None

    def test_set_pairing_code_schedules_the_drop(self, relay_mod, monkeypatch):
        dropped = []

        async def fake_drop(state):
            dropped.append(state)

        monkeypatch.setattr(relay_mod, "_drop_phone_after_rotation", fake_drop)
        state = relay_mod._RelayState(pairing_code="OLDCODE", port=1)
        state.phone_ws = FakePhoneSocket()

        class RunningLoop:
            def is_running(self):
                return True

        state.loop = RunningLoop()
        monkeypatch.setattr(relay_mod, "_relay_instance", state)
        scheduled = []
        monkeypatch.setattr(
            relay_mod.asyncio,
            "run_coroutine_threadsafe",
            lambda coro, loop: scheduled.append(coro),
        )

        relay_mod.set_pairing_code("NEWCODE")

        assert state.pairing_code == "NEWCODE"
        assert len(scheduled) == 1
        run(scheduled[0])
        assert dropped == [state]

    def test_an_unchanged_code_still_drops_nothing(self, relay_mod, monkeypatch):
        """Re-running setup with the same code must not touch a healthy session —
        the early return has to survive every restructuring of the drop."""
        state = relay_mod._RelayState(pairing_code="SAMECODE", port=1)
        state.phone_ws = FakePhoneSocket()
        state.phone_ws_code = "SAMECODE"

        class RunningLoop:
            def is_running(self):
                return True

        state.loop = RunningLoop()
        monkeypatch.setattr(relay_mod, "_relay_instance", state)
        monkeypatch.setattr(
            relay_mod.asyncio,
            "run_coroutine_threadsafe",
            lambda coro, loop: pytest.fail("nothing must be scheduled for an unchanged code"),
        )

        relay_mod.set_pairing_code("SAMECODE")

        assert state.phone_ws.closed is False


class TestRelayUrlScheme:
    """RELAY-2: get_relay_url() hardcoded http:// even when _ssl_context() is
    active, so any caller that trusted it attempted plaintext against a TLS
    listener. The scheme now follows the same configuration the relay serves
    with, and android_setup uses the same predicate to print an address the app
    can actually dial (TRANS-1)."""

    @pytest.fixture(autouse=True)
    def _no_tls(self, monkeypatch):
        monkeypatch.delenv("ANDROID_RELAY_CERT", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_KEY", raising=False)

    def test_plaintext_without_a_certificate(self, relay_mod, monkeypatch):
        monkeypatch.setattr(relay_mod, "_relay_instance", None)
        monkeypatch.setattr(relay_mod, "_relay_port", lambda: 19555)

        assert relay_mod.is_tls_configured() is False
        assert relay_mod.get_relay_url() == "http://localhost:19555"

    def test_tls_scheme_when_both_paths_are_configured(self, relay_mod, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/cert.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")
        state = relay_mod._RelayState(pairing_code="PAIRCODE", port=19556)
        monkeypatch.setattr(relay_mod, "_relay_instance", state)

        assert relay_mod.is_tls_configured() is True
        assert relay_mod.get_relay_url() == "https://localhost:19556"

    @pytest.mark.parametrize("present", ["cert", "key"], ids=["cert_only", "key_only"])
    def test_half_a_configuration_stays_plaintext(self, relay_mod, monkeypatch, present):
        """Matches _ssl_context(): one path is not TLS, so the URL must not claim
        https:// against a listener that is serving plaintext."""
        monkeypatch.setenv(
            "ANDROID_RELAY_CERT" if present == "cert" else "ANDROID_RELAY_KEY", "/tmp/x.pem"
        )
        monkeypatch.setattr(relay_mod, "_relay_instance", None)
        monkeypatch.setattr(relay_mod, "_relay_port", lambda: 19557)

        assert relay_mod.is_tls_configured() is False
        assert relay_mod.get_relay_url() == "http://localhost:19557"

    def test_the_url_scheme_agrees_with_the_context_that_is_served(
        self, relay_mod, monkeypatch
    ):
        """The invariant RELAY-2 named: whatever _ssl_context() decides, the URL
        reports the same transport."""
        monkeypatch.setattr(relay_mod, "_relay_instance", None)
        monkeypatch.setattr(relay_mod, "_relay_port", lambda: 19558)
        monkeypatch.setattr(
            ssl.SSLContext,
            "load_cert_chain",
            lambda self, certfile, keyfile: None,
        )

        monkeypatch.delenv("ANDROID_RELAY_CERT", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_KEY", raising=False)
        assert (relay_mod._ssl_context() is None) == (
            not relay_mod.get_relay_url().startswith("https://")
        )

        monkeypatch.setenv("ANDROID_RELAY_CERT", "/certs/cert.pem")
        monkeypatch.setenv("ANDROID_RELAY_KEY", "/certs/key.pem")
        assert (relay_mod._ssl_context() is not None) == (
            relay_mod.get_relay_url().startswith("https://")
        )


class TestServe:
    """_serve builds the app, starts the site and blocks until shutdown. The site
    is faked so no port is bound."""

    @pytest.fixture
    def serve(self, relay_mod, monkeypatch):
        site_class, created = fake_site_factory()
        monkeypatch.setattr(relay_mod, "web", WebShim(aiohttp.web, TCPSite=site_class))
        monkeypatch.delenv("ANDROID_RELAY_CERT", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_KEY", raising=False)
        self.created = created
        return relay_mod, created

    async def _run_serve(self, relay_mod, ready=None):
        state = make_relay_state(relay_mod, pairing_code="PAIRCODE", port=19555)
        ready = ready if ready is not None else threading.Event()
        task = asyncio.ensure_future(relay_mod._serve(state, ready))
        await asyncio.wait_for(asyncio.to_thread(ready.wait, 5), 6)
        return state, ready, task

    def test_every_allowlisted_route_is_registered_with_its_methods(self, serve, caplog):
        relay_mod, created = serve

        async def scenario():
            state, ready, task = await self._run_serve(relay_mod)
            registered = {}
            for route in state.app.router.routes():
                registered.setdefault(route.resource.canonical, set()).add(route.method)
            state.shutdown_event.set()
            await asyncio.wait_for(task, 5)
            return registered

        registered = run(scenario())

        for path, method in relay_mod._ROUTES.items():
            assert path in registered, f"{path} was not registered on the app"
            expected = {"GET", "POST"} if method == "BOTH" else {method}
            assert registered[path] >= expected, f"{path}: {registered[path]} != {expected}"
        assert set(registered) == set(relay_mod._ROUTES) | {"/ws"}

    def test_the_websocket_endpoint_is_registered(self, serve):
        relay_mod, _ = serve

        async def scenario():
            state, ready, task = await self._run_serve(relay_mod)
            paths = {route.resource.canonical for route in state.app.router.routes()}
            state.shutdown_event.set()
            await asyncio.wait_for(task, 5)
            return paths

        assert "/ws" in run(scenario())

    def test_the_site_is_bound_to_the_configured_host_and_port(self, serve, monkeypatch):
        relay_mod, created = serve
        monkeypatch.setenv("ANDROID_RELAY_HOST", "127.0.0.1")

        async def scenario():
            state, ready, task = await self._run_serve(relay_mod)
            state.shutdown_event.set()
            await asyncio.wait_for(task, 5)

        run(scenario())

        assert len(created) == 1
        assert created[0].host == "127.0.0.1"
        assert created[0].port == 19555
        assert created[0].started is True

    def test_the_host_defaults_to_localhost(self, serve, monkeypatch):
        relay_mod, created = serve
        monkeypatch.delenv("ANDROID_RELAY_HOST", raising=False)

        async def scenario():
            state, ready, task = await self._run_serve(relay_mod)
            state.shutdown_event.set()
            await asyncio.wait_for(task, 5)

        run(scenario())

        assert created[0].host == "localhost"

    def test_the_tls_context_is_handed_to_the_site(self, serve, monkeypatch):
        relay_mod, created = serve
        sentinel = object()
        monkeypatch.setattr(relay_mod, "_ssl_context", lambda: sentinel)

        async def scenario():
            state, ready, task = await self._run_serve(relay_mod)
            state.shutdown_event.set()
            await asyncio.wait_for(task, 5)

        run(scenario())

        assert created[0].kwargs["ssl_context"] is sentinel

    def test_no_tls_context_when_plaintext(self, serve):
        relay_mod, created = serve

        async def scenario():
            state, ready, task = await self._run_serve(relay_mod)
            state.shutdown_event.set()
            await asyncio.wait_for(task, 5)

        run(scenario())

        assert created[0].kwargs["ssl_context"] is None

    def test_the_listening_line_names_the_scheme(self, serve, caplog):
        relay_mod, _ = serve

        async def scenario():
            with caplog.at_level(logging.INFO, logger=LOGGER):
                state, ready, task = await self._run_serve(relay_mod)
                state.shutdown_event.set()
                await asyncio.wait_for(task, 5)

        run(scenario())

        assert "Relay listening on http://localhost:19555" in caplog.text

    def test_binding_off_localhost_without_tls_warns(self, serve, monkeypatch, caplog):
        """The pairing token travels over this link; an internet-facing plaintext
        relay is the one misconfiguration worth shouting about at startup."""
        relay_mod, _ = serve
        monkeypatch.setenv("ANDROID_RELAY_HOST", "0.0.0.0")

        async def scenario():
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                state, ready, task = await self._run_serve(relay_mod)
                state.shutdown_event.set()
                await asyncio.wait_for(task, 5)

        run(scenario())

        assert "TLS is NOT configured" in caplog.text

    def test_localhost_without_tls_does_not_warn(self, serve, caplog):
        relay_mod, _ = serve

        async def scenario():
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                state, ready, task = await self._run_serve(relay_mod)
                state.shutdown_event.set()
                await asyncio.wait_for(task, 5)

        run(scenario())

        assert "TLS is NOT configured" not in caplog.text

    def test_ready_is_set_before_it_blocks(self, serve):
        """start_relay waits on this event, so setting it after the shutdown wait
        would hang every caller for ten seconds."""
        relay_mod, _ = serve
        ready = threading.Event()

        async def scenario():
            state, _, task = await self._run_serve(relay_mod, ready=ready)
            assert ready.is_set()
            assert not task.done(), "_serve returned before shutdown was signalled"
            state.shutdown_event.set()
            await asyncio.wait_for(task, 5)

        run(scenario())

    def test_every_route_handler_forwards_its_own_path(self, serve):
        """The routes are registered in a loop, so a handler that closed over the
        loop variable instead of taking it as a default argument would send every
        request to whichever path was bound last — and the phone would answer the
        wrong command with a straight face.

        Calling the registered handlers directly is the only way to check all 40
        of them without a listening socket.
        """
        relay_mod, _ = serve
        forwarded = []

        async def scenario():
            state, ready, task = await self._run_serve(relay_mod)

            class AnsweringSocket(FakePhoneSocket):
                async def send_json(self, command):
                    forwarded.append((command["method"], command["path"]))
                    if command["path"] == "/mic_file":
                        async with state.streams_lock:
                            queue = state.streams.get(command["request_id"])
                        if queue is not None:
                            await queue.put(("error", "test finished"))
                        return
                    await super().send_json(command)
                    async with state.pending_lock:
                        future = state.pending.pop(command["request_id"], None)
                    if future is not None and not future.done():
                        future.set_result(
                            {
                                "request_id": command["request_id"],
                                "status": 200,
                                "result": {"ok": True},
                            }
                        )

            state.phone_ws = AnsweringSocket()
            for route in state.app.router.routes():
                if route.method not in ("GET", "POST"):
                    continue  # add_get also registers HEAD
                if route.resource.canonical == "/ws":
                    continue  # the handshake needs a real request; tested above
                request = FakeRequest(method=route.method, headers=AUTH, json_body={})
                response = await route.handler(request)
                assert response.status in (200, 502), (
                    f"{route.method} {route.resource.canonical} -> {response.status}"
                )
            state.shutdown_event.set()
            await asyncio.wait_for(task, 5)

        run(scenario())

        expected = {
            (method, path)
            for path, allowed in relay_mod._ROUTES.items()
            for method in ({"GET", "POST"} if allowed == "BOTH" else {allowed})
        }
        assert set(forwarded) == expected, (
            f"handlers forwarded the wrong path: missing "
            f"{sorted(expected - set(forwarded))}, unexpected "
            f"{sorted(set(forwarded) - expected)}"
        )
        assert len(forwarded) == len(expected), "a handler was called twice"

    def test_the_ws_route_handler_goes_through_ws_auth(self, serve):
        """The /ws closure must reach _handle_ws, where the pairing check lives;
        a handler that answered the handshake itself would be an open relay."""
        relay_mod, _ = serve

        async def scenario():
            state, ready, task = await self._run_serve(relay_mod)
            route = next(
                r for r in state.app.router.routes() if r.resource.canonical == "/ws"
            )
            with pytest.raises(aiohttp.web.HTTPForbidden):
                await route.handler(FakeRequest(headers={"Authorization": "Bearer WRONG"}))
            state.shutdown_event.set()
            await asyncio.wait_for(task, 5)

        run(scenario())

    def test_shutdown_disconnects_the_phone_and_cleans_up(self, serve, caplog):
        relay_mod, _ = serve

        async def scenario():
            state, ready, task = await self._run_serve(relay_mod)
            socket = FakePhoneSocket()
            state.phone_ws = socket
            future = state.loop.create_future()
            async with state.pending_lock:
                state.pending["rid"] = future
            with caplog.at_level(logging.INFO, logger=LOGGER):
                state.shutdown_event.set()
                await asyncio.wait_for(task, 5)
            return socket, future

        socket, future = run(scenario())

        assert socket.closed is True
        assert future.done() and isinstance(future.exception(), ConnectionError)
        assert "Relay server cleaned up" in caplog.text


# ── Rate limiting ─────────────────────────────────────────────────────────────


class TestAuthWindow:
    @pytest.fixture(autouse=True)
    def clean_window(self, relay_mod):
        with relay_mod._auth_lock:
            relay_mod._auth_blocked.clear()
            relay_mod._auth_failures.clear()
        relay_mod._auth_last_cleanup = 0.0
        yield
        with relay_mod._auth_lock:
            relay_mod._auth_blocked.clear()
            relay_mod._auth_failures.clear()
        relay_mod._auth_last_cleanup = 0.0

    def test_failures_below_the_threshold_do_not_block(self, relay_mod):
        for _ in range(relay_mod._AUTH_MAX_ATTEMPTS - 1):
            relay_mod._auth_record_failure("203.0.113.9")

        assert relay_mod._auth_is_blocked("203.0.113.9") is False

    def test_the_threshold_blocks(self, relay_mod, caplog):
        with caplog.at_level(logging.WARNING, logger=LOGGER):
            for _ in range(relay_mod._AUTH_MAX_ATTEMPTS):
                relay_mod._auth_record_failure("203.0.113.9")

        assert relay_mod._auth_is_blocked("203.0.113.9") is True
        assert f"blocked for {relay_mod._AUTH_BLOCK_SECONDS}s" in caplog.text

    def test_blocking_clears_the_failure_window(self, relay_mod):
        """Otherwise the counter would keep growing and re-block forever."""
        for _ in range(relay_mod._AUTH_MAX_ATTEMPTS):
            relay_mod._auth_record_failure("203.0.113.9")

        assert relay_mod._auth_failures.get("203.0.113.9") is None

    def test_other_ips_are_unaffected(self, relay_mod):
        for _ in range(relay_mod._AUTH_MAX_ATTEMPTS):
            relay_mod._auth_record_failure("203.0.113.9")

        assert relay_mod._auth_is_blocked("203.0.113.10") is False

    def test_an_expired_block_is_lifted_on_the_next_check(self, relay_mod):
        relay_mod._auth_blocked["203.0.113.9"] = time.monotonic() - 1

        assert relay_mod._auth_is_blocked("203.0.113.9") is False
        assert "203.0.113.9" not in relay_mod._auth_blocked

    def test_an_expired_block_is_lifted_even_when_the_sweep_is_rate_limited(self, relay_mod):
        """The sweep only runs every _AUTH_CLEANUP_INTERVAL, so the check has to
        notice an expired block itself — otherwise a phone stays locked out for
        up to two minutes after its block ends."""
        relay_mod._auth_last_cleanup = time.monotonic()
        relay_mod._auth_blocked["203.0.113.9"] = time.monotonic() - 1

        assert relay_mod._auth_is_blocked("203.0.113.9") is False
        assert relay_mod._auth_blocked == {}

    def test_a_live_block_is_reported(self, relay_mod):
        relay_mod._auth_blocked["203.0.113.9"] = time.monotonic() + 300

        assert relay_mod._auth_is_blocked("203.0.113.9") is True

    def test_a_failure_older_than_the_window_does_not_count(self, relay_mod):
        """The window slides: four attempts an hour ago must not put the fifth
        one over the line."""
        old = time.monotonic() - relay_mod._AUTH_WINDOW_SECONDS - 1
        relay_mod._auth_failures["203.0.113.9"] = [old] * (relay_mod._AUTH_MAX_ATTEMPTS - 1)

        relay_mod._auth_record_failure("203.0.113.9")

        assert relay_mod._auth_is_blocked("203.0.113.9") is False
        assert len(relay_mod._auth_failures["203.0.113.9"]) == 1

    def test_the_sweep_drops_expired_blocks_and_stale_windows(self, relay_mod):
        now = time.monotonic()
        relay_mod._auth_last_cleanup = now - relay_mod._AUTH_CLEANUP_INTERVAL - 1
        relay_mod._auth_blocked["expired"] = now - 10
        relay_mod._auth_blocked["live"] = now + 100
        relay_mod._auth_failures["stale"] = [now - relay_mod._AUTH_WINDOW_SECONDS - 10]
        relay_mod._auth_failures["fresh"] = [now]

        relay_mod._auth_cleanup()

        assert relay_mod._auth_blocked == {"live": now + 100}
        assert relay_mod._auth_failures == {"fresh": [now]}

    def test_the_sweep_is_rate_limited(self, relay_mod):
        """It runs on every auth check, so it must not rebuild both dicts each
        time."""
        relay_mod._auth_last_cleanup = time.monotonic()
        relay_mod._auth_blocked["expired"] = time.monotonic() - 10

        relay_mod._auth_cleanup()

        assert "expired" in relay_mod._auth_blocked

    def test_the_sweep_runs_on_the_first_check_of_a_process(self, relay_mod):
        relay_mod._auth_last_cleanup = 0.0
        relay_mod._auth_blocked["expired"] = time.monotonic() - 10

        relay_mod._auth_is_blocked("203.0.113.9")

        assert relay_mod._auth_blocked == {}


# ── WebSocket handler (phone side) ────────────────────────────────────────────


class FakeWebSocket:
    """Stands in for aiohttp's WebSocketResponse on the phone side."""

    def __init__(self, messages=(), heartbeat=None):
        self.messages = list(messages)
        self.heartbeat = heartbeat
        self.prepared = False
        self.closed = False
        self.close_calls = []
        self._exception = RuntimeError("websocket blew up")

    async def prepare(self, request):
        self.prepared = True

    def exception(self):
        return self._exception

    async def close(self, code=None, message=None):
        self.closed = True
        self.close_calls.append((code, message))

    def __aiter__(self):
        async def iterate():
            for message in self.messages:
                yield message

        return iterate()


class RecordingQueue:
    """asyncio.Queue stand-in that keeps what was put into it.

    The relay drains and re-fills a stream queue when a session ends, so a test
    that wants to see what the phone sent has to look at a record rather than at
    the live queue.
    """

    def __init__(self):
        self.items = []

    async def put(self, item):
        self.items.append(item)

    def put_nowait(self, item):
        self.items.append(item)

    def empty(self):
        return True

    def get_nowait(self):
        raise asyncio.QueueEmpty


class RacingQueue:
    """A stream queue that someone else is draining at the same time.

    ``empty()`` saying False and ``get_nowait()`` then raising QueueEmpty is the
    race the try/except around the drain loops exists for; this reproduces it
    without needing two threads to actually interleave.
    """

    def __init__(self, first_item=None):
        self.puts = []
        self._first = first_item

    def empty(self):
        return False

    def get_nowait(self):
        raise asyncio.QueueEmpty

    def put_nowait(self, item):
        self.puts.append(item)

    async def get(self):
        if self._first is None:
            raise asyncio.QueueEmpty
        item, self._first = self._first, None
        return item


def ws_message(kind, data=None):
    """A received WebSocket message, as the handler's `async for` yields it."""
    return types.SimpleNamespace(type=kind, data=data)


class TestHandleWsAuth:
    def test_a_blocked_ip_is_refused_before_the_token_is_checked(self, relay_mod, caplog):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            relay_mod._auth_blocked["203.0.113.9"] = time.monotonic() + 300
            request = FakeRequest(headers=AUTH, remote="203.0.113.9")
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                with pytest.raises(aiohttp.web.HTTPTooManyRequests):
                    await relay_mod._handle_ws(request, state)

        run(scenario())

        assert "blocked IP 203.0.113.9" in caplog.text

    def test_a_bad_token_is_refused(self, relay_mod, caplog):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            request = FakeRequest(headers={"Authorization": "Bearer WRONGCODE"})
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                with pytest.raises(aiohttp.web.HTTPForbidden):
                    await relay_mod._handle_ws(request, state)
            return state

        state = run(scenario())

        assert state.phone_ws is None
        assert "WRONGCODE" not in caplog.text
        assert "****" in caplog.text
        assert len(relay_mod._auth_failures.get("203.0.113.9", [])) == 1

    def test_a_query_string_token_is_not_a_credential(self, relay_mod):
        """?token= was removed because reverse proxies log query strings
        verbatim; the handler only ever reads the Authorization header."""
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            request = FakeRequest(headers={})
            with pytest.raises(aiohttp.web.HTTPForbidden):
                await relay_mod._handle_ws(request, state)

        run(scenario())

    def test_a_non_bearer_scheme_is_refused(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            request = FakeRequest(headers={"Authorization": "Basic UEFJUkNPREU="})
            with pytest.raises(aiohttp.web.HTTPForbidden):
                await relay_mod._handle_ws(request, state)

        run(scenario())

    def test_the_token_must_match_exactly(self, relay_mod):
        """PairingManager issues uppercase codes; case-folding would halve the
        key space."""
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            request = FakeRequest(headers={"Authorization": "Bearer paircode"})
            with pytest.raises(aiohttp.web.HTTPForbidden):
                await relay_mod._handle_ws(request, state)

        run(scenario())

    def test_a_missing_remote_address_is_still_rate_limited(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            request = FakeRequest(headers={}, remote=None)
            with pytest.raises(aiohttp.web.HTTPForbidden):
                await relay_mod._handle_ws(request, state)

        run(scenario())

        assert len(relay_mod._auth_failures["unknown"]) == 1


class TestHandleWsSession:
    @pytest.fixture
    def websocket(self, relay_mod, monkeypatch):
        """Install the fake WebSocketResponse class in the copy under test."""
        created = []

        def factory(**kwargs):
            socket = FakeWebSocket(messages=self.messages, **kwargs)
            created.append(socket)
            return socket

        self.messages = []
        monkeypatch.setattr(relay_mod, "web", WebShim(aiohttp.web, WebSocketResponse=factory))
        return created

    def test_an_accepted_phone_gets_a_prepared_heartbeat_socket(self, relay_mod, websocket, caplog):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            request = FakeRequest(headers=AUTH)
            with caplog.at_level(logging.INFO, logger=LOGGER):
                returned = await relay_mod._handle_ws(request, state)
            return state, returned

        state, returned = run(scenario())

        assert returned is websocket[0]
        assert returned.prepared is True
        assert returned.heartbeat == 15.0, "no heartbeat means a half-open socket looks connected"
        assert "Phone connected from 203.0.113.9" in caplog.text
        # the handler's finally block already ran by the time it returns
        assert state.phone_ws is None

    def test_a_ws_error_ends_the_session(self, relay_mod, websocket, caplog):
        self.messages = [ws_message(aiohttp.WSMsgType.ERROR)]

        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            with caplog.at_level(logging.ERROR, logger=LOGGER):
                await relay_mod._handle_ws(FakeRequest(headers=AUTH), state)
            return state

        state = run(scenario())

        assert "Phone WS error" in caplog.text
        assert "websocket blew up" in caplog.text
        assert state.phone_ws is None

    def test_a_text_message_is_routed_to_the_pending_request(self, relay_mod, websocket):
        payload = {"request_id": "rid-1", "status": 200, "result": {"ok": True}}
        self.messages = [ws_message(aiohttp.WSMsgType.TEXT, json.dumps(payload))]

        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            future = state.loop.create_future()
            async with state.pending_lock:
                state.pending["rid-1"] = future
            await relay_mod._handle_ws(FakeRequest(headers=AUTH), state)
            return future

        assert run(scenario()).result() == payload

    def test_a_binary_message_is_routed_to_the_stream_queue(self, relay_mod, websocket):
        self.messages = [ws_message(aiohttp.WSMsgType.BINARY, stream_frame("rid-1", b"audio"))]

        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            queue = RecordingQueue()
            async with state.streams_lock:
                state.streams["rid-1"] = queue
            await relay_mod._handle_ws(FakeRequest(headers=AUTH), state)
            return queue.items

        # The session ends as soon as the message list is exhausted, and teardown
        # appends an error item — the chunk has to be first.
        assert run(scenario())[0] == ("chunk", b"audio")

    def test_an_unknown_message_type_is_ignored(self, relay_mod, websocket):
        self.messages = [ws_message(aiohttp.WSMsgType.PING), ws_message(aiohttp.WSMsgType.CLOSE)]

        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            await relay_mod._handle_ws(FakeRequest(headers=AUTH), state)
            return state

        assert run(scenario()).phone_ws is None

    def test_a_second_phone_replaces_the_first(self, relay_mod, websocket, caplog):
        """One phone at a time: the old socket is closed and its in-flight
        requests fail rather than hanging until they time out."""
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            old_socket = FakePhoneSocket()
            state.phone_ws = old_socket
            future = state.loop.create_future()
            async with state.pending_lock:
                state.pending["rid-old"] = future
            with caplog.at_level(logging.INFO, logger=LOGGER):
                await relay_mod._handle_ws(FakeRequest(headers=AUTH), state)
            return state, old_socket, future

        state, old_socket, future = run(scenario())

        assert "Replacing previous phone connection" in caplog.text
        assert old_socket.closed is True
        assert old_socket.close_calls[0][0] == aiohttp.WSCloseCode.GOING_AWAY
        assert isinstance(future.exception(), ConnectionError)
        assert state.phone_ws is not old_socket

    def test_disconnecting_clears_the_connected_phone(self, relay_mod, websocket):
        """The handler's finally block runs on every exit path, so a dropped
        phone must not leave is_phone_connected() lying."""
        self.messages = []

        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            await relay_mod._handle_ws(FakeRequest(headers=AUTH), state)
            return state

        assert run(scenario()).phone_ws is None


class TestCleanupAndInflight:
    def test_pending_requests_fail_with_the_reason(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod, socket=FakePhoneSocket())
            futures = [state.loop.create_future() for _ in range(3)]
            async with state.pending_lock:
                for index, future in enumerate(futures):
                    state.pending[f"rid-{index}"] = future
            await relay_mod._fail_inflight(state, "phone walked away")
            return state, futures

        state, futures = run(scenario())

        assert state.pending == {}
        for future in futures:
            assert isinstance(future.exception(), ConnectionError)
            assert "phone walked away" in str(future.exception())

    def test_an_already_finished_future_is_left_alone(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod)
            future = state.loop.create_future()
            future.set_result("answered")
            async with state.pending_lock:
                state.pending["rid"] = future
            await relay_mod._fail_inflight(state, "too late")
            return future

        assert run(scenario()).result() == "answered"

    def test_active_streams_are_told_the_phone_is_gone(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod)
            queue = asyncio.Queue(maxsize=8)
            queue.put_nowait(("chunk", b"partial"))
            async with state.streams_lock:
                state.streams["rid"] = queue
            await relay_mod._fail_inflight(state, "phone disconnected")
            return state, queue

        state, queue = run(scenario())

        assert state.streams == {}
        # the partial chunk is drained so the consumer sees the error first
        assert queue.get_nowait() == ("error", "Phone disconnected (phone disconnected)")
        assert queue.empty()

    def test_a_queue_being_drained_elsewhere_does_not_break_the_cleanup(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod)
            queue = RacingQueue()
            async with state.streams_lock:
                state.streams["rid"] = queue
            await relay_mod._fail_inflight(state, "phone disconnected")
            return state, queue

        state, queue = run(scenario())

        assert state.streams == {}
        assert queue.puts == [("error", "Phone disconnected (phone disconnected)")]

    def test_nothing_to_do_is_not_an_error(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod)
            await relay_mod._fail_inflight(state, "nothing pending")

        run(scenario())

    def test_cleanup_closes_the_socket_and_fails_requests(self, relay_mod):
        async def scenario():
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, socket=socket)
            future = state.loop.create_future()
            async with state.pending_lock:
                state.pending["rid"] = future
            await relay_mod._cleanup_phone(state, reason="gone")
            return state, socket, future

        state, socket, future = run(scenario())

        assert state.phone_ws is None
        assert socket.closed is True
        assert isinstance(future.exception(), ConnectionError)

    def test_cleanup_does_not_touch_an_already_closed_socket(self, relay_mod):
        async def scenario():
            socket = FakePhoneSocket(closed=True)
            state = make_relay_state(relay_mod, socket=socket)
            await relay_mod._cleanup_phone(state, reason="gone")
            return socket

        assert run(scenario()).close_calls == []

    def test_cleanup_with_no_phone_still_fails_requests(self, relay_mod):
        """The relay can shut down between a command being queued and the phone
        ever having connected."""
        async def scenario():
            state = make_relay_state(relay_mod, socket=None)
            future = state.loop.create_future()
            async with state.pending_lock:
                state.pending["rid"] = future
            await relay_mod._cleanup_phone(state, reason="relay shutdown")
            return future

        assert isinstance(run(scenario()).exception(), ConnectionError)

    def test_a_replaced_socket_cannot_clean_up_its_replacement(self, relay_mod):
        """The old handler's finally block runs after the new phone has already
        taken over; tearing that down would drop a live session."""
        async def scenario():
            state = make_relay_state(relay_mod)
            new_socket = FakePhoneSocket()
            state.phone_ws = new_socket
            future = state.loop.create_future()
            async with state.pending_lock:
                state.pending["rid-new"] = future
            await relay_mod._cleanup_phone(
                state, reason="old socket went away", expected_ws=object()
            )
            return state, new_socket, future

        state, new_socket, future = run(scenario())

        assert state.phone_ws is new_socket
        assert new_socket.closed is False
        assert not future.done(), "the replacement's in-flight request was failed"


# ── Phone message framing ─────────────────────────────────────────────────────


class TestOnPhoneMessage:
    def test_a_non_json_message_is_dropped_with_a_warning(self, relay_mod, caplog):
        async def scenario():
            state = make_relay_state(relay_mod)
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                await relay_mod._on_phone_message(state, "this is not json")
            return state

        state = run(scenario())

        assert "Non-JSON message from phone" in caplog.text
        assert state.pending == {}

    def test_a_long_non_json_message_is_truncated_in_the_log(self, relay_mod, caplog):
        async def scenario():
            state = make_relay_state(relay_mod)
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                await relay_mod._on_phone_message(state, "x" * 5000)

        run(scenario())

        logged = [line for line in caplog.text.splitlines() if "Non-JSON" in line]
        assert logged and logged[0].count("x") <= 200

    def test_a_message_without_a_request_id_is_dropped(self, relay_mod, caplog):
        async def scenario():
            state = make_relay_state(relay_mod)
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                await relay_mod._on_phone_message(state, json.dumps({"result": {"ok": True}}))

        run(scenario())

        assert "missing request_id" in caplog.text

    def test_an_empty_request_id_is_treated_as_missing(self, relay_mod, caplog):
        async def scenario():
            state = make_relay_state(relay_mod)
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                await relay_mod._on_phone_message(state, json.dumps({"request_id": "", "result": 1}))

        run(scenario())

        assert "missing request_id" in caplog.text

    def test_a_reply_nobody_is_waiting_for_is_ignored(self, relay_mod, caplog):
        """The caller may have timed out a second ago; a late reply must not
        raise inside the WebSocket handler and kill the session."""
        async def scenario():
            state = make_relay_state(relay_mod)
            with caplog.at_level(logging.DEBUG, logger=LOGGER):
                await relay_mod._on_phone_message(
                    state, json.dumps({"request_id": "rid-late", "result": 1})
                )

        run(scenario())

        assert "No pending future" in caplog.text

    def test_a_reply_for_an_already_finished_request_is_ignored(self, relay_mod):
        """A future that was cancelled or resolved a moment ago must not be
        re-set: asyncio raises InvalidStateError, which would kill the WebSocket
        handler and drop the phone."""
        async def scenario():
            state = make_relay_state(relay_mod)
            future = state.loop.create_future()
            future.set_result("already answered")
            async with state.pending_lock:
                state.pending["rid-1"] = future
            await relay_mod._on_phone_message(
                state, json.dumps({"request_id": "rid-1", "result": {"late": True}})
            )
            return future

        assert run(scenario()).result() == "already answered"

    def test_the_reply_resolves_the_waiting_request(self, relay_mod):
        payload = {"request_id": "rid-1", "status": 200, "result": {"ok": True}}

        async def scenario():
            state = make_relay_state(relay_mod)
            future = state.loop.create_future()
            async with state.pending_lock:
                state.pending["rid-1"] = future
            await relay_mod._on_phone_message(state, json.dumps(payload))
            return state, future

        state, future = run(scenario())

        assert future.result() == payload
        assert state.pending == {}, "the pending entry was not removed"

    def test_a_reply_for_an_active_stream_goes_to_the_stream_queue(self, relay_mod):
        """The same request_id can have a stream registered; routing it to a
        future instead would stall the download."""
        payload = {"request_id": "rid-1", "stream": {"event": "end", "bytes": 4}}

        async def scenario():
            state = make_relay_state(relay_mod)
            queue = asyncio.Queue()
            future = state.loop.create_future()
            async with state.streams_lock:
                state.streams["rid-1"] = queue
            async with state.pending_lock:
                state.pending["rid-1"] = future
            await relay_mod._on_phone_message(state, json.dumps(payload))
            return queue, future

        queue, future = run(scenario())

        assert queue.get_nowait() == ("message", payload)
        assert not future.done()


class TestStreamFrameDecoding:
    def test_a_well_formed_frame_decodes(self, relay_mod):
        assert relay_mod._decode_stream_frame(stream_frame("rid-1", b"audio")) == (
            "rid-1",
            b"audio",
        )

    def test_a_frame_with_no_payload_decodes(self, relay_mod):
        assert relay_mod._decode_stream_frame(stream_frame("rid-1", b"")) == ("rid-1", b"")

    def test_the_maximum_id_length_is_accepted(self, relay_mod):
        request_id = "r" * 128
        assert relay_mod._decode_stream_frame(stream_frame(request_id, b"x")) == (
            request_id,
            b"x",
        )

    def test_a_utf8_id_survives(self, relay_mod):
        assert relay_mod._decode_stream_frame(stream_frame("rid-é", b"x"))[0] == "rid-é"

    @pytest.mark.parametrize(
        "raw,reason",
        [
            (b"", "empty"),
            (b"\x00", "one byte"),
            (b"\x00\x01", "header only"),
            (b"\x00\x00x", "zero-length id"),
            (b"\x00\x81" + b"r" * 129 + b"x", "id longer than 128"),
            (b"\x00\x05abc", "truncated id"),
            (b"\x00\x02\xff\xfe" + b"x", "id is not utf-8"),
        ],
        ids=[
            "empty",
            "one_byte",
            "header_only",
            "zero_length_id",
            "id_too_long",
            "truncated_id",
            "invalid_utf8",
        ],
    )
    def test_malformed_frames_raise(self, relay_mod, raw, reason):
        with pytest.raises(ValueError):
            relay_mod._decode_stream_frame(raw)


class TestOnPhoneBinary:
    @pytest.mark.parametrize(
        "raw",
        [b"", b"\x00\x00x", b"\x00\x05abc", b"\x00\x02\xff\xfex", b"\x00\x81" + b"r" * 130],
        ids=["empty", "zero_length_id", "truncated", "invalid_utf8", "id_too_long"],
    )
    def test_a_malformed_frame_is_dropped_with_a_warning(self, relay_mod, caplog, raw):
        async def scenario():
            state = make_relay_state(relay_mod)
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                await relay_mod._on_phone_binary(state, raw)
            return state

        state = run(scenario())

        assert "Invalid binary stream frame from phone" in caplog.text
        assert state.streams == {}

    def test_a_frame_for_an_unknown_stream_is_dropped(self, relay_mod, caplog):
        async def scenario():
            state = make_relay_state(relay_mod)
            with caplog.at_level(logging.DEBUG, logger=LOGGER):
                await relay_mod._on_phone_binary(state, stream_frame("rid-gone", b"audio"))

        run(scenario())

        assert "No active stream" in caplog.text

    def test_a_valid_chunk_reaches_the_queue(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod)
            queue = asyncio.Queue()
            async with state.streams_lock:
                state.streams["rid-1"] = queue
            await relay_mod._on_phone_binary(state, stream_frame("rid-1", b"audio-bytes"))
            return queue

        assert run(scenario()).get_nowait() == ("chunk", b"audio-bytes")


# ── HTTP handler (tool side) ──────────────────────────────────────────────────


class TestHandleHttpAuth:
    def test_no_phone_connected_is_a_503(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=None)
            return await relay_mod._handle_http(FakeRequest(headers=AUTH), state, "/ping")

        response = run(scenario())

        assert response.status == 503
        assert "No phone connected" in response_json(response)["error"]

    def test_a_closed_phone_socket_is_a_503(self, relay_mod):
        async def scenario():
            state = make_relay_state(
                relay_mod, pairing_code="PAIRCODE", socket=FakePhoneSocket(closed=True)
            )
            return await relay_mod._handle_http(FakeRequest(headers=AUTH), state, "/ping")

        assert run(scenario()).status == 503

    def test_a_bad_token_is_a_401_and_counts_as_a_failure(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=FakePhoneSocket())
            response = await relay_mod._handle_http(
                FakeRequest(headers={"Authorization": "Bearer WRONG"}), state, "/ping"
            )
            return response, state

        response, state = run(scenario())

        assert response.status == 401
        assert response_json(response) == {"error": "Unauthorized"}
        assert state.pending == {}, "an unauthenticated request must not reach the phone"
        assert not state.phone_ws.sent

    def test_a_blocked_ip_is_a_429(self, relay_mod):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=FakePhoneSocket())
            relay_mod._auth_blocked["203.0.113.9"] = time.monotonic() + 300
            return await relay_mod._handle_http(FakeRequest(headers=AUTH), state, "/ping")

        assert run(scenario()).status == 429

    def test_five_bad_tokens_block_the_ip_for_the_http_api_too(self, relay_mod):
        """The limiter is shared with the WebSocket side, so a local process
        guessing tokens can lock the phone out — deliberate, and worth pinning."""
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=FakePhoneSocket())
            request = FakeRequest(headers={"Authorization": "Bearer WRONG"})
            statuses = [
                (await relay_mod._handle_http(request, state, "/ping")).status
                for _ in range(relay_mod._AUTH_MAX_ATTEMPTS + 1)
            ]
            return statuses

        statuses = run(scenario())

        assert statuses == [401] * relay_mod._AUTH_MAX_ATTEMPTS + [429]


class TestHandleHttpForwarding:
    def _reply(self, state, socket, status=200, result=None):
        async def responder():
            while not socket.sent:
                await asyncio.sleep(0)
            command = socket.sent[0]
            async with state.pending_lock:
                future = state.pending.get(command["request_id"])
            if future is not None and not future.done():
                future.set_result(
                    {
                        "request_id": command["request_id"],
                        "status": status,
                        "result": {} if result is None else result,
                    }
                )

        return responder()

    def test_the_command_envelope_carries_method_path_params_and_body(self, relay_mod):
        async def scenario():
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)
            request = FakeRequest(
                method="POST",
                headers=AUTH,
                query={"limit": "5"},
                json_body={"x": 100, "y": 200},
            )
            responder = self._reply(state, socket, result={"success": True})
            response, _ = await asyncio.gather(
                relay_mod._handle_http(request, state, "/tap"), responder
            )
            return response, socket.sent[0]

        response, command = run(scenario())

        assert response.status == 200
        assert response_json(response) == {"success": True}
        assert command["method"] == "POST"
        assert command["path"] == "/tap"
        assert command["params"] == {"limit": "5"}
        assert command["body"] == {"x": 100, "y": 200}
        assert uuid.UUID(command["request_id"]), "request_id must be a uuid the phone can echo"

    def test_a_get_has_an_empty_body(self, relay_mod):
        async def scenario():
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)
            responder = self._reply(state, socket, result={"count": 0})
            response, _ = await asyncio.gather(
                relay_mod._handle_http(FakeRequest(headers=AUTH), state, "/screen"), responder
            )
            return response, socket.sent[0]

        response, command = run(scenario())

        assert response.status == 200
        assert command["body"] == {}
        assert command["method"] == "GET"

    def test_a_malformed_post_body_is_forwarded_as_empty(self, relay_mod):
        """The phone gets a command with no arguments rather than the relay
        returning a 400 the tool layer does not understand."""
        async def scenario():
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)
            request = FakeRequest(method="POST", headers=AUTH, json_body=ValueError("bad json"))
            responder = self._reply(state, socket, result={"success": True})
            response, _ = await asyncio.gather(
                relay_mod._handle_http(request, state, "/tap"), responder
            )
            return response, socket.sent[0]

        response, command = run(scenario())

        assert response.status == 200
        assert command["body"] == {}

    def test_the_phone_status_is_passed_through(self, relay_mod):
        """A 403 from the phone has to stay a 403, or the tool layer cannot tell
        'permission denied' from 'relay broke'."""
        async def scenario():
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)
            responder = self._reply(state, socket, status=403, result={"error": "denied"})
            return await asyncio.gather(
                relay_mod._handle_http(FakeRequest(headers=AUTH), state, "/location"), responder
            )

        response, _ = run(scenario())

        assert response.status == 403
        assert response_json(response) == {"error": "denied"}

    def test_a_reply_without_status_or_result_is_an_empty_200(self, relay_mod):
        async def scenario():
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)

            async def responder():
                while not socket.sent:
                    await asyncio.sleep(0)
                command = socket.sent[0]
                async with state.pending_lock:
                    future = state.pending.get(command["request_id"])
                if future is not None and not future.done():
                    future.set_result({"request_id": command["request_id"]})

            return await asyncio.gather(
                relay_mod._handle_http(FakeRequest(headers=AUTH), state, "/ping"), responder()
            )

        response, _ = run(scenario())

        assert response.status == 200
        assert response_json(response) == {}

    def test_the_pending_entry_exists_before_the_command_is_sent(self, relay_mod):
        """The relay registers the response target first so a phone that answers
        immediately cannot beat the registration and drop the reply."""
        async def scenario():
            seen = {}

            class EagerSocket(FakePhoneSocket):
                async def send_json(self, payload):
                    async with state.pending_lock:
                        seen["registered"] = payload["request_id"] in state.pending
                    await super().send_json(payload)
                    async with state.pending_lock:
                        future = state.pending.get(payload["request_id"])
                    if future is not None and not future.done():
                        future.set_result(
                            {"request_id": payload["request_id"], "status": 200, "result": {}}
                        )

            socket = EagerSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)
            response = await relay_mod._handle_http(FakeRequest(headers=AUTH), state, "/ping")
            return response, seen

        response, seen = run(scenario())

        assert seen["registered"] is True
        assert response.status == 200

    def test_a_send_failure_is_a_502_and_leaves_nothing_pending(self, relay_mod, caplog):
        async def scenario():
            socket = FakePhoneSocket(send_error=RuntimeError("socket is gone"))
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)
            with caplog.at_level(logging.ERROR, logger=LOGGER):
                response = await relay_mod._handle_http(
                    FakeRequest(headers=AUTH), state, "/ping"
                )
            return response, state

        response, state = run(scenario())

        assert response.status == 502
        assert "Failed to send command to phone" in response_json(response)["error"]
        assert state.pending == {}, "a failed send left the request pending forever"
        assert "Failed to send command to phone" in caplog.text

    def test_a_send_failure_on_a_stream_request_clears_the_stream_registry(
        self, relay_mod, caplog
    ):
        """The stream path keeps its own registry; a failed send that left the
        entry behind would leak a queue for every retry."""
        async def scenario():
            socket = FakePhoneSocket(send_error=RuntimeError("socket is gone"))
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)
            with caplog.at_level(logging.ERROR, logger=LOGGER):
                response = await relay_mod._handle_http(
                    FakeRequest(headers=AUTH), state, "/mic_file"
                )
            return response, state

        response, state = run(scenario())

        assert response.status == 502
        assert state.streams == {}
        assert state.pending == {}

    def test_a_phone_that_never_answers_is_a_504(self, relay_mod, monkeypatch, caplog):
        monkeypatch.setattr(relay_mod, "_RESPONSE_TIMEOUT", 0.05)

        async def scenario():
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                response = await relay_mod._handle_http(
                    FakeRequest(method="POST", headers=AUTH, json_body={}), state, "/tap"
                )
            return response, state

        response, state = run(scenario())

        assert response.status == 504
        assert "did not respond within" in response_json(response)["error"]
        assert state.pending == {}, "the timed-out request was left pending"
        assert "Phone did not respond within" in caplog.text

    def test_a_phone_that_disconnects_mid_request_is_a_502(self, relay_mod):
        async def scenario():
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)

            async def disconnect():
                while not socket.sent:
                    await asyncio.sleep(0)
                await relay_mod._fail_inflight(state, "phone disconnected")

            response, _ = await asyncio.gather(
                relay_mod._handle_http(FakeRequest(headers=AUTH), state, "/ping"), disconnect()
            )
            return response

        response = run(scenario())

        assert response.status == 502
        assert "Phone disconnected" in response_json(response)["error"]

    def test_a_mic_file_request_is_registered_as_a_stream_not_a_future(self, relay_mod):
        """The binary path has its own registry; putting it in `pending` would
        make the first audio frame resolve an HTTP JSON response."""
        async def scenario():
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)

            async def responder():
                while not socket.sent:
                    await asyncio.sleep(0)
                command = socket.sent[0]
                async with state.streams_lock:
                    queue = state.streams.get(command["request_id"])
                if queue is not None:
                    await queue.put(("error", "test finished"))

            response, _ = await asyncio.gather(
                relay_mod._handle_http(FakeRequest(headers=AUTH), state, "/mic_file"), responder()
            )
            return response, socket.sent[0], state

        response, command, state = run(scenario())

        assert command["path"] == "/mic_file"
        assert state.pending == {}
        assert response.status == 502
        assert response_json(response) == {"error": "test finished"}


class TestRelayBinaryStream:
    """The microphone download path: metadata first, then frames, then a
    checksum. Every refusal here is the relay declining to present a truncated
    recording as a complete one."""

    @pytest.fixture
    def stream(self, relay_mod, monkeypatch):
        created = []

        def factory(**kwargs):
            response = FakeStreamResponse(**kwargs)
            created.append(response)
            return response

        monkeypatch.setattr(relay_mod, "web", WebShim(aiohttp.web, StreamResponse=factory))
        return created

    async def _drive(self, relay_mod, items, request_id="rid-1", timeout=None):
        state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
        queue = asyncio.Queue(maxsize=8)
        for item in items:
            queue.put_nowait(item)
        async with state.streams_lock:
            state.streams[request_id] = queue
        response = await relay_mod._relay_binary_stream(
            FakeRequest(), state, request_id, queue
        )
        return response, state, queue

    def _empty_stream(self, filename="recording.wav", mime="audio/wav"):
        """A complete download of a zero-byte recording: metadata, end event and
        a checksum that all agree, so the handler returns instead of waiting."""
        return [
            self._start(0, filename=filename, mime=mime),
            (
                "message",
                {
                    "stream": {
                        "event": "end",
                        "bytes": 0,
                        "sha256": hashlib.sha256(b"").hexdigest(),
                    }
                },
            ),
        ]

    def _start(self, size, filename="recording.wav", mime="audio/wav"):
        return (
            "message",
            {
                "request_id": "rid-1",
                "status": 200,
                "stream": {
                    "event": "start",
                    "filename": filename,
                    "mimeType": mime,
                    "size": size,
                },
            },
        )

    def test_error_before_metadata_is_a_502(self, relay_mod, stream):
        response, state, _ = run(self._drive(relay_mod, [("error", "phone walked away")]))

        assert response.status == 502
        assert response_json(response) == {"error": "phone walked away"}
        assert state.streams == {}

    def test_audio_before_metadata_is_refused(self, relay_mod, stream):
        response, _, _ = run(self._drive(relay_mod, [("chunk", b"audio")]))

        assert response.status == 502
        assert response_json(response) == {"error": "Phone sent stream data before metadata"}

    def test_a_phone_error_response_is_passed_through_with_its_status(self, relay_mod, stream):
        """No recording on the phone is a 404 from the phone, not a relay fault."""
        items = [("message", {"status": 404, "result": {"error": "no such recording"}})]

        response, _, _ = run(self._drive(relay_mod, items))

        assert response.status == 404
        assert response_json(response) == {"error": "no such recording"}

    def test_a_message_that_is_not_a_dict_is_refused(self, relay_mod, stream):
        response, _, _ = run(self._drive(relay_mod, [("message", "nonsense")]))

        assert response.status == 502
        assert response_json(response) == {"error": "Invalid stream response"}

    def test_a_message_without_a_stream_uses_the_phones_status(self, relay_mod, stream):
        response, _, _ = run(self._drive(relay_mod, [("message", {"status": 500})]))

        assert response.status == 500
        assert response_json(response) == {"error": "Invalid stream response"}

    def test_a_message_with_no_status_at_all_defaults_to_502(self, relay_mod, stream):
        response, _, _ = run(self._drive(relay_mod, [("message", {})]))

        assert response.status == 502
        assert response_json(response) == {"error": "Invalid stream response"}

    def test_an_event_that_is_not_start_is_refused(self, relay_mod, stream):
        """Pinned with its wart: the status comes from the phone's own message, so
        a phone that says 200 and then sends something that is not a stream start
        produces a 200 whose body is an error. The download still cannot be
        mistaken for audio — the tool rejects the bytes as "not a WAV file" — but
        the status is misleading."""
        items = [("message", {"status": 200, "stream": {"event": "end", "bytes": 0}})]

        response, _, _ = run(self._drive(relay_mod, items))

        assert response.status == 200
        assert response_json(response) == {"error": "Invalid stream response"}
        assert stream == [], "a response body was started for a refused download"

    @pytest.mark.parametrize(
        "size",
        [-1, "not-a-size", None, 257 * 1024 * 1024],
        ids=["negative", "not_a_number", "missing", "above_the_cap"],
    )
    def test_an_invalid_declared_size_is_refused_up_front(self, relay_mod, stream, size):
        """Checked before a single byte is written: a bad size means the client
        would otherwise hang on a Content-Length that never arrives."""
        start = self._start(4)
        if size is None:
            del start[1]["stream"]["size"]
        else:
            start[1]["stream"]["size"] = size

        response, _, _ = run(self._drive(relay_mod, [start]))

        if size == "not-a-size":
            assert response.status == 502  # int() raises; the handler catches it
        else:
            assert response.status == 413
            assert response_json(response) == {"error": "Recording size is invalid"}
        assert stream == [], "a response body was started for a refused download"

    def test_a_complete_download_streams_every_byte(self, relay_mod, stream):
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"data" + b"\x01\x02" * 40
        items = [
            self._start(len(payload)),
            ("chunk", payload[:30]),
            ("chunk", payload[30:]),
            (
                "message",
                {
                    "status": 200,
                    "stream": {
                        "event": "end",
                        "bytes": len(payload),
                        "sha256": hashlib.sha256(payload).hexdigest(),
                    },
                },
            ),
        ]

        response, state, _ = run(self._drive(relay_mod, items))

        assert response.status == 200
        assert response.prepared is True
        assert response.body == payload
        assert response.eof is True
        assert response.forced_close is False
        assert state.streams == {}, "the stream was left registered"

    def test_the_response_headers_describe_a_download(self, relay_mod, stream):
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x01" * 8
        items = [
            self._start(len(payload), filename="rec_2026.wav"),
            ("chunk", payload),
            (
                "message",
                {
                    "stream": {
                        "event": "end",
                        "bytes": len(payload),
                        "sha256": hashlib.sha256(payload).hexdigest(),
                    }
                },
            ),
        ]

        run(self._drive(relay_mod, items))

        headers = stream[0].headers
        assert headers["Content-Type"] == "audio/wav"
        assert headers["Content-Length"] == str(len(payload))
        assert headers["Content-Disposition"] == 'attachment; filename="rec_2026.wav"'
        assert headers["Cache-Control"] == "no-store", "a recording must never be cached"

    @pytest.mark.parametrize(
        "filename,expected",
        [
            ("recording.wav", "recording.wav"),
            ("../../etc/passwd.wav", "passwd.wav"),
            ('quote".wav', "quote_.wav"),
            ("spaced name.wav", "spaced_name.wav"),
            ("..%2f..%2fetc.wav", ".._2f.._2fetc.wav"),
            ("recording.mp3", "recording.wav"),
            ("", "recording.wav"),
            ("../../../x", "recording.wav"),
        ],
        ids=[
            "plain",
            "traversal",
            "quote",
            "space",
            "percent_encoded",
            "wrong_extension",
            "empty",
            "no_extension",
        ],
    )
    def test_the_filename_is_sanitised_into_the_header(self, relay_mod, stream, filename, expected):
        """The phone supplies this string and it lands in a header the browser
        turns into a local filename."""
        items = self._empty_stream(filename=filename)

        run(self._drive(relay_mod, items))

        assert stream, "no response was prepared"
        header = stream[0].headers["Content-Disposition"]
        assert header == f'attachment; filename="{expected}"'
        # The header is what a browser turns into a local filename, so no
        # separator may survive whatever the phone sent.
        assert "/" not in expected and "\\" not in expected

    def test_a_non_wav_mime_type_is_downgraded(self, relay_mod, stream):
        run(self._drive(relay_mod, self._empty_stream(mime="text/html")))

        assert stream[0].headers["Content-Type"] == "application/octet-stream"

    def test_more_bytes_than_declared_tears_the_response_down(self, relay_mod, stream):
        items = [
            self._start(4),
            ("chunk", b"RIFF"),
            ("chunk", b"extra-bytes-past-the-declared-size"),
        ]

        response, _, _ = run(self._drive(relay_mod, items))

        assert response.status == 200
        assert response.forced_close is True
        assert response.eof is False, "a truncated stream must never be presented as complete"

    def test_a_phone_stream_error_tears_the_response_down(self, relay_mod, stream, caplog):
        items = [
            self._start(8),
            ("chunk", b"RIFFxxxx"),
            ("message", {"stream": {"event": "error", "message": "recorder died"}}),
        ]

        with caplog.at_level(logging.WARNING, logger=LOGGER):
            response, _, _ = run(self._drive(relay_mod, items))

        assert response.forced_close is True
        assert response.eof is False
        assert "recorder died" in caplog.text

    def test_an_error_after_the_body_started_tears_the_response_down(
        self, relay_mod, stream, caplog
    ):
        """Headers are on the wire, so the only honest signal left is a closed
        connection — the client must not see a complete-looking download."""
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x01" * 8
        items = [self._start(len(payload)), ("chunk", payload), ("error", "phone died mid-download")]

        with caplog.at_level(logging.WARNING, logger=LOGGER):
            response, _, _ = run(self._drive(relay_mod, items))

        assert response.status == 200
        assert response.forced_close is True
        assert response.eof is False
        assert "phone died mid-download" in caplog.text

    def test_an_unknown_stream_event_tears_the_response_down(self, relay_mod, stream):
        items = [self._start(4), ("chunk", b"RIFF"), ("message", {"stream": {"event": "pause"}})]

        response, _, _ = run(self._drive(relay_mod, items))

        assert response.forced_close is True

    def test_an_unknown_queue_item_tears_the_response_down(self, relay_mod, stream):
        items = [self._start(4), ("chunk", b"RIFF"), ("nonsense", b"?")]

        response, _, _ = run(self._drive(relay_mod, items))

        assert response.forced_close is True

    def test_a_short_stream_is_refused_at_the_end_event(self, relay_mod, stream, caplog):
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x01" * 8
        items = [
            self._start(len(payload)),
            ("chunk", payload[:10]),
            (
                "message",
                {
                    "stream": {
                        "event": "end",
                        "bytes": len(payload),
                        "sha256": hashlib.sha256(payload).hexdigest(),
                    }
                },
            ),
        ]

        with caplog.at_level(logging.WARNING, logger=LOGGER):
            response, _, _ = run(self._drive(relay_mod, items))

        assert response.forced_close is True
        assert "Recording stream length does not match metadata" in caplog.text

    def test_end_bytes_disagreeing_with_the_chunks_is_refused(self, relay_mod, stream, caplog):
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x01" * 8
        items = [
            self._start(len(payload)),
            ("chunk", payload),
            (
                "message",
                {
                    "stream": {
                        "event": "end",
                        "bytes": len(payload) + 1,
                        "sha256": hashlib.sha256(payload).hexdigest(),
                    }
                },
            ),
        ]

        with caplog.at_level(logging.WARNING, logger=LOGGER):
            response, _, _ = run(self._drive(relay_mod, items))

        assert response.forced_close is True
        assert "Recording stream length does not match metadata" in caplog.text

    def test_a_wrong_checksum_is_refused(self, relay_mod, stream, caplog):
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x01" * 8
        items = [
            self._start(len(payload)),
            ("chunk", payload),
            (
                "message",
                {"stream": {"event": "end", "bytes": len(payload), "sha256": "0" * 64}},
            ),
        ]

        with caplog.at_level(logging.WARNING, logger=LOGGER):
            response, _, _ = run(self._drive(relay_mod, items))

        assert response.forced_close is True
        assert "Recording stream checksum mismatch" in caplog.text

    def test_a_malformed_checksum_is_refused(self, relay_mod, stream, caplog):
        """Not 64 hex characters, so compare_digest would raise on the encoding
        rather than compare anything."""
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x01" * 8
        items = [
            self._start(len(payload)),
            ("chunk", payload),
            (
                "message",
                {"stream": {"event": "end", "bytes": len(payload), "sha256": "not-a-sha"}},
            ),
        ]

        with caplog.at_level(logging.WARNING, logger=LOGGER):
            run(self._drive(relay_mod, items))

        assert "Recording stream checksum mismatch" in caplog.text

    def test_a_missing_checksum_is_refused(self, relay_mod, stream, caplog):
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x01" * 8
        items = [
            self._start(len(payload)),
            ("chunk", payload),
            ("message", {"stream": {"event": "end", "bytes": len(payload)}}),
        ]

        with caplog.at_level(logging.WARNING, logger=LOGGER):
            run(self._drive(relay_mod, items))

        assert "Recording stream checksum mismatch" in caplog.text

    def test_an_error_mid_stream_after_metadata_is_a_502(self, relay_mod, stream):
        """No response body has been started yet, so the client can still be told
        what happened instead of getting a truncated 200."""
        items = [("error", "phone disconnected")]

        response, _, _ = run(self._drive(relay_mod, items))

        assert response.status == 502

    def test_nothing_arriving_is_a_504(self, relay_mod, monkeypatch, caplog):
        monkeypatch.setattr(relay_mod, "_RESPONSE_TIMEOUT", 0.05)

        async def scenario():
            state = make_relay_state(relay_mod)
            queue = asyncio.Queue(maxsize=8)
            async with state.streams_lock:
                state.streams["rid-1"] = queue
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                response = await relay_mod._relay_binary_stream(
                    FakeRequest(), state, "rid-1", queue
                )
            return response, state

        response, state = run(scenario())

        assert response.status == 504
        assert response_json(response) == {"error": "Phone stream timed out"}
        assert "Timed out receiving microphone stream" in caplog.text
        assert state.streams == {}

    def test_a_stall_after_the_body_started_tears_the_response_down(
        self, relay_mod, stream, monkeypatch, caplog
    ):
        """Headers are already on the wire, so a 504 is impossible; closing the
        connection is the only honest signal left."""
        monkeypatch.setattr(relay_mod, "_RESPONSE_TIMEOUT", 0.05)
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x01" * 8

        async def scenario():
            state = make_relay_state(relay_mod)
            queue = asyncio.Queue(maxsize=8)
            queue.put_nowait(self._start(len(payload)))
            queue.put_nowait(("chunk", payload))
            async with state.streams_lock:
                state.streams["rid-1"] = queue
            with caplog.at_level(logging.WARNING, logger=LOGGER):
                response = await relay_mod._relay_binary_stream(
                    FakeRequest(), state, "rid-1", queue
                )
            return response

        response = run(scenario())

        assert response.forced_close is True
        assert response.eof is False
        assert "Timed out receiving microphone stream" in caplog.text

    def test_the_queue_is_drained_so_a_waiting_phone_is_not_stuck(self, relay_mod, stream):
        """The queue is bounded; if the HTTP caller gives up while the phone is
        blocked on a full queue, the finally block has to free it."""
        payload = b"RIFF" + b"\x00" * 4 + b"WAVE" + b"\x01" * 8

        async def scenario():
            state = make_relay_state(relay_mod)
            queue = asyncio.Queue(maxsize=8)
            queue.put_nowait(self._start(len(payload)))
            for _ in range(7):  # maxsize is 8, including the metadata item
                queue.put_nowait(("chunk", b"leftover"))
            async with state.streams_lock:
                state.streams["rid-1"] = queue
            response = await relay_mod._relay_binary_stream(
                FakeRequest(), state, "rid-1", queue
            )
            return response, queue, state

        response, queue, state = run(scenario())

        assert response.forced_close is True
        assert queue.empty()
        assert state.streams == {}

    def test_a_failed_stream_is_unregistered_even_when_it_never_started(
        self, relay_mod, stream
    ):
        async def scenario():
            state = make_relay_state(relay_mod)
            queue = asyncio.Queue(maxsize=8)
            queue.put_nowait(("error", "gone"))
            async with state.streams_lock:
                state.streams["rid-1"] = queue
            await relay_mod._relay_binary_stream(FakeRequest(), state, "rid-1", queue)
            return state

        assert run(scenario()).streams == {}

    def test_the_final_drain_survives_a_concurrent_consumer(self, relay_mod, stream):
        async def scenario():
            state = make_relay_state(relay_mod)
            queue = RacingQueue(first_item=("error", "gone"))
            async with state.streams_lock:
                state.streams["rid-1"] = queue
            response = await relay_mod._relay_binary_stream(
                FakeRequest(), state, "rid-1", queue
            )
            return response, state

        response, state = run(scenario())

        assert response.status == 502
        assert state.streams == {}

    def test_a_replaced_queue_is_not_unregistered(self, relay_mod, stream):
        """The finally block only removes its own entry; a retry that already
        re-registered the request_id must survive."""
        async def scenario():
            state = make_relay_state(relay_mod)
            queue = asyncio.Queue(maxsize=8)
            replacement = asyncio.Queue(maxsize=8)
            queue.put_nowait(("error", "gone"))
            async with state.streams_lock:
                state.streams["rid-1"] = replacement
            await relay_mod._relay_binary_stream(FakeRequest(), state, "rid-1", queue)
            return state, replacement

        state, replacement = run(scenario())

        assert state.streams["rid-1"] is replacement
