import pytest
import threading
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
    _decode_stream_frame,
    _cleanup_phone,
    _RelayState,
    _realtime_session_config,
    _realtime_client_status,
    _safe_realtime_error_details,
    _decode_realtime_sdp,
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


class TestRobotPhoneEvents:
    PORT = 19884
    CODE = "ROBOT1"

    def test_allowlisted_event_is_available_to_authenticated_long_poll(self):
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
                await ws.send_json({"event": "robot.talk_requested", "protocol": 1})
                response = await session.get(
                    f"http://127.0.0.1:{self.PORT}/robot/events",
                    params={"after": 0, "timeout": 2},
                    headers=headers,
                )
                assert response.status == 200
                payload = await response.json()
                assert payload["events"] == [
                    {
                        "id": 1,
                        "event": "robot.talk_requested",
                        "receivedAt": payload["events"][0]["receivedAt"],
                    }
                ]
                assert isinstance(payload["events"][0]["receivedAt"], int)

                await ws.send_json({"event": "robot.backend_openai", "protocol": 1})
                backend_response = await session.get(
                    f"http://127.0.0.1:{self.PORT}/robot/events",
                    params={"after": payload["latest"], "timeout": 2},
                    headers=headers,
                )
                assert backend_response.status == 200
                backend_payload = await backend_response.json()
                assert backend_payload["events"][0]["event"] == "robot.backend_openai"

                await ws.send_json(
                    {
                        "event": "robot.realtime_transcript",
                        "protocol": 1,
                        "role": "user",
                        "text": "Hallo live",
                        "itemId": "item_user_1",
                    }
                )
                transcript_response = await session.get(
                    f"http://127.0.0.1:{self.PORT}/robot/events",
                    params={"after": backend_payload["latest"], "timeout": 2},
                    headers=headers,
                )
                transcript_payload = await transcript_response.json()
                transcript_event = transcript_payload["events"][0]
                assert transcript_event["role"] == "user"
                assert transcript_event["text"] == "Hallo live"
                assert transcript_event["itemId"] == "item_user_1"

                empty = await session.get(
                    f"http://127.0.0.1:{self.PORT}/robot/events",
                    params={"after": transcript_payload["latest"]},
                    headers=headers,
                )
                assert (await empty.json())["events"] == []
                await ws.close()

        asyncio.run(scenario())

    def test_unknown_event_is_discarded_and_endpoint_requires_auth(self):
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
                await ws.send_json({"event": "robot.arbitrary_payload", "protocol": 1})
                await ws.send_json(
                    {
                        "event": "robot.realtime_transcript",
                        "protocol": 1,
                        "role": "system",
                        "text": "must be rejected",
                        "itemId": "bad_1",
                    }
                )
                await ws.send_json(
                    {
                        "event": "robot.talk_requested",
                        "protocol": 1,
                        "transcript": "must never enter the event queue",
                    }
                )
                response = await session.get(
                    f"http://127.0.0.1:{self.PORT}/robot/events",
                    headers=headers,
                )
                assert response.status == 200
                assert (await response.json())["events"] == []

                unauthorized = await session.get(
                    f"http://127.0.0.1:{self.PORT}/robot/events"
                )
                assert unauthorized.status == 401
                await ws.close()

        asyncio.run(scenario())


class TestRealtimeSession:
    PORT = 19885
    CODE = "TEST85"

    def test_session_config_is_audio_only_and_transcribed(self):
        config = _realtime_session_config()
        assert config["type"] == "realtime"
        assert config["output_modalities"] == ["audio"]
        assert config["audio"]["input"]["transcription"]["model"]
        assert config["audio"]["input"]["transcription"]["language"] == "de"
        assert "languages" not in config["audio"]["input"]["transcription"]
        assert "delay" not in config["audio"]["input"]["transcription"]
        assert config["audio"]["input"]["turn_detection"]["type"] == "semantic_vad"
        assert "tools" not in config

    def test_session_config_maps_allow_listed_quality_tiers(self, monkeypatch):
        for name in (
            "ROBOT_DIALOG_OPENAI_REALTIME_MODEL_MINI",
            "ROBOT_DIALOG_OPENAI_REALTIME_MODEL_STANDARD",
            "ROBOT_DIALOG_OPENAI_REALTIME_MODEL",
        ):
            monkeypatch.delenv(name, raising=False)
        assert _realtime_session_config("mini")["model"] == "gpt-realtime-2.1-mini"
        assert _realtime_session_config("standard")["model"] == "gpt-realtime-2"
        assert _realtime_session_config("top")["model"] == "gpt-realtime-2.1"

    def test_session_config_rejects_unknown_quality_tier(self):
        with pytest.raises(ValueError):
            _realtime_session_config("arbitrary-model")

    @pytest.mark.parametrize("status", [400, 401, 403, 429])
    def test_safe_actionable_upstream_status_is_preserved(self, status):
        assert _realtime_client_status(status) == status

    @pytest.mark.parametrize("status", [201, 404, 500, 503])
    def test_other_upstream_status_is_collapsed(self, status):
        assert _realtime_client_status(status) == 502

    def test_safe_upstream_error_details_include_only_machine_slugs(self):
        payload = b'{"error":{"type":"invalid_request_error","code":"invalid_value","param":"session.audio.input"}}'
        assert _safe_realtime_error_details(payload) == {
            "upstreamType": "invalid_request_error",
            "upstreamCode": "invalid_value",
            "upstreamParam": "session.audio.input",
        }

    def test_safe_upstream_error_details_reject_messages_and_unsafe_values(self):
        payload = b'{"error":{"type":"bad value with spaces","code":"invalid_value","message":"private provider text"}}'
        assert _safe_realtime_error_details(payload) == {"upstreamCode": "invalid_value"}

    def test_sdp_decoder_preserves_trailing_crlf(self):
        payload = b"v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n"
        assert _decode_realtime_sdp(payload).encode("utf-8") == payload

    @pytest.mark.parametrize("payload", [b"not-sdp", b"v=0\x00bad", b"\xff"])
    def test_sdp_decoder_rejects_invalid_payload(self, payload):
        with pytest.raises((UnicodeDecodeError, ValueError)):
            _decode_realtime_sdp(payload)

    def test_endpoint_requires_relay_auth_and_server_side_key(self, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.delenv("OPENAI_API_KEY", raising=False)
        start_relay(pairing_code=self.CODE, port=self.PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                unauthenticated = await session.post(
                    f"http://127.0.0.1:{self.PORT}/robot/realtime/session",
                    data="v=0\r\n",
                    headers={"Content-Type": "application/sdp"},
                )
                assert unauthenticated.status == 401

                authenticated = await session.post(
                    f"http://127.0.0.1:{self.PORT}/robot/realtime/session",
                    data="v=0\r\n",
                    headers={
                        "Authorization": f"Bearer {self.CODE}",
                        "Content-Type": "application/sdp",
                    },
                )
                assert authenticated.status == 503
                assert "key" not in (await authenticated.text()).lower()

        asyncio.run(scenario())
