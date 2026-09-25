import pytest
import threading
import time
from tools.android_relay import (
    start_relay,
    stop_relay,
    is_relay_running,
    is_phone_connected,
    get_relay_url,
    set_pairing_code,
    _ws_auth,
    _http_auth,
    _is_loopback,
    _AUTH_MAX_ATTEMPTS,
    _mask_token,
    _safe_body_repr,
    _decode_stream_frame,
    _cleanup_phone,
    _RelayState,
)


@pytest.fixture(autouse=True)
def reset_relay():
    yield
    stop_relay()


@pytest.fixture(autouse=True)
def reset_auth_state():
    _ws_auth.reset()
    _http_auth.reset()
    yield
    _ws_auth.reset()
    _http_auth.reset()


def _active_code():
    import tools.android_relay as relay_mod

    return relay_mod._relay_instance.pairing_code


def _await_log_record(caplog, substring, timeout=5.0, poll=0.01):
    """Wait for a captured log record containing `substring`; fail loudly if none.

    The relay finalises a stream on its own background thread and its own
    event loop, so its warning about that stream can land *after* the HTTP
    client the test drives has already returned. Asserting on `caplog.records`
    straight after the scenario therefore races the relay: the assertion is
    right and the record simply has not been emitted yet.

    Waiting on the condition rather than guessing a delay is what makes the
    stream tests deterministic. A timeout raises with the records that *were*
    seen, so a genuine regression still fails with something readable instead
    of a bare `assert False`.
    """
    deadline = time.monotonic() + timeout
    while True:
        for record in caplog.records:
            if substring in record.getMessage():
                return record
        if time.monotonic() >= deadline:
            seen = [record.getMessage() for record in caplog.records]
            raise AssertionError(
                f"no log record containing {substring!r} within {timeout}s; "
                f"saw {len(seen)} record(s): {seen}"
            )
        time.sleep(poll)


class TestRelayLifecycle:
    def test_start_with_specific_port(self):
        start_relay(pairing_code="TEST01", port=19876)
        assert is_relay_running()
        url = get_relay_url()
        assert "19876" in url
        stop_relay()

    def test_stop_when_not_running(self):
        stop_relay()

    def test_double_start_is_noop(self):
        assert start_relay(pairing_code="TEST01", port=19877) is True
        # A second start leaves the running relay — and its code — alone;
        # re-pairing has to go through set_pairing_code().
        assert start_relay(pairing_code="TEST02", port=19877) is False
        assert is_relay_running()
        assert _active_code() == "TEST01"
        stop_relay()

    def test_start_fails_fast_when_port_is_taken(self):
        import socket

        blocker = socket.socket()
        blocker.bind(("localhost", 19879))
        blocker.listen()
        try:
            started = time.monotonic()
            with pytest.raises(RuntimeError, match="19879"):
                start_relay(pairing_code="TEST03", port=19879)
            assert time.monotonic() - started < 5
            assert not is_relay_running()
        finally:
            blocker.close()

    def test_is_phone_connected_false(self):
        assert not is_phone_connected()

    def test_is_relay_running_false_initially(self):
        assert not is_relay_running()

    def test_get_relay_url_returns_default_when_stopped(self):
        url = get_relay_url()
        assert url is not None
        assert "localhost" in url

    def test_set_pairing_code_without_relay_is_reported(self):
        assert set_pairing_code("NEPCODE") is False

    def test_set_pairing_code_updates_the_live_relay(self):
        import requests

        start_relay(pairing_code="OLDCDE", port=19878)
        assert set_pairing_code("NEWCDE") is True
        assert _active_code() == "NEWCDE"

        url = "http://127.0.0.1:19878/ping"
        # 503 = auth passed, no phone; 401 = token refused.
        assert requests.get(url, headers={"Authorization": "Bearer NEWCDE"}, timeout=5).status_code == 503
        assert requests.get(url, headers={"Authorization": "Bearer OLDCDE"}, timeout=5).status_code == 401
        stop_relay()

    def test_set_pairing_code_disconnects_phone_on_old_code(self):
        import asyncio
        import aiohttp

        start_relay(pairing_code="OLDCDE", port=19878)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    "ws://127.0.0.1:19878/ws",
                    headers={"Authorization": "Bearer OLDCDE"},
                )
                for _ in range(100):
                    if is_phone_connected():
                        break
                    await asyncio.sleep(0.01)
                assert is_phone_connected()
                await asyncio.to_thread(set_pairing_code, "NEWCDE")
                msg = await ws.receive(timeout=5)
                assert msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED)
                await ws.close()

        asyncio.run(scenario())
        assert not is_phone_connected()


class TestRateLimiting:
    def test_not_blocked_initially(self):
        assert not _ws_auth.is_blocked("1.2.3.4")

    def test_blocked_after_max_failures(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _ws_auth.record_failure("1.2.3.4")
        assert _ws_auth.is_blocked("1.2.3.4")

    def test_different_ip_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _ws_auth.record_failure("1.2.3.4")
        assert not _ws_auth.is_blocked("5.6.7.8")

    def test_under_limit_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS - 1):
            _ws_auth.record_failure("1.2.3.4")
        assert not _ws_auth.is_blocked("1.2.3.4")

    def test_ws_and_http_tables_are_separate(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _http_auth.record_failure("127.0.0.1")
        assert _http_auth.is_blocked("127.0.0.1")
        assert not _ws_auth.is_blocked("127.0.0.1")

    def test_loopback_detection(self):
        assert _is_loopback("127.0.0.1")
        assert _is_loopback("::1")
        assert not _is_loopback("192.168.1.5")
        assert not _is_loopback("unknown")


class TestHttpBan:
    """Regression: five bad HTTP requests used to ban localhost for 300 s on
    the table the phone's WS handshake also used, so the agent — and the
    phone — were locked out even with the correct code."""

    PORT = 19885
    CODE = "BANCDE"

    def _burn_http_attempts(self):
        import requests

        url = f"http://127.0.0.1:{self.PORT}/ping"
        statuses = [
            requests.get(url, headers={"Authorization": "Bearer WRONG0"}, timeout=5).status_code
            for _ in range(_AUTH_MAX_ATTEMPTS + 1)
        ]
        assert statuses[:_AUTH_MAX_ATTEMPTS] == [401] * _AUTH_MAX_ATTEMPTS
        assert statuses[-1] == 429  # the ban does engage for bad tokens
        return url

    def test_valid_token_from_loopback_is_not_refused_by_the_ban(self):
        import requests

        start_relay(pairing_code=self.CODE, port=self.PORT)
        url = self._burn_http_attempts()
        good = requests.get(url, headers={"Authorization": f"Bearer {self.CODE}"}, timeout=5)
        assert good.status_code == 503  # auth passed; no phone connected

    def test_http_failures_do_not_ban_the_phone(self):
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.CODE, port=self.PORT)
        self._burn_http_attempts()

        async def connect():
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"},
                )
                await ws.close()

        asyncio.run(connect())


class TestTokenMasking:
    """Verify that bad auth tokens are masked in log output, not logged in plaintext."""

    def test_normal_token_masked(self):
        token = "SECRET123"
        masked = _mask_token(token)
        assert masked == "****"
        assert token not in masked

    def test_short_token_fully_masked(self):
        assert _mask_token("X") == "****"
        assert _mask_token("AB") == "****"

    def test_empty_token_fully_masked(self):
        assert _mask_token("") == "****"


class TestBodyLogRedaction:
    """Regression: request-body debug logs must not contain PII (AGENTS.md rule:
    strip phone numbers, recipients, location from tool responses/logs)."""

    def test_sms_body_redacted(self):
        repr_ = _safe_body_repr({"to": "+15551234567", "body": "secret message"})
        assert "+15551234567" not in repr_
        assert "secret message" not in repr_
        assert "<redacted>" in repr_

    def test_call_number_redacted(self):
        repr_ = _safe_body_repr({"number": "+15551234567"})
        assert "+15551234567" not in repr_

    def test_typed_text_redacted(self):
        repr_ = _safe_body_repr({"text": "hunter2-password"})
        assert "hunter2-password" not in repr_

    def test_non_sensitive_fields_kept(self):
        repr_ = _safe_body_repr({"x": 100, "y": 200})
        assert "100" in repr_ and "200" in repr_

    def test_empty_body(self):
        assert _safe_body_repr({}) == "{}"

    def test_truncated_to_200_chars(self):
        repr_ = _safe_body_repr({"key": "v" * 500})
        assert len(repr_) <= 200

    def test_intent_data_uri_redacted(self):
        repr_ = _safe_body_repr({"action": "VIEW", "dataUri": "tel:+15551234567"})
        assert "+15551234567" not in repr_
        assert "VIEW" in repr_

    def test_every_pii_payload_android_tool_sends_is_redacted(self, monkeypatch):
        """Drive each PII-carrying tool with a sentinel and run the body it
        actually sends through the relay's log redaction. A new payload key
        that carries user data but is missing from _SENSITIVE_BODY_KEYS fails
        here rather than landing in a debug log."""
        import tools.android_tool as tool

        sent = []
        monkeypatch.setattr(tool, "_post", lambda path, payload: sent.append((path, payload)) or {})
        pii = "PII-SENTINEL-+15551234567"

        tool.android_send_sms(to=pii, body=pii)
        tool.android_call(number=pii)
        tool.android_type(text=pii)
        tool.android_tap_text(text=pii)
        tool.android_clipboard_write(text=pii)
        tool.android_speak(text=pii)
        tool.android_wait(text=pii)
        tool.android_find_nodes(text=pii)
        tool.android_send_intent(action="android.intent.action.VIEW", data_uri=pii, extras={"k": pii})
        tool.android_broadcast(action="com.example.PING", extras={"k": pii})

        assert len(sent) == 10
        for path, payload in sent:
            assert pii not in _safe_body_repr(payload), f"{path} leaks PII via {sorted(payload)}"


class TestWsAuthHeader:
    """Regression: WS handshake auth accepts a Bearer header only. The ?token=
    query string fallback was removed because it leaked pairing codes into
    reverse-proxy access logs."""

    PORT = 19881
    CODE = "WSCODE"

    def _try_connect(self, headers=None, query=""):
        import asyncio
        import aiohttp

        async def attempt():
            async with aiohttp.ClientSession() as session:
                try:
                    ws = await session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/ws{query}", headers=headers or {}
                    )
                    await ws.close()
                    return True
                except aiohttp.WSServerHandshakeError:
                    return False

        return asyncio.run(attempt())

    def test_bearer_header_accepted(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert self._try_connect(headers={"Authorization": f"Bearer {self.CODE}"})

    def test_query_token_rejected(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not self._try_connect(query=f"?token={self.CODE}")

    def test_bad_bearer_header_rejected(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not self._try_connect(headers={"Authorization": "Bearer WRONG1"})

    def test_no_credentials_rejected(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not self._try_connect()


class TestMicrophoneBinaryStream:
    PORT = 19882
    CODE = "MICODE"

    @staticmethod
    def _frame(request_id: str, payload: bytes) -> bytes:
        request_id_bytes = request_id.encode("utf-8")
        return len(request_id_bytes).to_bytes(2, "big") + request_id_bytes + payload

    def test_binary_frame_decoder(self):
        raw = self._frame("request-1", b"audio")
        assert _decode_stream_frame(raw) == ("request-1", b"audio")

    @pytest.mark.parametrize("raw", [b"", b"\x00\x00x", b"\x00\x05abc"])
    def test_binary_frame_decoder_rejects_malformed_frames(self, raw):
        with pytest.raises(ValueError):
            _decode_stream_frame(raw)

    def test_replaced_socket_cannot_clean_up_new_phone(self):
        import asyncio

        async def scenario():
            state = _RelayState(pairing_code=self.CODE, port=self.PORT)
            state.phone_ws_lock = asyncio.Lock()
            old_socket = object()
            new_socket = object()
            state.phone_ws = new_socket

            await _cleanup_phone(
                state,
                reason="old socket disconnected",
                expected_ws=old_socket,
            )

            assert state.phone_ws is new_socket

        asyncio.run(scenario())

    def test_wav_is_streamed_from_phone_to_http_client(self):
        import asyncio
        import hashlib
        import aiohttp

        wav = b"RIFF" + (b"\x01\x02" * 64)
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers=headers,
                )
                download = asyncio.create_task(
                    session.get(
                        f"http://127.0.0.1:{self.PORT}/mic_file",
                        headers=headers,
                    )
                )

                command = await ws.receive_json(timeout=2)
                request_id = command["request_id"]
                assert command["path"] == "/mic_file"

                await ws.send_json(
                    {
                        "request_id": request_id,
                        "status": 200,
                        "stream": {
                            "event": "start",
                            "filename": "recording_test.wav",
                            "mimeType": "audio/wav",
                            "size": len(wav),
                        },
                    }
                )
                await ws.send_bytes(self._frame(request_id, wav[:48]))
                await ws.send_bytes(self._frame(request_id, wav[48:]))
                await ws.send_json(
                    {
                        "request_id": request_id,
                        "status": 200,
                        "stream": {
                            "event": "end",
                            "bytes": len(wav),
                            "sha256": hashlib.sha256(wav).hexdigest(),
                        },
                    }
                )

                response = await asyncio.wait_for(download, timeout=2)
                assert response.status == 200
                assert response.headers["Content-Type"] == "audio/wav"
                assert await response.read() == wav
                await ws.close()

        asyncio.run(scenario())


class TestPhoneReplacement:
    PORT = 19883
    CODE = "REPLCE"

    def test_new_phone_remains_usable_after_replacing_old_socket(self):
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                old_ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers=headers,
                )
                old_close = asyncio.create_task(old_ws.receive())
                new_ws = await asyncio.wait_for(
                    session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/ws",
                        headers=headers,
                    ),
                    timeout=2,
                )
                await asyncio.wait_for(old_close, timeout=2)

                request = asyncio.create_task(
                    session.get(
                        f"http://127.0.0.1:{self.PORT}/ping",
                        headers=headers,
                    )
                )
                command = await new_ws.receive_json(timeout=2)
                await new_ws.send_json(
                    {
                        "request_id": command["request_id"],
                        "status": 200,
                        "result": {"status": "ok"},
                    }
                )

                response = await asyncio.wait_for(request, timeout=2)
                assert response.status == 200
                assert await response.json() == {"status": "ok"}
                await new_ws.close()

        asyncio.run(scenario())


class TestUnresponsiveOldPhone:
    PORT = 19886
    CODE = "HALFDD"

    def test_half_dead_old_socket_does_not_stall_http(self):
        """Closing the replaced socket can block for a long time on a
        half-dead peer (drain on a full send buffer, or waiting up to 10 s for
        its close frame). That must not happen under phone_ws_lock, or every
        HTTP request stalls behind it.

        A real half-dead TCP peer is hard to fake portably, so the relay-side
        socket's close() is replaced with one that hangs until released.
        """
        import asyncio
        import aiohttp
        import tools.android_relay as relay_mod

        start_relay(pairing_code=self.CODE, port=self.PORT)
        state = relay_mod._relay_instance
        release = asyncio.Event()  # bound to the relay loop on first await
        close_called = threading.Event()

        async def hanging_close(*args, **kwargs):
            close_called.set()
            await release.wait()
            return True

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                old_ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws", headers=headers
                )
                for _ in range(200):
                    if state.phone_ws is not None:
                        break
                    await asyncio.sleep(0.01)
                state.phone_ws.close = hanging_close

                new_ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws", headers=headers
                )
                assert await asyncio.to_thread(close_called.wait, 3)
                request = asyncio.create_task(
                    session.get(f"http://127.0.0.1:{self.PORT}/ping", headers=headers)
                )
                command = await new_ws.receive_json(timeout=3)
                await new_ws.send_json(
                    {"request_id": command["request_id"], "status": 200, "result": {"ok": True}}
                )
                response = await asyncio.wait_for(request, timeout=3)
                assert response.status == 200
                state.loop.call_soon_threadsafe(release.set)
                await new_ws.close()
                await old_ws.close()

        try:
            asyncio.run(scenario())
        finally:
            state.loop.call_soon_threadsafe(release.set)


class TestPhoneMessageLogging:
    def test_non_json_and_missing_id_log_no_content(self, caplog):
        import asyncio
        import logging
        from tools.android_relay import _on_phone_message

        state = _RelayState(pairing_code="LOGCDE", port=0)
        secret = "+15551234567 meet at 221B Baker St"
        with caplog.at_level(logging.DEBUG, logger="android_relay"):
            asyncio.run(_on_phone_message(state, secret))
            asyncio.run(_on_phone_message(state, '{"result": "' + secret + '"}'))
            asyncio.run(_on_phone_message(state, '["' + secret + '"]'))

        messages = [r.getMessage() for r in caplog.records]
        assert len(messages) == 3
        for message in messages:
            assert "+1555" not in message and "Baker" not in message


class TestMicrophoneBinaryStreamNegative:
    """Negative paths for the binary stream relay (#99).

    The happy path (TestMicrophoneBinaryStream) covers a well-behaved phone.
    These tests pin the relay's behavior against a hostile or failing phone:
    checksum mismatch, oversize payloads, length-mismatched end events, an
    invalid declared size, and a mid-stream disconnect. In every case the
    HTTP caller must get either an error status or a torn-down connection —
    never a silent partial file presented as complete.
    """

    PORT = 19884
    CODE = "MINEG1"

    @staticmethod
    def _frame(request_id: str, payload: bytes) -> bytes:
        request_id_bytes = request_id.encode("utf-8")
        return len(request_id_bytes).to_bytes(2, "big") + request_id_bytes + payload

    def _run_stream_scenario(self, phone_play, expect):
        import asyncio
        import aiohttp

        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            headers = {"Authorization": f"Bearer {self.CODE}"}
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers=headers,
                )
                download = asyncio.create_task(
                    session.get(
                        f"http://127.0.0.1:{self.PORT}/mic_file",
                        headers=headers,
                    )
                )
                command = await ws.receive_json(timeout=2)
                assert command["path"] == "/mic_file"

                await phone_play(ws, command["request_id"])

                if expect == "status":
                    response = await asyncio.wait_for(download, timeout=2)
                    assert response.status >= 400
                    await ws.close()
                    return
                if expect == "teardown-or-complete":
                    # Detection happens after the last byte is delivered; the
                    # client either sees a torn-down body or the full payload.
                    # Server-side logs are asserted by the caller.
                    response = await asyncio.wait_for(download, timeout=2)
                    try:
                        await asyncio.wait_for(response.read(), timeout=2)
                    except aiohttp.ClientError:
                        pass
                    await ws.close()
                    return
                # expect == "teardown": the client must never see a silent
                # truncated success. Three outcomes all satisfy that, and
                # which one happens depends on whether the relay processes
                # the phone's disconnect before it commits the response:
                #   - the connection dies before or with the headers,
                #   - the headers arrive and the body read fails,
                #   - the relay notices first and answers with an error status.
                try:
                    response = await asyncio.wait_for(download, timeout=2)
                except aiohttp.ClientError:
                    response = None  # died before/with headers
                if response is not None:
                    try:
                        await asyncio.wait_for(response.read(), timeout=2)
                    except aiohttp.ClientError:
                        pass  # torn-down body — the intended outcome
                    else:
                        assert response.status >= 400, (
                            "stream completed cleanly with status "
                            f"{response.status}; a truncated success is the "
                            "one outcome this test exists to prevent"
                        )
                await ws.close()

        asyncio.run(scenario())

    def test_sha256_mismatch_is_detected_server_side(self, caplog):
        import hashlib
        import logging

        wav = b"RIFF" + (b"\x09\x09" * 64)

        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_bad.wav",
                        "mimeType": "audio/wav",
                        "size": len(wav),
                    },
                }
            )
            await ws.send_bytes(self._frame(request_id, wav))
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "end",
                        "bytes": len(wav),
                        # Wrong digest over different content.
                        "sha256": hashlib.sha256(b"tampered").hexdigest(),
                    },
                }
            )

        # The relay withholds the last chunk until the checksum is verified,
        # so the client must see a torn-down download, never the full body.
        with caplog.at_level(logging.WARNING, logger="tools.android_relay"):
            self._run_stream_scenario(phone_play, expect="teardown")
            # The relay reaches this verdict on its own thread, after the
            # client has returned — wait for it rather than racing it.
            record = _await_log_record(caplog, "checksum mismatch")

        assert "Recording stream checksum mismatch" in record.getMessage()

    def test_oversize_chunk_aborts_the_download(self):
        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_big.wav",
                        "mimeType": "audio/wav",
                        "size": 8,  # declare 8 bytes...
                    },
                }
            )
            # ...then send 48.
            await ws.send_bytes(self._frame(request_id, b"\x01" * 48))

        self._run_stream_scenario(phone_play, expect="teardown")

    def test_end_bytes_mismatch_is_detected_server_side(self, caplog):
        import hashlib
        import logging

        wav = b"RIFF" + (b"\x02\x02" * 32)

        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_len.wav",
                        "mimeType": "audio/wav",
                        "size": len(wav),
                    },
                }
            )
            await ws.send_bytes(self._frame(request_id, wav))
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "end",
                        "bytes": len(wav) + 100,  # lies about the total
                        "sha256": hashlib.sha256(wav).hexdigest(),
                    },
                }
            )

        # Same as the checksum case: the withheld final chunk is never sent.
        with caplog.at_level(logging.WARNING, logger="tools.android_relay"):
            self._run_stream_scenario(phone_play, expect="teardown")
            # As above: the verdict is logged on the relay's own thread.
            record = _await_log_record(caplog, "length does not match")

        assert "Recording stream length does not match metadata" in record.getMessage()

    def test_declared_size_above_the_cap_is_rejected_up_front(self):
        from tools.android_relay import _MAX_STREAM_BYTES

        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_huge.wav",
                        "mimeType": "audio/wav",
                        "size": _MAX_STREAM_BYTES + 1,
                    },
                }
            )

        self._run_stream_scenario(phone_play, expect="status")

    def test_negative_declared_size_is_rejected_up_front(self):
        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_neg.wav",
                        "mimeType": "audio/wav",
                        "size": -1,
                    },
                }
            )

        self._run_stream_scenario(phone_play, expect="status")

    def test_phone_disconnect_mid_stream_tears_down_the_download(self):
        async def phone_play(ws, request_id):
            await ws.send_json(
                {
                    "request_id": request_id,
                    "status": 200,
                    "stream": {
                        "event": "start",
                        "filename": "recording_cut.wav",
                        "mimeType": "audio/wav",
                        "size": 1024,
                    },
                }
            )
            await ws.send_bytes(self._frame(request_id, b"\x03" * 48))
            await ws.close()  # phone vanishes mid-stream

        self._run_stream_scenario(phone_play, expect="teardown")


class TestAwaitLogRecord:
    """The wait helper the stream tests rely on must never pass silently.

    These pin the helper itself, because the whole point of it is to turn a
    lost race into a loud failure. A helper that returned happily when the
    record never arrived would be worse than the bare assert it replaces.
    """

    LOGGER = "tools.android_relay"

    def test_it_returns_the_record_that_is_already_there(self, caplog):
        import logging

        with caplog.at_level(logging.WARNING, logger=self.LOGGER):
            logging.getLogger(self.LOGGER).warning("checksum mismatch seen")

        record = _await_log_record(caplog, "checksum mismatch", timeout=1)

        assert "checksum mismatch" in record.getMessage()

    def test_it_waits_for_a_record_that_arrives_late(self, caplog):
        """The race this exists for: the relay logs after the client returns.

        A bare `assert any(...)` fails here; only waiting passes.
        """
        import logging

        def emit_later():
            time.sleep(0.2)
            logging.getLogger(self.LOGGER).warning("late length does not match")

        with caplog.at_level(logging.WARNING, logger=self.LOGGER):
            thread = threading.Thread(target=emit_later, daemon=True)
            thread.start()
            try:
                record = _await_log_record(
                    caplog, "length does not match", timeout=5
                )
            finally:
                thread.join(timeout=5)

        assert "length does not match" in record.getMessage()

    def test_it_fails_loudly_when_the_record_never_arrives(self, caplog):
        with pytest.raises(AssertionError, match="no log record containing"):
            _await_log_record(caplog, "this is never logged", timeout=0.05)


class TestRouteAllowlist:
    """The relay forwards only what _ROUTES lists, so gaps are silent.

    A route added to CommandDispatcher but not to the allowlist works over
    direct USB/LAN and simply does not exist over the relay — which is the
    default transport. Nothing fails loudly, so these tests are the only thing
    standing between a new endpoint and being quietly unreachable.
    """

    def test_battery_is_forwarded_as_a_get(self):
        from tools.android_relay import _ROUTES

        assert _ROUTES.get("/battery") == "GET"

    def test_the_documented_endpoints_are_exactly_the_allowlist(self):
        """docs/architecture.md and _ROUTES must not drift in either direction.

        Catches both the endpoint that was documented but never allowlisted —
        reachable on paper, refused in practice — and the one that was
        allowlisted but never documented.
        """
        import pathlib
        import re
        from tools.android_relay import _ROUTES

        doc = pathlib.Path(__file__).resolve().parent.parent / "docs" / "architecture.md"
        line = next(
            l for l in doc.read_text(encoding="utf-8").splitlines()
            if "HTTP bridge endpoints (method per path)" in l
        )

        def paths(section, terminator):
            start = line.index(section) + len(section)
            end = line.index(terminator, start) if terminator else len(line)
            return re.findall(r"`([^`]+)`", line[start:end])

        documented = {}
        for path in paths("GET: ", ". POST:"):
            documented[path] = "GET"
        for path in paths("POST: ", ". Both:"):
            documented[path] = "POST"
        for path in paths("Both: ", None):
            documented[path] = "BOTH"

        assert documented == _ROUTES, (
            "docs/architecture.md and the relay allowlist disagree — "
            f"documented only: {sorted(set(documented) - set(_ROUTES))}, "
            f"allowlisted only: {sorted(set(_ROUTES) - set(documented))}, "
            "method mismatches: "
            f"{sorted(p for p in set(documented) & set(_ROUTES) if documented[p] != _ROUTES[p])}"
        )

    def test_the_bridge_routes_are_exactly_the_allowlist(self):
        """Parse the phone's actual routes and compare them to _ROUTES.

        CommandDispatcher.dispatch matches `method == "X" && path == "/y"`;
        /mic_file is the one route handled outside it, in RelayClient (binary
        stream), with the same matching shape.
        """
        import pathlib
        import re
        from tools.android_relay import _ROUTES

        kotlin = (
            pathlib.Path(__file__).resolve().parent.parent
            / "hermes-android-bridge" / "app" / "src" / "main" / "kotlin"
            / "com" / "hermesandroid" / "bridge"
        )
        pattern = re.compile(r'method\s*==\s*"(GET|POST)"\s*&&\s*path\s*==\s*"([^"]+)"')
        found: dict[str, set] = {}
        for rel in ("server/CommandDispatcher.kt", "client/RelayClient.kt"):
            for method, path in pattern.findall((kotlin / rel).read_text(encoding="utf-8")):
                found.setdefault(path, set()).add(method)
        assert len(found) > 30, "route pattern no longer matches the Kotlin source"

        bridge = {
            path: "BOTH" if methods == {"GET", "POST"} else methods.pop()
            for path, methods in found.items()
        }
        assert bridge == _ROUTES, (
            "CommandDispatcher/RelayClient and the relay allowlist disagree — "
            f"bridge only: {sorted(set(bridge) - set(_ROUTES))}, "
            f"allowlisted only: {sorted(set(_ROUTES) - set(bridge))}, "
            "method mismatches: "
            f"{sorted(p for p in set(bridge) & set(_ROUTES) if bridge[p] != _ROUTES[p])}"
        )

    def test_the_two_relay_copies_are_byte_identical(self):
        """AGENTS.md requires the plugin copy to track tools/ exactly.

        android_tool.py is allowed to differ (import path and registration
        trailer); android_relay.py is not. Comparing bytes rather than
        behaviour, because the rule is about the copies staying in step.
        """
        import pathlib

        root = pathlib.Path(__file__).resolve().parent.parent
        tools_copy = (root / "tools" / "android_relay.py").read_bytes()
        plugin_copy = (root / "hermes-android-plugin" / "android_relay.py").read_bytes()

        assert tools_copy == plugin_copy, (
            "tools/android_relay.py and hermes-android-plugin/android_relay.py "
            "have drifted; apply the same change to both"
        )
