"""PII handling: what a tool sends to the phone, what it returns to the agent,
and what the relay writes to its log.

AGENTS.md keeps PII (phone numbers, recipients, location) out of tool responses
and logs; docs/manual-tests.md items 15-17 turn that into three checks a
reviewer can run by hand. These are the automated equivalents:

* the phone still receives the real values — redaction that also broke the
  action would be a silent regression;
* the tool's response is the bridge's payload, nothing added: no echoed
  recipient, no echoed SMS body, no echoed contact query;
* the relay's log line for a forwarded command carries ``<redacted>`` in every
  PII-bearing field;
* the pairing token appears nowhere, in any log, at any level.

Both copies of the toolset are exercised.
"""

import asyncio
import json
import logging
from urllib.parse import parse_qs, urlsplit

import pytest
import responses

from tests.support import (
    FakePhoneSocket,
    FakeRequest,
    as_json,
    make_relay_state,
    response_json,
)

# (tool, bridge path, call(module), request body the phone must receive,
#  values that must not come back in the response)
PII_CALLS = [
    (
        "android_send_sms",
        "/send_sms",
        lambda m: m.android_send_sms("+15551234567", "at the clinic again"),
        {"to": "+15551234567", "body": "at the clinic again"},
        ["+15551234567", "15551234567", "at the clinic again"],
    ),
    (
        "android_call",
        "/call",
        lambda m: m.android_call("+15551234567"),
        {"number": "+15551234567"},
        ["+15551234567", "15551234567"],
    ),
    (
        "android_search_contacts",
        "/contacts",
        lambda m: m.android_search_contacts("Jane Doe"),
        None,  # GET: the query travels in the URL
        ["Jane Doe", "Jane%20Doe"],
    ),
    (
        "android_type",
        "/type",
        lambda m: m.android_type("hunter2-otp-1234"),
        {"text": "hunter2-otp-1234", "clearFirst": False},
        ["hunter2-otp-1234"],
    ),
    (
        "android_clipboard_write",
        "/clipboard",
        lambda m: m.android_clipboard_write("clipboard-secret"),
        {"text": "clipboard-secret"},
        ["clipboard-secret"],
    ),
    (
        "android_speak",
        "/speak",
        lambda m: m.android_speak("say this aloud"),
        {"text": "say this aloud", "queue": 1},
        ["say this aloud"],
    ),
    (
        "android_send_intent",
        "/intent",
        lambda m: m.android_send_intent(
            "android.intent.action.VIEW", data_uri="https://maps.example/?q=home"
        ),
        {"action": "android.intent.action.VIEW", "dataUri": "https://maps.example/?q=home"},
        ["https://maps.example/?q=home", "q=home"],
    ),
    (
        "android_broadcast",
        "/broadcast",
        lambda m: m.android_broadcast("com.example.PING", extras={"otp": "123456"}),
        {"action": "com.example.PING", "extras": {"otp": "123456"}},
        ["123456"],
    ),
]

PII_IDS = [entry[0] for entry in PII_CALLS]


class TestToolResponsesDoNotEchoTheirArguments:
    """The bridge already strips PII from its own replies ("SMS sent", not
    "SMS sent to +1555..."); these tests make sure the Python side does not
    put it back by echoing what it was asked to do."""

    @pytest.mark.parametrize("tool,path,call,sent,must_not_appear", PII_CALLS, ids=PII_IDS)
    def test_the_response_is_the_bridge_payload_verbatim(
        self, tool_mod, bridge, tool, path, call, sent, must_not_appear
    ):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}{path}", json={"success": True, "message": "ok"})
        rsps.add(responses.POST, f"{url}{path}", json={"success": True, "message": "ok"})

        result = call(tool_mod)

        assert as_json(result) == {"success": True, "message": "ok"}, f"{tool}: {result}"
        for value in must_not_appear:
            assert value not in result, f"{tool} echoed {value!r} into its response"

    @pytest.mark.parametrize("tool,path,call,sent,must_not_appear", PII_CALLS, ids=PII_IDS)
    def test_the_phone_still_receives_the_real_values(
        self, tool_mod, bridge, tool, path, call, sent, must_not_appear
    ):
        """Redaction must never reach the wire: the phone needs the number to
        dial and the text to type."""
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}{path}", json={"success": True})
        rsps.add(responses.POST, f"{url}{path}", json={"success": True})

        call(tool_mod)

        request = rsps.calls[0].request
        if sent is None:
            # GET: the value travels in the query string, percent-encoded
            query = parse_qs(urlsplit(request.url).query)
            assert query["query"] == ["Jane Doe"]
            assert query["limit"] == ["20"]
        else:
            assert json.loads(request.body) == sent

    @pytest.mark.parametrize("tool,path,call,sent,must_not_appear", PII_CALLS, ids=PII_IDS)
    def test_a_bridge_error_does_not_carry_the_arguments_back(
        self, tool_mod, bridge, tool, path, call, sent, must_not_appear
    ):
        """The failure path builds its message from the exception, so this is
        where an echo would most easily slip in."""
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}{path}", json={"error": "not available"}, status=403)
        rsps.add(responses.POST, f"{url}{path}", json={"error": "not available"}, status=403)

        result = call(tool_mod)

        assert "not available" in result
        for value in must_not_appear:
            assert value not in result, f"{tool} echoed {value!r} into its error"

    def test_location_is_returned_because_that_is_the_tool(self, tool_mod, bridge):
        """android_location exists to hand coordinates to the agent, so the
        response is not the place to redact — the log is (see below)."""
        rsps, url = bridge
        rsps.add(
            responses.GET,
            f"{url}/location",
            json={"success": True, "data": {"latitude": 1.0, "longitude": 2.0}},
        )

        assert as_json(tool_mod.android_location()) == {
            "success": True,
            "data": {"latitude": 1.0, "longitude": 2.0},
        }

    def test_clipboard_read_returns_what_the_phone_sent(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/clipboard", json={"text": "copied-secret"})

        assert as_json(tool_mod.android_clipboard_read()) == {"text": "copied-secret"}


class TestRelayLogRedaction:
    """The relay logs every command it forwards at DEBUG, so this is the one
    place a phone number or an OTP can end up on disk."""

    LOGGER = "android_relay"

    @pytest.fixture
    def captured_command(self, relay_mod, caplog, monkeypatch):
        """Forward one command through _handle_http and return the log text.

        The response wait is shortened so the handler finishes instead of
        blocking for _RESPONSE_TIMEOUT; the command is logged before it waits.
        """
        monkeypatch.setattr(relay_mod, "_RESPONSE_TIMEOUT", 0.01)

        async def run(body):
            socket = FakePhoneSocket()
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE", socket=socket)
            request = FakeRequest(
                method="POST",
                headers={"Authorization": "Bearer PAIRCODE"},
                json_body=body,
            )
            with caplog.at_level(logging.DEBUG, logger=self.LOGGER):
                response = await relay_mod._handle_http(request, state, "/send_sms")
            return response, socket, caplog.text

        return run

    @pytest.mark.parametrize(
        "key",
        sorted(
            {
                "to",
                "number",
                "body",
                "text",
                "message",
                "extras",
                "data",
                "uri",
                "content",
            }
        ),
    )
    def test_every_sensitive_key_is_redacted(self, relay_mod, captured_command, key):
        _, _, log_text = asyncio.run(captured_command({key: "+15551234567"}))

        assert "+15551234567" not in log_text, f"{key} reached the log verbatim"
        assert "<redacted>" in log_text

    def test_the_sensitive_key_list_is_the_one_under_test(self, relay_mod):
        """Pinned so a new PII-bearing field cannot be added to the relay's
        request vocabulary without a matching redaction test."""
        assert relay_mod._SENSITIVE_BODY_KEYS == frozenset(
            {"to", "number", "body", "text", "message", "extras", "data", "uri", "content"}
        )

    def test_non_sensitive_fields_stay_readable(self, relay_mod, captured_command):
        """Redaction that hid everything would make the log useless for the one
        thing it is for: seeing which command was sent."""
        _, _, log_text = asyncio.run(
            captured_command({"x": 540, "y": 960, "nodeId": "n12", "duration": 300})
        )

        assert "540" in log_text
        assert "n12" in log_text
        assert "<redacted>" not in log_text

    def test_an_sms_command_logs_no_number_and_no_body(self, relay_mod, captured_command):
        response, socket, log_text = asyncio.run(
            captured_command({"to": "+15551234567", "body": "at the clinic again"})
        )

        assert response.status == 504  # nothing answered; the log is what matters
        assert socket.sent[0]["body"] == {"to": "+15551234567", "body": "at the clinic again"}
        assert "+15551234567" not in log_text
        assert "clinic" not in log_text
        assert '{"to": "<redacted>", "body": "<redacted>"}' in log_text

    def test_the_path_and_method_stay_in_the_log(self, relay_mod, captured_command):
        _, _, log_text = asyncio.run(captured_command({"text": "secret"}))

        assert "POST /send_sms" in log_text

    def test_a_phone_response_is_never_logged(self, relay_mod, caplog):
        """The reply is where location, contacts and SMS bodies actually live."""
        payload = {
            "request_id": "rid-1",
            "status": 200,
            "result": {
                "contacts": [{"name": "Jane Doe", "numbers": ["+15551234567"]}],
                "latitude": 51.5,
            },
        }

        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            future = state.loop.create_future()
            async with state.pending_lock:
                state.pending["rid-1"] = future
            with caplog.at_level(logging.DEBUG, logger=self.LOGGER):
                await relay_mod._on_phone_message(state, json.dumps(payload))
            return await future

        resolved = asyncio.run(scenario())

        assert resolved == payload
        assert "Jane Doe" not in caplog.text
        assert "+15551234567" not in caplog.text
        assert "51.5" not in caplog.text

    def test_a_long_body_is_truncated_in_the_log(self, relay_mod, captured_command):
        """_safe_body_repr caps at 200 characters; a pasted document must not
        turn the log into a copy of the clipboard.

        The field is deliberately not one of the redacted keys — this measures
        the length cap rather than the redaction list."""
        _, _, log_text = asyncio.run(captured_command({"note": "x" * 5000}))

        logged = [line for line in log_text.splitlines() if ">>>" in line]
        assert logged, "the command was not logged at all"
        command_line = logged[0]
        assert "x" * 500 not in command_line
        assert command_line.count("x") <= 200


class TestTokenIsNeverLogged:
    """The pairing code is full device control (SECURITY.md), and _mask_token
    exists so that not even a prefix can reach a log."""

    LOGGER = "android_relay"

    @pytest.mark.parametrize("token", ["PAIRCODE", "P", "", "a" * 64], ids=["normal", "single", "empty", "long"])
    def test_mask_token_reveals_nothing(self, relay_mod, token):
        masked = relay_mod._mask_token(token)
        assert masked == "****"
        assert not (token and token[:1] in masked)

    def test_a_rejected_http_request_logs_the_mask_not_the_token(self, relay_mod, caplog):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            request = FakeRequest(headers={"Authorization": "Bearer WRONGCODE"})
            with caplog.at_level(logging.DEBUG, logger=self.LOGGER):
                return await relay_mod._handle_http(request, state, "/ping")

        response = asyncio.run(scenario())

        assert response.status == 401
        assert "WRONGCODE" not in caplog.text
        assert "PAIRCODE" not in caplog.text
        assert "****" in caplog.text

    def test_a_rejected_http_request_does_not_leak_into_the_rate_limit_log(
        self, relay_mod, caplog
    ):
        """Five failures block the IP; the block warning must not carry the
        attempted code either."""
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            request = FakeRequest(headers={"Authorization": "Bearer WRONGCODE"})
            with caplog.at_level(logging.DEBUG, logger=self.LOGGER):
                for _ in range(relay_mod._AUTH_MAX_ATTEMPTS):
                    await relay_mod._handle_http(request, state, "/ping")
                blocked = relay_mod._auth_is_blocked(request.remote)
            return blocked

        assert asyncio.run(scenario()) is True
        assert "WRONGCODE" not in caplog.text
        assert "blocked" in caplog.text

    def test_a_blocked_ip_is_refused_before_the_token_is_looked_at(
        self, relay_mod, caplog, monkeypatch
    ):
        async def scenario():
            state = make_relay_state(relay_mod, pairing_code="PAIRCODE")
            monkeypatch.setitem(
                relay_mod._auth_blocked, "203.0.113.9", float("inf")
            )
            request = FakeRequest(headers={"Authorization": "Bearer PAIRCODE"})
            with caplog.at_level(logging.DEBUG, logger=self.LOGGER):
                return await relay_mod._handle_http(request, state, "/ping")

        response = asyncio.run(scenario())

        assert response.status == 429
        assert response_json(response)["error"].startswith("Too many failed")
        assert "PAIRCODE" not in caplog.text
