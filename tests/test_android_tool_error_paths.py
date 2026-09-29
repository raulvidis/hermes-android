"""Error paths for the android_* tools: unreachable bridge, timeouts, non-200
responses and malformed JSON bodies.

Every tool is a thin wrapper that must convert whatever the transport does into
a JSON string the agent can read, so the contract under test here is narrow:

* it never raises — an exception escaping a tool call aborts the agent turn
  instead of being reported;
* the result is JSON (or a ``MEDIA:`` line on success, never on failure);
* the failure is visible in the result;
* the upstream error text survives, so "permission not granted" is not
  flattened into "something went wrong".

Parametrized over both copies of the toolset: ``tools/`` (standalone dev) and
``hermes-android-plugin/`` (what ships inside hermes-agent). The copies are kept
in step by hand, so a fix applied to one and not the other shows up as a single
failing param.

android_mic_fetch is left out of the non-200 tests below: it is a streaming
download with its own status handling and redaction rule (#99), tested in
test_android_tool_media.py.
"""

import json
import re
import time

import pytest
import requests
import responses

from tests.support import (
    NON_TABLE_TOOLS,
    TOOL_CALLS,
    TOOL_CALL_NAMES,
    add_timeout_error,
    add_transport_error,
    as_json,
    is_error,
)

# Tools whose result is always a JSON document, whatever the bridge did.
JSON_TOOLS = [entry for entry in TOOL_CALLS if entry[0] != "android_mic_fetch"]
JSON_TOOL_NAMES = [entry[0] for entry in JSON_TOOLS]


def _register(rsps, url, **kwargs):
    """Register a GET and a POST mock for one bridge path (or pattern)."""
    rsps.add(responses.GET, url, **kwargs)
    rsps.add(responses.POST, url, **kwargs)


def _http_response(status=200, body="", json_body=None):
    """A requests.Response built in memory — no adapter, no socket."""
    response = requests.Response()
    response.status_code = status
    response.url = "http://bridge.test/x"
    if json_body is not None:
        body = json.dumps(json_body)
        response.headers["Content-Type"] = "application/json"
    response._content = body.encode("utf-8")
    return response


class TestTheTableIsComplete:
    def test_the_error_table_covers_every_registered_tool(self, tool_mod):
        """A new tool must arrive with an error-path test, not without one.

        The table in tests/support.py drives the transport-failure tests below.
        If it drifts from _HANDLERS the new tool silently loses that coverage —
        which is how the leak in TestErrorRedaction stayed hidden for 39 of the
        40 tools.
        """
        assert set(TOOL_CALL_NAMES) | set(NON_TABLE_TOOLS) == set(tool_mod._HANDLERS), (
            "tests/support.py TOOL_CALLS has drifted from _HANDLERS — "
            f"untested: {sorted(set(tool_mod._HANDLERS) - set(TOOL_CALL_NAMES) - set(NON_TABLE_TOOLS))}, "
            f"no longer registered: {sorted(set(TOOL_CALL_NAMES) - set(tool_mod._HANDLERS))}"
        )

    def test_every_table_entry_names_the_path_the_tool_actually_calls(
        self, tool_mod, bridge
    ):
        """The table's declared path must be the one the tool requests.

        Guards the table itself: a stale path would leave a tool "tested" against
        an endpoint it no longer calls.
        """
        rsps, _ = bridge
        _register(rsps, re.compile(r".*"), json={"success": True})

        for name, method, path, call in TOOL_CALLS:
            rsps.calls.reset()
            call(tool_mod)
            requested = [(c.request.method, c.request.url) for c in rsps.calls]
            assert requested, f"{name} made no HTTP request"
            assert any(
                verb == method and url.split("?")[0].rstrip("/").endswith(path)
                for verb, url in requested
            ), f"{name} did not {method} {path}; requested {requested}"


@pytest.mark.parametrize("name,method,path,call", TOOL_CALLS, ids=TOOL_CALL_NAMES)
class TestTransportFailures:
    """The bridge being unreachable or slow is the common case in the field:
    the relay is down, the phone dropped its WebSocket, the LAN changed."""

    def test_connection_error_is_reported_not_raised(
        self, tool_mod, bridge, name, method, path, call
    ):
        rsps, _ = bridge
        add_transport_error(rsps)

        result = call(tool_mod)

        assert is_error(result), f"{name} did not report the failure: {result[:200]}"
        assert as_json(result) is not None, f"{name} returned non-JSON on failure: {result[:200]}"

    def test_read_timeout_is_reported_not_raised(
        self, tool_mod, bridge, name, method, path, call
    ):
        rsps, _ = bridge
        add_timeout_error(rsps)

        result = call(tool_mod)

        assert is_error(result), f"{name} did not report the timeout: {result[:200]}"

    def test_unreachable_and_timeout_are_reported_the_same_way(
        self, tool_mod, bridge, name, method, path, call
    ):
        """Both mean 'the bridge did not answer'; the agent should not have to
        distinguish them, so both must land in the same keys."""
        rsps, _ = bridge
        add_transport_error(rsps)
        unreachable = as_json(call(tool_mod))

        rsps.reset()
        add_timeout_error(rsps)
        timed_out = as_json(call(tool_mod))

        assert set(unreachable) == set(timed_out), (
            f"{name} reports an unreachable bridge as {sorted(unreachable)} "
            f"but a timeout as {sorted(timed_out)}"
        )

    def test_no_temp_file_is_left_behind(self, tool_mod, bridge, name, method, path, call):
        """The three MEDIA tools create a temp file only once the bytes are in
        hand; a failed transfer must not litter the temp dir."""
        from tests.support import collect_media_paths, remove_paths

        rsps, _ = bridge
        add_transport_error(rsps)

        result = call(tool_mod)

        leftovers = collect_media_paths([result])
        remove_paths(leftovers)
        assert not leftovers, f"{name} advertised a MEDIA file after a failed transfer"


@pytest.mark.parametrize("name,method,path,call", JSON_TOOLS, ids=JSON_TOOL_NAMES)
class TestNon200Responses:
    def test_json_error_body_survives(self, tool_mod, bridge, name, method, path, call):
        """A 4xx/5xx carrying {"error": ...} must reach the agent intact —
        'SEND_SMS permission not granted' is actionable, 'HTTP 403' is not."""
        rsps, _ = bridge
        _register(rsps, re.compile(r".*"), json={"error": "permission not granted"}, status=403)

        result = call(tool_mod)

        assert is_error(result), f"{name}: {result[:200]}"
        assert "permission not granted" in result, f"{name} lost the upstream error: {result[:200]}"

    def test_non_json_error_body_survives(self, tool_mod, bridge, name, method, path, call):
        """A reverse proxy in front of the relay answers 502 with HTML."""
        rsps, _ = bridge
        _register(rsps, re.compile(r".*"), body="Bad Gateway", status=502)

        result = call(tool_mod)

        assert is_error(result), f"{name}: {result[:200]}"
        assert "Bad Gateway" in result, f"{name} lost the upstream body: {result[:200]}"

    def test_error_body_without_an_error_key_is_wrapped(
        self, tool_mod, bridge, name, method, path, call
    ):
        rsps, _ = bridge
        _register(rsps, re.compile(r".*"), json={"detail": "boom"}, status=500)

        result = call(tool_mod)

        assert is_error(result), f"{name}: {result[:200]}"
        assert "boom" in result, f"{name} lost the upstream detail: {result[:200]}"

    def test_error_body_that_is_a_json_list_is_wrapped(
        self, tool_mod, bridge, name, method, path, call
    ):
        rsps, _ = bridge
        _register(rsps, re.compile(r".*"), json=["unexpected", "list"], status=500)

        result = as_json(call(tool_mod))

        assert is_error(json.dumps(result)), f"{name}: {result}"
        assert "unexpected" in json.dumps(result), f"{name} lost the upstream list: {result}"


class TestExtractResponse:
    """_extract_response is where the JSON/non-JSON and 2xx/5xx decisions are made."""

    def test_ok_json_passes_through(self, tool_mod):
        response = _http_response(json_body={"success": True, "count": 3})
        assert tool_mod._extract_response(response) == {"success": True, "count": 3}

    def test_ok_with_a_non_json_body_becomes_an_error_dict(self, tool_mod):
        response = _http_response(status=200, body="<html>gateway</html>")
        assert tool_mod._extract_response(response) == {"error": "<html>gateway</html>"}

    def test_ok_with_an_empty_body_falls_back_to_the_status(self, tool_mod):
        response = _http_response(status=200, body="")
        assert tool_mod._extract_response(response) == {"error": "HTTP 200"}

    def test_http_error_raises_with_the_body_serialised(self, tool_mod):
        response = _http_response(status=403, json_body={"error": "denied"})
        with pytest.raises(requests.exceptions.HTTPError) as excinfo:
            tool_mod._extract_response(response)
        assert json.loads(str(excinfo.value)) == {"error": "denied"}
        assert excinfo.value.response is response

    def test_http_error_without_an_error_key_is_wrapped(self, tool_mod):
        response = _http_response(status=500, json_body={"detail": "boom"})
        with pytest.raises(requests.exceptions.HTTPError) as excinfo:
            tool_mod._extract_response(response)
        assert json.loads(str(excinfo.value)) == {"error": {"detail": "boom"}}

    def test_http_error_with_a_list_body_is_wrapped(self, tool_mod):
        response = _http_response(status=500, json_body=["a", "b"])
        with pytest.raises(requests.exceptions.HTTPError) as excinfo:
            tool_mod._extract_response(response)
        assert json.loads(str(excinfo.value)) == {"error": ["a", "b"]}

    def test_http_error_with_a_non_json_body_uses_the_text(self, tool_mod):
        response = _http_response(status=503, body="Service Unavailable")
        with pytest.raises(requests.exceptions.HTTPError) as excinfo:
            tool_mod._extract_response(response)
        assert json.loads(str(excinfo.value)) == {"error": "Service Unavailable"}

    def test_http_error_with_an_empty_body_uses_the_status(self, tool_mod):
        response = _http_response(status=500, body="")
        with pytest.raises(requests.exceptions.HTTPError) as excinfo:
            tool_mod._extract_response(response)
        assert json.loads(str(excinfo.value)) == {"error": "HTTP 500"}


class TestMalformedJsonOnASuccessStatus:
    """A 200 whose body is not JSON is what a proxy or a half-started bridge returns."""

    def test_read_screen_reports_the_error(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/screen", body="<html>502</html>", status=200)

        assert as_json(tool_mod.android_read_screen()) == {"error": "<html>502</html>"}

    def test_empty_body_reports_the_status(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/apps", body="", status=200)

        assert as_json(tool_mod.android_get_apps()) == {"error": "HTTP 200"}

    def test_screenshot_does_not_write_a_media_file(self, tool_mod, bridge):
        """The MEDIA: contract is 'this path holds a real image'; a gateway error
        page must not be handed to the user as one."""
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/screenshot", body="<html>502</html>", status=200)

        result = tool_mod.android_screenshot()

        assert "MEDIA:" not in result
        assert as_json(result) == {"error": "<html>502</html>"}

    @pytest.mark.xfail(
        strict=True,
        reason=(
            "BUG: android_ping reports status 'ok' for a 200 whose body is not "
            "JSON. _extract_response turns the undecodable body into "
            '{"error": "<raw text>"} and hands it back as success data, so ping '
            "answers {'status': 'ok', 'bridge': {'error': ...}}. Agents read "
            "status=='ok' as 'bridge is healthy' — exactly the wrong signal when "
            "the relay is returning a proxy error page."
        ),
    )
    def test_ping_does_not_claim_ok_when_the_body_is_not_json(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/ping", body="<html>502 Bad Gateway</html>", status=200)

        result = as_json(tool_mod.android_ping())

        assert result["status"] == "error", f"ping called a non-JSON 200 healthy: {result}"


class TestCheckRequirements:
    """_check_requirements is the plugin's check_fn: False hides every android_*
    tool from the agent, so a wrong answer here is invisible breakage."""

    def test_true_when_the_phone_is_connected(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/ping", json={"phone_connected": True})
        assert tool_mod._check_requirements() is True

    def test_true_when_only_the_accessibility_service_reports(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/ping", json={"accessibilityService": True})
        assert tool_mod._check_requirements() is True

    def test_false_when_the_bridge_is_up_but_no_phone(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(
            responses.GET,
            f"{url}/ping",
            json={"phone_connected": False, "accessibilityService": False},
        )
        assert tool_mod._check_requirements() is False

    def test_false_when_the_ping_body_has_neither_flag(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/ping", json={"status": "ok"})
        assert tool_mod._check_requirements() is False

    def test_false_on_a_non_200(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/ping", json={"error": "no phone"}, status=503)
        assert tool_mod._check_requirements() is False

    def test_false_on_malformed_json(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/ping", body="not json at all", status=200)
        assert tool_mod._check_requirements() is False

    def test_false_when_the_bridge_is_unreachable(self, tool_mod, bridge):
        rsps, _ = bridge
        add_transport_error(rsps)
        assert tool_mod._check_requirements() is False

    def test_false_when_the_bridge_times_out(self, tool_mod, bridge):
        rsps, _ = bridge
        add_timeout_error(rsps)
        assert tool_mod._check_requirements() is False

    def test_sends_the_configured_bearer_token(self, tool_mod, bridge, monkeypatch):
        """This check runs before any tool call, so it is the first thing that has
        to authenticate — including with a token that came from the .env fallback."""
        rsps, url = bridge
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "PAIRCODE")
        rsps.add(responses.GET, f"{url}/ping", json={"phone_connected": True})

        assert tool_mod._check_requirements() is True
        assert rsps.calls[0].request.headers["Authorization"] == "Bearer PAIRCODE"

    def test_uses_its_own_short_timeout(self, tool_mod, bridge, monkeypatch):
        """ANDROID_BRIDGE_TIMEOUT is deliberately ignored here: the check gates
        tool visibility, so it must fail fast rather than inherit a 60s setting."""
        rsps, url = bridge
        monkeypatch.setenv("ANDROID_BRIDGE_TIMEOUT", "60")
        rsps.add(responses.GET, f"{url}/ping", json={"phone_connected": True})

        seen = {}
        real_get = requests.get

        def spy(target, **kwargs):
            seen["timeout"] = kwargs.get("timeout")
            return real_get(target, **kwargs)

        monkeypatch.setattr(requests, "get", spy)

        tool_mod._check_requirements()

        assert seen["timeout"] == 2


class TestArgumentValidation:
    """Bad arguments must be rejected without a bridge round-trip."""

    def test_tap_without_coordinates_or_node(self, tool_mod, bridge):
        rsps, _ = bridge
        add_transport_error(rsps)

        result = as_json(tool_mod.android_tap())

        assert result == {"error": "Provide either (x, y) or node_id"}
        assert not rsps.calls, "validation must not reach the network"

    def test_tap_accepts_zero_coordinates(self, tool_mod, bridge):
        """(0, 0) is a legal tap target; only None means 'not given'."""
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/tap", json={"success": True})

        tool_mod.android_tap(0, 0)

        assert json.loads(rsps.calls[0].request.body) == {"x": 0, "y": 0}

    def test_tap_prefers_node_id_over_coordinates(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/tap", json={"success": True})

        tool_mod.android_tap(10, 20, node_id="n7")

        assert json.loads(rsps.calls[0].request.body) == {"nodeId": "n7"}

    def test_long_press_without_coordinates_or_node(self, tool_mod, bridge):
        rsps, _ = bridge
        add_transport_error(rsps)

        assert as_json(tool_mod.android_long_press()) == {
            "error": "Provide either (x, y) or node_id"
        }
        assert not rsps.calls

    def test_long_press_keeps_a_custom_duration(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/long_press", json={"success": True})

        tool_mod.android_long_press(node_id="n1", duration=1500)

        assert json.loads(rsps.calls[0].request.body) == {"nodeId": "n1", "duration": 1500}

    @pytest.mark.parametrize(
        "duration",
        [-1, 1801, 1.5, "5", None, True, False],
        ids=["negative", "above_cap", "float", "string", "none", "true", "false"],
    )
    def test_mic_record_rejects_invalid_durations_without_a_request(
        self, tool_mod, bridge, duration
    ):
        """duration is a model-supplied argument, so bool and str both turn up."""
        rsps, _ = bridge
        add_transport_error(rsps)

        result = as_json(tool_mod.android_mic_record(duration))

        assert result == {"error": "duration must be between 0 and 1800 seconds"}
        assert not rsps.calls

    @pytest.mark.parametrize("duration", [0, 1, 1800], ids=["zero", "one", "cap"])
    def test_mic_record_accepts_the_whole_valid_range(self, tool_mod, bridge, duration):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/mic_start", json={"status": "starting"})

        assert as_json(tool_mod.android_mic_record(duration)) == {"status": "starting"}
        assert json.loads(rsps.calls[0].request.body) == {"duration": duration}

    def test_wait_sends_only_the_selectors_it_was_given(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/wait", json={"found": True})

        tool_mod.android_wait(class_name="android.widget.Button", timeout_ms=1200)

        assert json.loads(rsps.calls[0].request.body) == {
            "className": "android.widget.Button",
            "timeoutMs": 1200,
        }

    def test_wait_with_no_selector_still_polls(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/wait", json={"found": True})

        tool_mod.android_wait()

        assert json.loads(rsps.calls[0].request.body) == {"timeoutMs": 5000}

    def test_find_nodes_sends_clickable_false(self, tool_mod, bridge):
        """clickable=False is a real filter; only None means 'do not filter'."""
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/find_nodes", json={"nodes": []})

        tool_mod.android_find_nodes(
            class_name="android.widget.TextView", clickable=False, limit=3
        )

        assert json.loads(rsps.calls[0].request.body) == {
            "className": "android.widget.TextView",
            "clickable": False,
            "limit": 3,
        }

    def test_send_intent_forwards_every_optional_field(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/intent", json={"success": True})

        tool_mod.android_send_intent(
            "android.intent.action.VIEW",
            data_uri="https://example.com",
            extras={"key": "value"},
            package="com.example.app",
        )

        assert json.loads(rsps.calls[0].request.body) == {
            "action": "android.intent.action.VIEW",
            "dataUri": "https://example.com",
            "extras": {"key": "value"},
            "packageOverride": "com.example.app",
        }

    def test_send_intent_omits_unset_optional_fields(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/intent", json={"success": True})

        tool_mod.android_send_intent("android.settings.WIFI_SETTINGS")

        assert json.loads(rsps.calls[0].request.body) == {
            "action": "android.settings.WIFI_SETTINGS"
        }

    def test_broadcast_forwards_extras(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/broadcast", json={"success": True})

        tool_mod.android_broadcast("com.example.PING", extras={"n": 1})

        assert json.loads(rsps.calls[0].request.body) == {
            "action": "com.example.PING",
            "extras": {"n": 1},
        }

    def test_speak_flush_maps_onto_the_queue_flag(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/speak", json={"success": True})

        tool_mod.android_speak("hello", flush=True)
        tool_mod.android_speak("hello", flush=False)

        assert json.loads(rsps.calls[0].request.body)["queue"] == 0
        assert json.loads(rsps.calls[1].request.body)["queue"] == 1

    def test_scroll_sends_node_id_only_when_given(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/scroll", json={"success": True})

        tool_mod.android_scroll("up")
        tool_mod.android_scroll("up", node_id="n3")

        assert json.loads(rsps.calls[0].request.body) == {"direction": "up"}
        assert json.loads(rsps.calls[1].request.body) == {"direction": "up", "nodeId": "n3"}

    def test_type_forwards_clear_first(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/type", json={"success": True})

        tool_mod.android_type("abc", clear_first=True)

        assert json.loads(rsps.calls[0].request.body) == {"text": "abc", "clearFirst": True}

    def test_read_screen_passes_both_flags_as_lowercase_strings(self, tool_mod, bridge):
        """The Kotlin side compares against the literal "true", so Python's
        True/False repr would silently disable both options."""
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/screen", json={"tree": [], "count": 0})

        tool_mod.android_read_screen(include_bounds=True, include_system_ui=True)

        assert rsps.calls[0].request.url.endswith("/screen?bounds=true&system_ui=true")

    def test_notifications_include_removed_is_opt_in(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/notifications", json={"notifications": []})

        tool_mod.android_notifications(limit=7, since=123)
        tool_mod.android_notifications(limit=7, since=123, include_removed=True)

        assert "include_removed" not in rsps.calls[0].request.url
        assert rsps.calls[1].request.url.endswith("&include_removed=true")


class TestMacro:
    """android_macro dispatches through _HANDLERS, so it inherits every other
    tool's failure mode and has to stop the sequence at the first one."""

    @pytest.fixture(autouse=True)
    def no_sleeping(self, monkeypatch):
        """The macro paces itself at 0.5s per step; tests must not wait for it."""
        self.sleeps = []
        monkeypatch.setattr(time, "sleep", lambda seconds: self.sleeps.append(seconds))

    def test_runs_every_step_in_order(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/tap", json={"success": True})
        rsps.add(responses.POST, f"{url}/type", json={"success": True})

        result = as_json(
            tool_mod.android_macro(
                [
                    {"tool": "android_tap", "args": {"x": 1, "y": 2}},
                    {"tool": "android_type", "args": {"text": "hi"}},
                ],
                name="two-steps",
            )
        )

        assert result["success"] is True
        assert result["name"] == "two-steps"
        assert result["completed"] == 2
        assert [entry["tool"] for entry in result["results"]] == [
            "android_tap",
            "android_type",
        ]
        assert self.sleeps == [0.5, 0.5]

    def test_stops_when_a_step_reports_success_false(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(
            responses.POST,
            f"{url}/tap",
            json={"success": False, "error": "node not clickable"},
        )
        rsps.add(responses.POST, f"{url}/type", json={"success": True})

        result = as_json(
            tool_mod.android_macro(
                [
                    {"tool": "android_tap", "args": {"x": 1, "y": 2}},
                    {"tool": "android_type", "args": {"text": "hi"}},
                ]
            )
        )

        assert result["error"] == "Step 0 (android_tap) failed"
        assert result["completed"] == 0
        assert len(rsps.calls) == 1, "the macro ran on past a failed step"

    def test_stops_when_a_step_hits_a_transport_error(self, tool_mod, bridge):
        """Regression: a result keyed "error" with no "success" used to be read
        as success, so a macro continued past a dead bridge."""
        rsps, _ = bridge
        add_transport_error(rsps)

        result = as_json(
            tool_mod.android_macro(
                [
                    {"tool": "android_tap", "args": {"x": 1, "y": 2}},
                    {"tool": "android_type", "args": {"text": "hi"}},
                ]
            )
        )

        assert result["error"] == "Step 0 (android_tap) failed"
        assert "error" in result["results"][0]["result"]

    def test_unknown_tool_stops_the_sequence(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/tap", json={"success": True})

        result = as_json(
            tool_mod.android_macro(
                [
                    {"tool": "android_tap", "args": {"x": 1, "y": 2}},
                    {"tool": "android_not_a_tool", "args": {}},
                ]
            )
        )

        assert result["error"] == "Step 1: unknown tool 'android_not_a_tool'"
        assert result["completed"] == 1
        assert len(rsps.calls) == 1

    def test_step_missing_the_tool_key_is_reported_as_unknown(self, tool_mod):
        result = as_json(tool_mod.android_macro([{"args": {}}]))
        assert result["error"] == "Step 0: unknown tool ''"

    def test_step_without_args_defaults_to_an_empty_dict(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/ping", json={"status": "ok"})

        result = as_json(tool_mod.android_macro([{"tool": "android_ping"}]))

        assert result["completed"] == 1

    def test_handler_type_error_is_reported_not_raised(self, tool_mod):
        """An argument the tool does not accept is a model mistake; it must come
        back as a readable step failure rather than an exception."""
        result = as_json(
            tool_mod.android_macro([{"tool": "android_tap", "args": {"bogus": 1}}])
        )

        assert result["error"].startswith("Step 0 (android_tap) raised:")
        assert "unexpected keyword argument" in result["error"]
        assert result["completed"] == 0

    def test_empty_step_list_succeeds(self, tool_mod):
        assert as_json(tool_mod.android_macro([])) == {
            "success": True,
            "name": "unnamed",
            "completed": 0,
            "results": [],
        }

    def test_a_non_string_step_result_is_kept(self, tool_mod, monkeypatch):
        """Handlers may return an already-decoded dict instead of a JSON string."""
        monkeypatch.setitem(
            tool_mod._HANDLERS, "android_ping", lambda args, **kw: {"success": True}
        )

        result = as_json(tool_mod.android_macro([{"tool": "android_ping", "args": {}}]))

        assert result["results"][0]["result"] == {"success": True}

    @pytest.mark.xfail(
        strict=True,
        reason=(
            "BUG: android_macro raises instead of returning a JSON error when a "
            "step is not a dict. The loop reads step.get('tool') *outside* the "
            "try/except that turns handler failures into "
            '{"error": "Step N ... raised: ..."}, so a model that sends '
            '["android_tap"] or [null] gets an AttributeError out of the tool '
            "call — an unhandled exception in the agent turn instead of a "
            "readable step failure. android_macro(None) raises TypeError the "
            "same way."
        ),
    )
    def test_a_malformed_step_does_not_escape_as_an_exception(self, tool_mod):
        result = tool_mod.android_macro(["android_tap"])
        assert is_error(result), f"macro raised or returned {result!r} for a malformed step"


class TestErrorRedaction:
    """AGENTS.md: never expose server IPs outside the task, and strip PII from
    tool responses. android_mic_fetch has done this since #99; these tests hold
    every other tool to the same rule."""

    def test_mic_fetch_redacts_the_bridge_address(self, tool_mod, bridge, monkeypatch):
        """The behaviour that already works, pinned for both copies."""
        rsps, _ = bridge
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://203.0.113.7:8766")
        add_transport_error(rsps)

        result = as_json(tool_mod.android_mic_fetch())

        assert result == {"error": "Could not download microphone recording from the bridge"}
        assert "203.0.113.7" not in result["error"]
        assert "8766" not in result["error"]

    def test_no_tool_leaks_the_bridge_address_or_its_own_arguments(
        self, tool_mod, bridge, monkeypatch
    ):
        rsps, _ = bridge
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://203.0.113.7:8766")
        add_transport_error(rsps)

        leaking = {}
        for name, _method, _path, call in TOOL_CALLS:
            result = call(tool_mod)
            found = [
                needle
                for needle in ("203.0.113.7", "8766", "Jane%20Doe", "Jane Doe")
                if needle in result
            ]
            if found:
                leaking[name] = found

        assert not leaking, f"tools leaking connection details: {sorted(leaking)}"

    def test_a_timeout_is_still_reported_as_a_failure(self, tool_mod, bridge, monkeypatch):
        """The weak half of the leak test above, kept passing on purpose: whatever
        the wording, a timeout must be visible as an error."""
        rsps, _ = bridge
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://203.0.113.7:8766")
        add_timeout_error(rsps)

        result = as_json(tool_mod.android_ping())

        assert result["status"] == "error"
        assert "timed out" in result["message"].lower()

    def test_the_pairing_token_never_appears_in_a_response(self, tool_mod, bridge, monkeypatch):
        rsps, _ = bridge
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "SECRET-PAIRING-CODE")
        add_transport_error(rsps)

        for name, _method, _path, call in TOOL_CALLS:
            result = call(tool_mod)
            assert "SECRET-PAIRING-CODE" not in result, f"{name} echoed the pairing token"

    def test_the_pairing_token_never_appears_in_a_request_url(
        self, tool_mod, bridge, monkeypatch
    ):
        """The relay rejects ?token= for exactly this reason (SECURITY.md): query
        strings are logged verbatim by reverse proxies."""
        rsps, _ = bridge
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "SECRET-PAIRING-CODE")
        _register(rsps, re.compile(r".*"), json={"success": True})

        for _name, _method, _path, call in TOOL_CALLS:
            call(tool_mod)

        for record in rsps.calls:
            assert "SECRET-PAIRING-CODE" not in record.request.url
            assert "token=" not in record.request.url.lower()

    def test_every_request_carries_the_bearer_header(self, tool_mod, bridge, monkeypatch):
        rsps, _ = bridge
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "PAIRCODE")
        _register(rsps, re.compile(r".*"), json={"success": True})

        for name, _method, _path, call in TOOL_CALLS:
            rsps.calls.reset()
            call(tool_mod)
            assert rsps.calls, f"{name} made no request"
            for record in rsps.calls:
                assert record.request.headers.get("Authorization") == "Bearer PAIRCODE", (
                    f"{name} called {record.request.url} unauthenticated"
                )

    def test_no_authorization_header_when_no_token_is_configured(
        self, tool_mod, bridge, monkeypatch
    ):
        """No token means no header at all — not 'Bearer None', which the relay
        would count as a failed pairing attempt against the rate limit."""
        rsps, _ = bridge
        monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
        _register(rsps, re.compile(r".*"), json={"success": True})

        tool_mod.android_ping()

        assert "Authorization" not in rsps.calls[0].request.headers


class TestTransportErrorMessages:
    """The wording an agent sees decides whether it retries, re-pairs or gives
    up, so the interesting shapes are pinned rather than left to str(exception)."""

    def test_connection_error_message_is_a_safe_generic_reason(self, tool_mod, bridge):
        """Not str(exception): requests' own text carries the bridge host:port and
        the request URL, so the wording is pinned to a reason that carries
        neither (AGENTS.md — no server IPs, no PII in tool responses)."""
        rsps, _ = bridge
        add_transport_error(rsps, message="connection refused by the bridge")

        assert as_json(tool_mod.android_get_apps()) == {
            "error": "ConnectionError: bridge unreachable (connection refused)"
        }

    def test_timeout_message_is_a_safe_generic_reason(self, tool_mod, bridge):
        """The timeout wording is pinned too: it must still say "timed out", which
        is what tells an agent the bridge is slow rather than absent."""
        rsps, _ = bridge
        add_timeout_error(rsps)

        assert as_json(tool_mod.android_get_apps()) == {
            "error": "ReadTimeout: bridge request timed out"
        }

    def test_http_error_message_is_the_serialised_body(self, tool_mod, bridge):
        """Pinned as-is, wart included: the body arrives double-encoded (a JSON
        document inside a JSON string) because _extract_response raises
        HTTPError(json.dumps(body)) and the caller does str(exception). Changing
        that is a deliberate act, and this test is what makes it visible."""
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/apps", json={"error": "not paired"}, status=401)

        assert as_json(tool_mod.android_get_apps()) == {"error": '{"error": "not paired"}'}

    def test_ping_wraps_the_failure_under_message(self, tool_mod, bridge):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/ping", json={"error": "not paired"}, status=401)

        result = as_json(tool_mod.android_ping())

        assert result["status"] == "error"
        assert json.loads(result["message"]) == {"error": "not paired"}
