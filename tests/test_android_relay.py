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
    _auth_is_blocked,
    _auth_record_failure,
    _auth_lock,
    _auth_blocked,
    _auth_failures,
    _AUTH_MAX_ATTEMPTS,
    _mask_token,
    _safe_body_repr,
    _SENSITIVE_BODY_KEYS,
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
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    yield
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()


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
        start_relay(pairing_code="TEST01", port=19877)
        start_relay(pairing_code="TEST02", port=19877)
        assert is_relay_running()
        stop_relay()

    def test_is_phone_connected_false(self):
        assert not is_phone_connected()

    def test_is_relay_running_false_initially(self):
        assert not is_relay_running()

    def test_get_relay_url_returns_default_when_stopped(self):
        url = get_relay_url()
        assert url is not None
        assert "localhost" in url

    def test_set_pairing_code(self):
        set_pairing_code("NEPCODE")
        start_relay(pairing_code="NEPCODE", port=19878)
        assert is_relay_running()
        stop_relay()


class TestRateLimiting:
    def test_not_blocked_initially(self):
        assert not _auth_is_blocked("1.2.3.4")

    def test_blocked_after_max_failures(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _auth_record_failure("1.2.3.4")
        assert _auth_is_blocked("1.2.3.4")

    def test_different_ip_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _auth_record_failure("1.2.3.4")
        assert not _auth_is_blocked("5.6.7.8")

    def test_under_limit_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS - 1):
            _auth_record_failure("1.2.3.4")
        assert not _auth_is_blocked("1.2.3.4")


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


class TestRelayEnvFileFallback:
    """Review F5: the relay read ANDROID_RELAY_PORT/HOST/CERT/KEY from os.environ
    only, while docs/configuration.md lists them in the same ~/.hermes/.env table
    as the two variables that did fall back. The relay started in-process by
    android_setup runs inside the gateway, which is exactly the process that does
    not export .env — the condition the fallback exists for. (The systemd unit in
    contrib/ uses EnvironmentFile=, so the daemon path was never affected.)
    """

    @pytest.fixture(autouse=True)
    def _reset_cache(self, monkeypatch, tmp_path):
        import tools.android_relay as relay

        monkeypatch.setattr(relay, "_ENV_FILE_CACHE", None, raising=False)
        monkeypatch.setenv("HERMES_HOME", str(tmp_path))
        for key in (
            "ANDROID_RELAY_PORT",
            "ANDROID_RELAY_HOST",
            "ANDROID_RELAY_CERT",
            "ANDROID_RELAY_KEY",
        ):
            monkeypatch.delenv(key, raising=False)
        self.env_file = tmp_path / ".env"
        self.relay = relay

    def test_port_from_env_file(self):
        self.env_file.write_text("ANDROID_RELAY_PORT=9000\n")
        assert self.relay._relay_port() == 9000

    def test_port_defaults_when_unset(self):
        assert self.relay._relay_port() == 8766

    def test_host_from_env_file(self):
        self.env_file.write_text("ANDROID_RELAY_HOST=0.0.0.0\n")
        assert self.relay._relay_host() == "0.0.0.0"

    def test_host_defaults_when_unset(self):
        assert self.relay._relay_host() == "localhost"

    def test_os_environ_wins(self, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_PORT", "7000")
        self.env_file.write_text("ANDROID_RELAY_PORT=9000\n")
        assert self.relay._relay_port() == 7000

    def test_tls_paths_from_env_file(self, monkeypatch):
        """A relay configured for TLS in .env must actually serve TLS; returning
        None here silently downgrades wss:// to ws://."""
        self.env_file.write_text("ANDROID_RELAY_CERT=/c.pem\nANDROID_RELAY_KEY=/k.pem\n")
        seen = {}

        def fake_load(self_ctx, certfile, keyfile):
            seen["cert"] = certfile
            seen["key"] = keyfile

        import ssl

        monkeypatch.setattr(ssl.SSLContext, "load_cert_chain", fake_load)
        assert self.relay._ssl_context() is not None
        assert seen == {"cert": "/c.pem", "key": "/k.pem"}

    def test_tls_stays_off_without_both_paths(self):
        self.env_file.write_text("ANDROID_RELAY_CERT=/c.pem\n")
        assert self.relay._ssl_context() is None

    def test_get_relay_url_uses_the_env_file_port(self):
        self.env_file.write_text("ANDROID_RELAY_PORT=9000\n")
        assert get_relay_url() == "http://localhost:9000"


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

    def test_send_intent_data_uri_redacted(self):
        """Review L3: android_send_intent sends the URI under `dataUri`, which was
        not in the redaction set, so a DEBUG body log carried the full number.
        `extras` being covered did not help — the URI is a sibling field."""
        repr_ = _safe_body_repr(
            {
                "action": "android.intent.action.SENDTO",
                "dataUri": "smsto:+15551234567",
            }
        )
        assert "+15551234567" not in repr_
        assert "15551234567" not in repr_
        # The action itself is not PII and stays readable, or the log is useless.
        assert "android.intent.action.SENDTO" in repr_

    def test_deep_link_token_in_data_uri_redacted(self):
        repr_ = _safe_body_repr({"action": "android.intent.action.VIEW", "dataUri": "https://x.example/reset?t=SECRET"})
        assert "SECRET" not in repr_

    def test_every_pii_payload_key_android_send_intent_can_send_is_redacted(self, monkeypatch):
        """Guard the guard: derive the payload from the tool itself, so a new
        field cannot leak by default. Fails until someone classifies it."""
        from tools import android_tool

        captured = {}

        def fake_post(path, payload):
            captured.update(payload)
            return {}

        monkeypatch.setattr(android_tool, "_post", fake_post)
        android_tool.android_send_intent(
            "android.intent.action.SENDTO",
            data_uri="smsto:+15551234567",
            extras={"k": "v"},
            package="com.example",
        )

        # `action` and `packageOverride` name an app/verb, not a person, and stay
        # readable so the log is still useful for debugging.
        plain = {"action", "packageOverride"}
        sent = set(captured)
        assert sent, "the tool sent no payload — this test is not observing anything"
        unclassified = sent - set(_SENSITIVE_BODY_KEYS) - plain
        assert not unclassified, (
            f"android_send_intent payload keys with no redaction decision: "
            f"{sorted(unclassified)} — add them to _SENSITIVE_BODY_KEYS, or to `plain` here"
        )

    def test_empty_body(self):
        assert _safe_body_repr({}) == "{}"

    def test_truncated_to_200_chars(self):
        repr_ = _safe_body_repr({"key": "v" * 500})
        assert len(repr_) <= 200


class TestPhoneMessageLogging:
    """Malformed phone frames are logged by shape only, never by content.

    A phone message is screen text, SMS, contacts or location; the old warnings
    logged its first 200 characters, and a JSON array raised instead.
    """

    SECRET = "+15551234567 meet at 221B Baker St"

    def _log_messages(self, caplog, raw):
        import asyncio
        import logging
        from tools.android_relay import _on_phone_message

        state = _RelayState(pairing_code="LOGCDE", port=0)

        async def deliver():
            state.streams_lock = asyncio.Lock()
            state.pending_lock = asyncio.Lock()
            await _on_phone_message(state, raw)

        caplog.clear()
        with caplog.at_level(logging.DEBUG, logger="android_relay"):
            asyncio.run(deliver())
        return [r.getMessage() for r in caplog.records]

    @pytest.mark.parametrize(
        "raw",
        [
            SECRET,  # not JSON
            '{"result": "' + SECRET + '"}',  # object without request_id
            '["' + SECRET + '"]',  # JSON, but not an object
        ],
        ids=["non-json", "missing-request-id", "json-array"],
    )
    def test_malformed_frame_logs_no_content(self, caplog, raw):
        messages = self._log_messages(caplog, raw)

        assert len(messages) == 1
        assert "+1555" not in messages[0] and "Baker" not in messages[0]


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

        # The relay withholds the final chunk until the checksum is verified,
        # so the client must see a torn-down download, never the full body —
        # and the relay must log why.
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


class TestPairingCodeRotation:
    """AUTH-1: rotating the pairing code must take effect on a *running* relay.

    Before the fix `start_relay()` returned early whenever a relay thread was
    alive, so `set_pairing_code()` was the only way to rotate — and nothing in
    production called it. Worse, the WebSocket that was already open had been
    authenticated with the old code at handshake time and kept full device
    control after "rotation".
    """

    PORT = 19973
    OLD = "ABC234"
    NEW = "XYZ789"

    def _handshake_succeeds(self, code: str) -> bool:
        import asyncio

        import aiohttp

        async def attempt():
            async with aiohttp.ClientSession() as session:
                try:
                    ws = await session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/ws",
                        headers={"Authorization": f"Bearer {code}"},
                    )
                except aiohttp.WSServerHandshakeError:
                    return False
                await ws.close()
                return True

        return asyncio.run(attempt())

    @staticmethod
    async def _wait_for_phone(timeout: float = 10.0) -> bool:
        import asyncio

        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if is_phone_connected():
                return True
            await asyncio.sleep(0.05)
        return False

    def test_set_pairing_code_reaches_a_running_relay(self):
        import tools.android_relay as relay

        start_relay(pairing_code=self.OLD, port=self.PORT)
        assert is_relay_running()

        set_pairing_code(self.NEW)

        assert relay._relay_instance is not None
        assert relay._relay_instance.pairing_code == self.NEW, (
            "a running relay must pick up the rotated code — start_relay() alone "
            "is a no-op once the thread is alive"
        )

    def test_start_relay_alone_does_not_rotate_a_running_relay(self):
        """Pins *why* set_pairing_code is required: start_relay is a no-op."""
        import tools.android_relay as relay

        start_relay(pairing_code=self.OLD, port=self.PORT)
        start_relay(pairing_code=self.NEW, port=self.PORT)

        assert relay._relay_instance.pairing_code == self.OLD

    def test_rotation_closes_the_session_authenticated_with_the_old_code(self):
        import asyncio

        import aiohttp

        start_relay(pairing_code=self.OLD, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.OLD}"},
                )
                assert await self._wait_for_phone(), "phone session never registered"

                set_pairing_code(self.NEW)

                msg = await asyncio.wait_for(ws.receive(), timeout=10)
                assert msg.type in (
                    aiohttp.WSMsgType.CLOSE,
                    aiohttp.WSMsgType.CLOSING,
                    aiohttp.WSMsgType.CLOSED,
                ), f"the stale session must be dropped, got {msg.type!r}"
                await ws.close()

        asyncio.run(scenario())

    def test_after_rotation_only_the_new_code_is_accepted(self):
        import asyncio

        import aiohttp

        start_relay(pairing_code=self.OLD, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.OLD}"},
                )
                assert await self._wait_for_phone()
                set_pairing_code(self.NEW)
                await asyncio.wait_for(ws.receive(), timeout=10)
                await ws.close()

        asyncio.run(scenario())

        assert self._handshake_succeeds(self.NEW), "the rotated code must work"
        assert not self._handshake_succeeds(self.OLD), (
            "the leaked code must stop working immediately after rotation"
        )

    def test_re_setting_the_same_code_keeps_the_live_session(self):
        """Re-running android_setup with an unchanged code must not drop a phone."""
        import asyncio

        import aiohttp

        start_relay(pairing_code=self.OLD, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.OLD}"},
                )
                assert await self._wait_for_phone()

                set_pairing_code(self.OLD)
                await asyncio.sleep(0.5)

                assert not ws.closed, "an unchanged code must not drop the session"
                assert is_phone_connected()
                await ws.close()

        asyncio.run(scenario())

    def test_set_pairing_code_without_a_relay_is_still_a_noop(self):
        stop_relay()
        set_pairing_code(self.NEW)  # must not raise
        assert not is_relay_running()


class TestNonAsciiTokenComparison:
    """AUTH-5: a non-ASCII Bearer token must be a counted rejection, not a 500.

    `hmac.compare_digest` refuses to compare `str` values containing non-ASCII
    characters, and neither call site was wrapped — so a garbage `Authorization`
    header produced an unhandled TypeError → aiohttp 500, and because
    `_auth_record_failure` sat on the line *after* the comparison, the attempt was
    never counted against the throttle.
    """

    CODE = "ABC234"
    PORT = 19974

    def test_compare_digest_would_have_raised(self):
        """Pins the bug the helper exists for."""
        import hmac

        with pytest.raises(TypeError):
            hmac.compare_digest("café", self.CODE)

    @pytest.mark.parametrize(
        "token,expected,result",
        [
            ("ABC234", "ABC234", True),
            ("café", "ABC234", False),
            ("ABC234", "café", False),
            ("日本", "日本", True),
            ("日本", "ABC234", False),
            ("", "ABC234", False),
            ("ABC234", "", False),
            ("", "", True),
            ("abc234", "ABC234", False),
            ("\ud800", "ABC234", False),  # lone surrogate: surrogateescape must cope
        ],
    )
    def test_tokens_equal_never_raises(self, token, expected, result):
        from tools.android_relay import _tokens_equal

        assert _tokens_equal(token, expected) is result

    def test_tokens_equal_rejects_non_strings(self):
        from tools.android_relay import _tokens_equal

        assert _tokens_equal(None, self.CODE) is False
        assert _tokens_equal(self.CODE, None) is False
        assert _tokens_equal(123456, self.CODE) is False

    def _handshake_status(self, header_value: str):
        import asyncio

        import aiohttp

        async def attempt():
            async with aiohttp.ClientSession() as session:
                try:
                    ws = await session.ws_connect(
                        f"ws://127.0.0.1:{self.PORT}/ws",
                        headers={"Authorization": header_value},
                    )
                except aiohttp.WSServerHandshakeError as exc:
                    return exc.status
                await ws.close()
                return None

        return asyncio.run(attempt())

    def test_ws_rejects_a_non_ascii_token_with_403_not_500(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)

        assert self._handshake_status("Bearer café") == 403

    def test_http_rejects_a_non_ascii_token_with_401_not_500(self):
        import requests

        start_relay(pairing_code=self.CODE, port=self.PORT)

        response = requests.get(
            f"http://127.0.0.1:{self.PORT}/ping",
            headers={"Authorization": "Bearer café"},
            timeout=10,
        )

        assert response.status_code == 401

    def test_a_non_ascii_attempt_counts_towards_the_throttle(self):
        """The accounting hole: garbage traffic used to be free."""
        start_relay(pairing_code=self.CODE, port=self.PORT)
        assert not _auth_is_blocked("127.0.0.1")

        for _ in range(_AUTH_MAX_ATTEMPTS):
            assert self._handshake_status("Bearer café") == 403

        assert _auth_is_blocked("127.0.0.1"), (
            "non-ASCII attempts must be recorded like any other failure"
        )

    def test_a_valid_token_still_authenticates(self):
        start_relay(pairing_code=self.CODE, port=self.PORT)

        assert self._handshake_status(f"Bearer {self.CODE}") is None
