import json
import os
import responses
import pytest

# Import tool functions directly (not via registry)
from tools.android_tool import (
    android_ping,
    android_read_screen,
    android_tap,
    android_tap_text,
    android_type,
    android_swipe,
    android_open_app,
    android_press_key,
    android_screenshot,
    android_scroll,
    android_wait,
    android_get_apps,
    android_current_app,
    android_setup,
    android_clipboard_read,
    android_clipboard_write,
    android_notifications,
    android_long_press,
    android_drag,
    android_describe_node,
    android_screen_hash,
    android_macro,
    android_location,
    android_send_sms,
    android_call,
    android_events,
    android_event_stream,
    android_screen_record,
    android_mic_record,
    android_mic_stop,
    android_mic_status,
    android_mic_fetch,
    android_read_widgets,
    android_media,
    android_search_contacts,
    android_send_intent,
    android_broadcast,
    android_speak,
    android_speak_stop,
    android_find_nodes,
    android_diff_screen,
    android_pinch,
    _SCHEMAS,
    _HANDLERS,
)


class TestSchemas:
    def test_all_42_tools_have_schemas(self):
        assert len(_SCHEMAS) == 42

    def test_all_42_tools_have_handlers(self):
        assert len(_HANDLERS) == 42

    def test_schema_names_match_handler_names(self):
        assert set(_SCHEMAS.keys()) == set(_HANDLERS.keys())

    def test_all_schemas_have_required_fields(self):
        for name, schema in _SCHEMAS.items():
            assert "name" in schema, f"{name} missing 'name'"
            assert "description" in schema, f"{name} missing 'description'"
            assert "parameters" in schema, f"{name} missing 'parameters'"


class TestCodeQuality:
    def test_no_unused_relay_imports_in_setup(self):
        """Verify android_setup only imports what it uses from android_relay."""
        import inspect
        import tools.android_tool as mod

        source = inspect.getsource(mod.android_setup)
        # is_relay_running was imported but never used — should not appear
        assert "is_relay_running" not in source, (
            "is_relay_running is imported but unused in android_setup"
        )
        # These should be present (used functions)
        assert "start_relay" in source
        assert "is_phone_connected" in source

    def test_plugin_copy_registers_all_tools_without_device_specific_values(self):
        import runpy
        from pathlib import Path

        plugin_path = Path(__file__).parents[1] / "hermes-android-plugin" / "android_tool.py"
        plugin_source = plugin_path.read_text(encoding="utf-8")
        plugin = runpy.run_path(str(plugin_path))

        assert len(plugin["_SCHEMAS"]) == 42
        assert set(plugin["_SCHEMAS"]) == set(plugin["_HANDLERS"])
        assert ("scp " + "-P") not in plugin_source


class TestPing:
    @responses.activate
    def test_ping_success(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/ping",
            json={"status": "ok", "accessibilityService": True, "version": "0.1.0"},
        )
        result = json.loads(android_ping())
        assert result["status"] == "ok"
        assert result["bridge"]["accessibilityService"] is True

    @responses.activate
    def test_ping_failure(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/ping",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_ping())
        assert result["status"] == "error"


class TestReadScreen:
    @responses.activate
    def test_read_screen(self, bridge_url):
        tree = [{"nodeId": "n1", "text": "Hello", "clickable": True}]
        responses.add(
            responses.GET,
            f"{bridge_url}/screen",
            json={"tree": tree, "count": 1},
        )
        result = json.loads(android_read_screen())
        assert result["tree"][0]["text"] == "Hello"

    @responses.activate
    def test_read_screen_with_bounds(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/screen",
            json={"tree": [], "count": 0},
        )
        result = json.loads(android_read_screen(include_bounds=True))
        assert "tree" in result

    @responses.activate
    def test_read_screen_filters_system_ui_by_default(self, bridge_url):
        # Issue #34: System UI must be excluded by default for token efficiency.
        responses.add(
            responses.GET,
            f"{bridge_url}/screen",
            json={"tree": [], "count": 0},
        )
        json.loads(android_read_screen())
        assert "system_ui=false" in responses.calls[0].request.url

    @responses.activate
    def test_read_screen_includes_system_ui_when_requested(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/screen",
            json={"tree": [], "count": 0},
        )
        json.loads(android_read_screen(include_system_ui=True))
        assert "system_ui=true" in responses.calls[0].request.url


class TestTap:
    @responses.activate
    def test_tap_by_coordinates(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/tap",
            json={"success": True, "message": "Tapped (100, 200)"},
        )
        result = json.loads(android_tap(x=100, y=200))
        assert result["success"] is True

    @responses.activate
    def test_tap_by_node_id(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/tap",
            json={"success": True, "message": "Tapped node n1"},
        )
        result = json.loads(android_tap(node_id="n1"))
        assert result["success"] is True

    def test_tap_no_args(self):
        result = json.loads(android_tap())
        assert "error" in result


class TestTapText:
    @responses.activate
    def test_tap_text(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/tap_text",
            json={"success": True, "message": "Tapped 'Continue'"},
        )
        result = json.loads(android_tap_text("Continue"))
        assert result["success"] is True

    @responses.activate
    def test_tap_text_exact(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/tap_text",
            json={"success": True},
        )
        result = json.loads(android_tap_text("OK", exact=True))
        assert result["success"] is True


class TestType:
    @responses.activate
    def test_type_text(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/type",
            json={"success": True, "message": "Typed text"},
        )
        result = json.loads(android_type("hello world"))
        assert result["success"] is True

    @responses.activate
    def test_type_clear_first(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/type",
            json={"success": True},
        )
        result = json.loads(android_type("new text", clear_first=True))
        assert result["success"] is True


class TestSwipe:
    @responses.activate
    def test_swipe(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/swipe",
            json={"success": True, "message": "Swiped up (medium)"},
        )
        result = json.loads(android_swipe("up"))
        assert result["success"] is True

    @responses.activate
    def test_swipe_long(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/swipe",
            json={"success": True},
        )
        result = json.loads(android_swipe("down", distance="long"))
        assert result["success"] is True


class TestOpenApp:
    @responses.activate
    def test_open_app(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/open_app",
            json={"success": True, "message": "Opening com.ubercab"},
        )
        result = json.loads(android_open_app("com.ubercab"))
        assert result["success"] is True


class TestPressKey:
    @responses.activate
    def test_press_key(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/press_key",
            json={"success": True, "message": "Pressed back"},
        )
        result = json.loads(android_press_key("back"))
        assert result["success"] is True


class TestScreenshot:
    @responses.activate
    def test_screenshot(self, bridge_url):
        import base64

        valid_png = base64.b64encode(b"\x89PNG\r\n\x1a\n").decode()
        responses.add(
            responses.GET,
            f"{bridge_url}/screenshot",
            json={"image": valid_png, "width": 1080, "height": 1920},
        )
        result = android_screenshot()
        assert "Screenshot captured" in result
        assert "1080x1920" in result


class TestScroll:
    @responses.activate
    def test_scroll(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/scroll",
            json={"success": True},
        )
        result = json.loads(android_scroll("down"))
        assert result["success"] is True

    @responses.activate
    def test_scroll_with_node(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/scroll",
            json={"success": True},
        )
        result = json.loads(android_scroll("up", node_id="scroll_view_1"))
        assert result["success"] is True


class TestWait:
    @responses.activate
    def test_wait_found(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/wait",
            json={"success": True, "message": "Element found"},
        )
        result = json.loads(android_wait(text="Loading complete"))
        assert result["success"] is True

    @responses.activate
    def test_wait_timeout(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/wait",
            json={"success": False, "message": "Timeout"},
        )
        result = json.loads(android_wait(text="Never appears", timeout_ms=1000))
        assert result["success"] is False


class TestGetApps:
    @responses.activate
    def test_get_apps(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/apps",
            json={
                "apps": [{"packageName": "com.ubercab", "label": "Uber"}],
                "count": 1,
            },
        )
        result = json.loads(android_get_apps())
        assert result["count"] == 1


class TestCurrentApp:
    @responses.activate
    def test_current_app(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/current_app",
            json={"package": "com.ubercab", "className": "MainActivity"},
        )
        result = json.loads(android_current_app())
        assert result["package"] == "com.ubercab"


class TestSetup:
    @responses.activate
    def test_setup_saves_config(self, monkeypatch):
        """android_setup saves pairing code and sets env vars.

        The relay is stubbed: binding a real port made this fail on a loaded
        machine ("Relay failed to start within 10 seconds"), and relay lifecycle
        is covered by test_android_relay.py.
        """
        import tools.android_relay as relay

        monkeypatch.setattr("tools.android_tool._get_public_ip", lambda: "1.2.3.4")
        monkeypatch.setattr(relay, "start_relay", lambda **kw: None)
        monkeypatch.setattr(relay, "set_pairing_code", lambda code: None)
        monkeypatch.setattr(relay, "is_phone_connected", lambda: False)

        result = json.loads(android_setup("ABC234"))
        assert result["status"] == "ok"
        assert os.environ.get("ANDROID_BRIDGE_TOKEN") == "ABC234"
        assert "localhost" in os.environ.get("ANDROID_BRIDGE_URL", "")


class TestSetupRotatesARunningRelay:
    """AUTH-1: a rotated code must reach an already-running relay.

    ``start_relay()`` returns early while a relay thread is alive, so pushing the
    code through ``set_pairing_code()`` is the only way rotation takes effect —
    and it is also what closes phone sessions authenticated with the old code.
    """

    @staticmethod
    def _stub_relay(monkeypatch, calls):
        import tools.android_relay as relay

        monkeypatch.setattr(
            relay,
            "start_relay",
            lambda pairing_code, port=0: calls.append(("start_relay", pairing_code)),
        )
        monkeypatch.setattr(
            relay, "set_pairing_code", lambda code: calls.append(("set_pairing_code", code))
        )
        monkeypatch.setattr(relay, "is_phone_connected", lambda: False)
        monkeypatch.setattr("tools.android_tool._get_public_ip", lambda: "1.2.3.4")

    def test_setup_pushes_the_code_to_a_running_relay(self, monkeypatch):
        calls = []
        self._stub_relay(monkeypatch, calls)
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "")
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "")

        result = json.loads(android_setup("K7V3NP"))

        assert result["status"] == "ok"
        assert ("set_pairing_code", "K7V3NP") in calls, (
            "android_setup must call set_pairing_code so a relay that is already "
            f"running picks up the rotated code; calls were {calls}"
        )

    def test_the_code_is_pushed_before_start_relay(self, monkeypatch):
        """start_relay is a no-op when running, so it cannot be the rotation path."""
        calls = []
        self._stub_relay(monkeypatch, calls)
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "")
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "")

        android_setup("K7V3NP")

        assert calls.index(("set_pairing_code", "K7V3NP")) < calls.index(
            ("start_relay", "K7V3NP")
        ), f"set_pairing_code must run first; calls were {calls}"


class TestEnvFileFallback:
    """The gateway process may lack ANDROID_* vars in os.environ even though
    they exist in ~/.hermes/.env.  _bridge_token/_bridge_url must fall back to
    reading the .env file so the relay still authenticates."""

    def test_token_from_env_file_when_os_environ_empty(self, monkeypatch, tmp_path):
        from tools import android_tool

        monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
        monkeypatch.delenv("ANDROID_BRIDGE_URL", raising=False)
        monkeypatch.setattr(android_tool, "_ENV_FILE_CACHE", None)

        env_file = tmp_path / ".env"
        env_file.write_text("ANDROID_BRIDGE_TOKEN=SECRET123\nANDROID_BRIDGE_URL=http://1.2.3.4:8766\n")
        monkeypatch.setenv("HERMES_HOME", str(tmp_path))

        assert android_tool._bridge_token() == "SECRET123"
        assert android_tool._bridge_url() == "http://1.2.3.4:8766"

    def test_os_environ_wins_over_env_file(self, monkeypatch, tmp_path):
        from tools import android_tool

        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "FROM_ENV")
        monkeypatch.setattr(android_tool, "_ENV_FILE_CACHE", None)
        env_file = tmp_path / ".env"
        env_file.write_text("ANDROID_BRIDGE_TOKEN=FROM_FILE\n")
        monkeypatch.setenv("HERMES_HOME", str(tmp_path))

        assert android_tool._bridge_token() == "FROM_ENV"

    def test_missing_env_file_returns_none(self, monkeypatch, tmp_path):
        from tools import android_tool

        monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
        monkeypatch.setattr(android_tool, "_ENV_FILE_CACHE", None)
        monkeypatch.setenv("HERMES_HOME", str(tmp_path / "nonexistent"))

        assert android_tool._bridge_token() is None
        assert android_tool._bridge_url() == "http://localhost:8766"


class TestClipboardRead:
    @responses.activate
    def test_clipboard_read(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/clipboard",
            json={"success": True, "message": "Clipboard read", "data": "Hello world"},
        )
        result = json.loads(android_clipboard_read())
        assert result["success"] is True
        assert result["data"] == "Hello world"

    @responses.activate
    def test_clipboard_read_empty(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/clipboard",
            json={"success": True, "message": "Clipboard is empty", "data": ""},
        )
        result = json.loads(android_clipboard_read())
        assert result["success"] is True
        assert result["data"] == ""

    @responses.activate
    def test_clipboard_read_failure(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/clipboard",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_clipboard_read())
        assert "error" in result


class TestClipboardWrite:
    @responses.activate
    def test_clipboard_write(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/clipboard",
            json={"success": True, "message": "Copied to clipboard", "data": "Hello"},
        )
        result = json.loads(android_clipboard_write("Hello"))
        assert result["success"] is True

    @responses.activate
    def test_clipboard_write_failure(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/clipboard",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_clipboard_write("test"))
        assert "error" in result


class TestNotifications:
    @responses.activate
    def test_notifications(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/notifications",
            json={
                "notifications": [
                    {
                        "packageName": "com.whatsapp",
                        "title": "John",
                        "text": "Hey!",
                        "timestamp": 1700000000000,
                    }
                ],
                "count": 1,
                "listenerActive": True,
            },
        )
        result = json.loads(android_notifications())
        assert result["count"] == 1
        assert result["notifications"][0]["packageName"] == "com.whatsapp"
        assert result["listenerActive"] is True

    @responses.activate
    def test_notifications_with_since(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/notifications",
            json={"notifications": [], "count": 0, "listenerActive": True},
        )
        result = json.loads(android_notifications(since=1700000000000))
        assert result["count"] == 0

    @responses.activate
    def test_notifications_include_removed_is_opt_in(self, bridge_url):
        """#100 follow-up: dismissed notifications only reach the wire on
        explicit opt-in — the default request must not ask for them."""
        captured = {}

        def cb(request):
            captured["url"] = request.url
            return (200, {}, json.dumps({"notifications": [], "count": 0, "listenerActive": True}))

        responses.add_callback(responses.GET, f"{bridge_url}/notifications", callback=cb)

        json.loads(android_notifications())
        assert "include_removed" not in captured["url"]

        json.loads(android_notifications(include_removed=True))
        assert "include_removed=true" in captured["url"]

    @responses.activate
    def test_notifications_listener_inactive(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/notifications",
            json={"notifications": [], "count": 0, "listenerActive": False},
        )
        result = json.loads(android_notifications())
        assert result["listenerActive"] is False

    @responses.activate
    def test_notifications_failure(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/notifications",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_notifications())
        assert "error" in result


class TestLongPress:
    @responses.activate
    def test_long_press_by_coordinates(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/long_press",
            json={"success": True, "message": "Long pressed (100, 200) 500ms"},
        )
        result = json.loads(android_long_press(x=100, y=200))
        assert result["success"] is True

    @responses.activate
    def test_long_press_by_node(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/long_press",
            json={"success": True, "message": "Long pressed node n1"},
        )
        result = json.loads(android_long_press(node_id="n1"))
        assert result["success"] is True

    @responses.activate
    def test_long_press_custom_duration(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/long_press",
            json={"success": True},
        )
        result = json.loads(android_long_press(x=100, y=200, duration=1000))
        assert result["success"] is True

    def test_long_press_no_args(self):
        result = json.loads(android_long_press())
        assert "error" in result


class TestDrag:
    @responses.activate
    def test_drag(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/drag",
            json={"success": True, "message": "Dragged (100,200) to (300,400)"},
        )
        result = json.loads(android_drag(100, 200, 300, 400))
        assert result["success"] is True

    @responses.activate
    def test_drag_with_duration(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/drag",
            json={"success": True},
        )
        result = json.loads(android_drag(0, 0, 100, 100, duration=800))
        assert result["success"] is True

    @responses.activate
    def test_drag_failure(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/drag",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_drag(0, 0, 100, 100))
        assert "error" in result


class TestDescribeNode:
    @responses.activate
    def test_describe_node(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/describe_node",
            json={
                "success": True,
                "data": {
                    "nodeId": "n1",
                    "className": "android.widget.EditText",
                    "text": "Hello",
                    "clickable": True,
                    "editable": True,
                    "childCount": 0,
                },
            },
        )
        result = json.loads(android_describe_node("n1"))
        assert result["success"] is True
        assert result["data"]["editable"] is True

    @responses.activate
    def test_describe_node_not_found(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/describe_node",
            json={"success": False, "message": "Node not found: bad_id"},
        )
        result = json.loads(android_describe_node("bad_id"))
        assert result["success"] is False

    @responses.activate
    def test_describe_node_failure(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/describe_node",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_describe_node("n1"))
        assert "error" in result


class TestScreenHash:
    @responses.activate
    def test_screen_hash(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/screen_hash",
            json={"success": True, "data": {"hash": "abc123", "nodeCount": 42}},
        )
        result = json.loads(android_screen_hash())
        assert result["success"] is True
        assert result["data"]["hash"] == "abc123"

    @responses.activate
    def test_screen_hash_failure(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/screen_hash",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_screen_hash())
        assert "error" in result


class TestMacro:
    @responses.activate
    def test_macro_open_and_wait(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/open_app",
            json={"success": True, "message": "Opening com.ubercab"},
        )
        responses.add(
            responses.POST,
            f"{bridge_url}/wait",
            json={"success": True, "message": "Element found"},
        )
        result = json.loads(
            android_macro(
                steps=[
                    {"tool": "android_open_app", "args": {"package": "com.ubercab"}},
                    {"tool": "android_wait", "args": {"text": "Where to?"}},
                ],
                name="open_uber",
            )
        )
        assert result["success"] is True
        assert result["completed"] == 2

    @responses.activate
    def test_macro_stops_on_failure(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/tap_text",
            json={"success": False, "message": "Not found"},
        )
        result = json.loads(
            android_macro(
                steps=[
                    {"tool": "android_tap_text", "args": {"text": "nonexistent"}},
                ]
            )
        )
        assert "error" in result
        assert result["completed"] == 0

    def test_macro_unknown_tool(self):
        result = json.loads(
            android_macro(
                steps=[
                    {"tool": "android_nonexistent", "args": {}},
                ]
            )
        )
        assert "error" in result


class TestLocation:
    @responses.activate
    def test_location(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/location",
            json={
                "success": True,
                "data": {"latitude": 37.7749, "longitude": -122.4194, "accuracy": 10.0},
            },
        )
        result = json.loads(android_location())
        assert result["success"] is True
        assert result["data"]["latitude"] == 37.7749

    @responses.activate
    def test_location_failure(self, bridge_url):
        responses.add(
            responses.GET, f"{bridge_url}/location", body=ConnectionError("refused")
        )
        result = json.loads(android_location())
        assert "error" in result


class TestSendSms:
    @responses.activate
    def test_send_sms(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/send_sms",
            json={"success": True, "message": "SMS sent to +1234567890"},
        )
        result = json.loads(android_send_sms("+1234567890", "Hello!"))
        assert result["success"] is True

    @responses.activate
    def test_send_sms_failure(self, bridge_url):
        responses.add(
            responses.POST, f"{bridge_url}/send_sms", body=ConnectionError("refused")
        )
        result = json.loads(android_send_sms("+1234567890", "test"))
        assert "error" in result


class TestCall:
    @responses.activate
    def test_call(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/call",
            json={"success": True, "message": "Calling +1234567890"},
        )
        result = json.loads(android_call("+1234567890"))
        assert result["success"] is True

    @responses.activate
    def test_call_failure(self, bridge_url):
        responses.add(
            responses.POST, f"{bridge_url}/call", body=ConnectionError("refused")
        )
        result = json.loads(android_call("+1234567890"))
        assert "error" in result


class TestEvents:
    @responses.activate
    def test_events(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/events",
            json={
                "events": [
                    {
                        "eventType": "VIEW_CLICKED",
                        "text": "OK",
                        "packageName": "com.example",
                        "timestamp": 1700000000000,
                    },
                    {
                        "eventType": "WINDOW_STATE_CHANGED",
                        "className": "MainActivity",
                        "timestamp": 1700000001000,
                    },
                ],
                "count": 2,
                "streaming": True,
            },
        )
        result = json.loads(android_events())
        assert result["count"] == 2
        assert result["events"][0]["eventType"] == "VIEW_CLICKED"

    @responses.activate
    def test_events_with_since(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/events",
            json={"events": [], "count": 0, "streaming": False},
        )
        result = json.loads(android_events(since=1700000000000))
        assert result["count"] == 0

    @responses.activate
    def test_events_failure(self, bridge_url):
        responses.add(
            responses.GET, f"{bridge_url}/events", body=ConnectionError("refused")
        )
        result = json.loads(android_events())
        assert "error" in result


class TestEventStream:
    @responses.activate
    def test_enable_stream(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/events/stream",
            json={"success": True, "streaming": True},
        )
        result = json.loads(android_event_stream(True))
        assert result["streaming"] is True

    @responses.activate
    def test_disable_stream(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/events/stream",
            json={"success": True, "streaming": False},
        )
        result = json.loads(android_event_stream(False))
        assert result["streaming"] is False

    @responses.activate
    def test_stream_failure(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/events/stream",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_event_stream(True))
        assert "error" in result


class TestScreenRecord:
    @responses.activate
    def test_screen_record(self, bridge_url):
        import base64

        fake_video = base64.b64encode(b"\x00\x00\x00\x18ftypmp42").decode()
        responses.add(
            responses.POST,
            f"{bridge_url}/screen_record",
            json={
                "success": True,
                "data": {
                    "video": fake_video,
                    "width": 1080,
                    "height": 1920,
                    "durationMs": 5000,
                    "mimeType": "video/mp4",
                },
            },
        )
        result = android_screen_record()
        assert "Screen recorded" in result

    @responses.activate
    def test_screen_record_custom_duration(self, bridge_url):
        import base64

        fake_video = base64.b64encode(b"fake").decode()
        responses.add(
            responses.POST,
            f"{bridge_url}/screen_record",
            json={
                "success": True,
                "data": {
                    "video": fake_video,
                    "width": 1080,
                    "height": 1920,
                    "durationMs": 10000,
                },
            },
        )
        result = android_screen_record(duration_ms=10000)
        assert "Screen recorded" in result

    @responses.activate
    def test_screen_record_failure(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/screen_record",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_screen_record())
        assert "error" in result


class TestMicrophone:
    @responses.activate
    def test_start_recording(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/mic_start",
            json={"status": "starting", "duration": 12},
            status=202,
        )
        result = json.loads(android_mic_record(duration=12))
        assert result == {"status": "starting", "duration": 12}
        assert json.loads(responses.calls[0].request.body) == {"duration": 12}

    def test_rejects_invalid_duration_without_network_call(self):
        result = json.loads(android_mic_record(duration=1801))
        assert "error" in result

    @responses.activate
    def test_stop_recording(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/mic_stop",
            json={"status": "stopping"},
            status=202,
        )
        assert json.loads(android_mic_stop())["status"] == "stopping"

    @responses.activate
    def test_recording_status(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/mic_status",
            json={"phase": "ready", "recording": False, "latest": "recording_test.wav"},
        )
        assert json.loads(android_mic_status())["phase"] == "ready"

    @responses.activate
    def test_fetch_streams_wav_to_media_path(self, bridge_url):
        from pathlib import Path

        wav = b"RIFF" + (b"\x00" * 4) + b"WAVE" + (b"\x00" * 52)
        responses.add(
            responses.GET,
            f"{bridge_url}/mic_file",
            body=wav,
            status=200,
            headers={"Content-Type": "audio/wav", "Content-Length": str(len(wav))},
        )

        result = android_mic_fetch("recording_test.wav")
        media_path = Path(result.split("MEDIA:", 1)[1])
        try:
            assert media_path.read_bytes() == wav
            assert "name=recording_test.wav" in responses.calls[0].request.url
        finally:
            media_path.unlink(missing_ok=True)

    def test_fetch_rejects_device_paths(self):
        result = json.loads(android_mic_fetch("../recording.wav"))
        assert "error" in result


class TestReadWidgets:
    @responses.activate
    def test_read_widgets(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/widgets",
            json={
                "success": True,
                "data": {
                    "widgets": [{"text": "72°F", "className": "WeatherWidget"}],
                    "count": 1,
                },
            },
        )
        result = json.loads(android_read_widgets())
        assert result["success"] is True

    @responses.activate
    def test_read_widgets_failure(self, bridge_url):
        responses.add(
            responses.GET, f"{bridge_url}/widgets", body=ConnectionError("refused")
        )
        result = json.loads(android_read_widgets())
        assert "error" in result


class TestMedia:
    @responses.activate
    def test_media_toggle(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/media",
            json={"success": True, "message": "Media toggle sent"},
        )
        result = json.loads(android_media("toggle"))
        assert result["success"] is True

    @responses.activate
    def test_media_next(self, bridge_url):
        responses.add(responses.POST, f"{bridge_url}/media", json={"success": True})
        result = json.loads(android_media("next"))
        assert result["success"] is True

    @responses.activate
    def test_media_failure(self, bridge_url):
        responses.add(
            responses.POST, f"{bridge_url}/media", body=ConnectionError("refused")
        )
        result = json.loads(android_media("play"))
        assert "error" in result


class TestSearchContacts:
    @responses.activate
    def test_search_contacts(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/contacts",
            json={
                "success": True,
                "data": {
                    "contacts": [{"name": "John", "phones": "+1234567890"}],
                    "count": 1,
                },
            },
        )
        result = json.loads(android_search_contacts("John"))
        assert result["success"] is True
        assert result["data"]["count"] == 1

    @responses.activate
    def test_search_contacts_not_found(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/contacts",
            json={"success": False, "message": "No contacts found matching 'zzz'"},
        )
        result = json.loads(android_search_contacts("zzz"))
        assert result["success"] is False

    @responses.activate
    def test_search_contacts_failure(self, bridge_url):
        responses.add(
            responses.GET, f"{bridge_url}/contacts", body=ConnectionError("refused")
        )
        result = json.loads(android_search_contacts("test"))
        assert "error" in result

    @responses.activate
    def test_search_contacts_special_chars_url_encoded(self, bridge_url):
        """Query strings with special chars (& ? +) must be URL-encoded."""
        responses.add(
            responses.GET,
            f"{bridge_url}/contacts",
            json={"success": True, "data": {"contacts": [], "count": 0}},
        )
        # The ampersand, plus, and space must NOT break the URL
        result = json.loads(android_search_contacts("Tom & Jerry+Smith"))
        assert result["success"] is True
        # Verify the actual request URL has the query properly encoded
        assert len(responses.calls) == 1
        request_url = responses.calls[0].request.url
        assert "query=" in request_url
        # The raw '&' in the name must be encoded so it doesn't create a new param
        assert "Tom%20%26%20Jerry%2BSmith" in request_url or "Tom+%26+Jerry%2BSmith" in request_url

    @responses.activate
    def test_search_contacts_unicode_url_encoded(self, bridge_url):
        """Unicode query strings must be properly encoded."""
        responses.add(
            responses.GET,
            f"{bridge_url}/contacts",
            json={"success": True, "data": {"contacts": [], "count": 0}},
        )
        result = json.loads(android_search_contacts("José García"))
        assert result["success"] is True
        assert len(responses.calls) == 1


class TestSendIntent:
    @responses.activate
    def test_send_intent(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/intent",
            json={
                "success": True,
                "message": "Intent sent: android.settings.WIFI_SETTINGS",
            },
        )
        result = json.loads(android_send_intent("android.settings.WIFI_SETTINGS"))
        assert result["success"] is True

    @responses.activate
    def test_send_intent_with_extras(self, bridge_url):
        responses.add(responses.POST, f"{bridge_url}/intent", json={"success": True})
        result = json.loads(
            android_send_intent(
                "android.intent.action.VIEW",
                data_uri="https://example.com",
                extras={"key": "value"},
            )
        )
        assert result["success"] is True

    @responses.activate
    def test_send_intent_failure(self, bridge_url):
        responses.add(
            responses.POST, f"{bridge_url}/intent", body=ConnectionError("refused")
        )
        result = json.loads(android_send_intent("test"))
        assert "error" in result


class TestBroadcast:
    @responses.activate
    def test_broadcast(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/broadcast",
            json={"success": True, "message": "Broadcast sent: test.ACTION"},
        )
        result = json.loads(android_broadcast("test.ACTION"))
        assert result["success"] is True

    @responses.activate
    def test_broadcast_failure(self, bridge_url):
        responses.add(
            responses.POST, f"{bridge_url}/broadcast", body=ConnectionError("refused")
        )
        result = json.loads(android_broadcast("test"))
        assert "error" in result


class TestHTTPErrorPreservation:
    """Tests that HTTP error response bodies are preserved in error messages."""

    @responses.activate
    def test_post_preserves_json_error_body(self, bridge_url):
        """When the bridge returns a JSON error with non-200 status, the error body is preserved."""
        responses.add(
            responses.POST,
            f"{bridge_url}/tap",
            json={"error": "Node not found: bad_id", "code": "NODE_MISSING"},
            status=404,
        )
        result = json.loads(android_tap(node_id="bad_id"))
        assert "error" in result
        # The error message should contain the JSON body from the response
        assert "Node not found" in str(result["error"])

    @responses.activate
    def test_get_preserves_json_error_body(self, bridge_url):
        """When the bridge returns a JSON error on GET, the error body is preserved."""
        responses.add(
            responses.GET,
            f"{bridge_url}/screen",
            json={"error": "Accessibility service not running", "code": "SERVICE_DOWN"},
            status=503,
        )
        result = json.loads(android_read_screen())
        assert "error" in result
        assert "Accessibility service not running" in str(result["error"])

    @responses.activate
    def test_post_handles_non_json_error(self, bridge_url):
        """When the bridge returns a non-JSON error, we still get a useful message."""
        responses.add(
            responses.POST,
            f"{bridge_url}/tap",
            body="Internal Server Error",
            status=500,
        )
        result = json.loads(android_tap(x=100, y=200))
        assert "error" in result


class TestHardwareUnavailable:
    @responses.activate
    def test_send_sms_unavailable(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/send_sms",
            json={"success": False, "error": "SMS not available on this device"},
            status=200,
        )
        result = json.loads(android_send_sms("+1234567890", "test"))
        assert result["success"] is False
        assert "not available" in result["error"]

    @responses.activate
    def test_call_unavailable(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/call",
            json={"success": False, "error": "Phone calls not available on this device"},
            status=200,
        )
        result = json.loads(android_call("+1234567890"))
        assert result["success"] is False
        assert "not available" in result["error"]

    @responses.activate
    def test_contacts_unavailable(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/contacts",
            json={"success": False, "error": "Contacts not available on this device"},
            status=200,
        )
        result = json.loads(android_search_contacts("John"))
        assert result["success"] is False
        assert "not available" in result["error"]
class TestSpeak:
    @responses.activate
    def test_speak(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/speak",
            json={"success": True, "message": "Speaking: Hello world"},
        )
        result = json.loads(android_speak("Hello world"))
        assert result["success"] is True

    @responses.activate
    def test_speak_flush(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/speak",
            json={"success": True},
        )
        result = json.loads(android_speak("Urgent!", flush=True))
        assert result["success"] is True

    @responses.activate
    def test_speak_failure(self, bridge_url):
        responses.add(
            responses.POST, f"{bridge_url}/speak", body=ConnectionError("refused")
        )
        result = json.loads(android_speak("test"))
        assert "error" in result


class TestSpeakStop:
    @responses.activate
    def test_speak_stop(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/stop_speaking",
            json={"success": True, "message": "Speech stopped"},
        )
        result = json.loads(android_speak_stop())
        assert result["success"] is True

    @responses.activate
    def test_speak_stop_failure(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/stop_speaking",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_speak_stop())
        assert "error" in result


class TestFindNodes:
    @responses.activate
    def test_find_nodes_by_text(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/find_nodes",
            json={
                "success": True,
                "nodes": [{"nodeId": "n5", "text": "Login", "clickable": True}],
                "count": 1,
            },
        )
        result = json.loads(android_find_nodes(text="Login"))
        assert result["success"] is True
        assert result["count"] == 1

    @responses.activate
    def test_find_nodes_by_class(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/find_nodes",
            json={"success": True, "nodes": [], "count": 0},
        )
        result = json.loads(
            android_find_nodes(class_name="android.widget.Button")
        )
        assert result["success"] is True

    @responses.activate
    def test_find_nodes_clickable(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/find_nodes",
            json={"success": True, "nodes": [], "count": 0},
        )
        result = json.loads(android_find_nodes(clickable=True))
        assert result["success"] is True

    @responses.activate
    def test_find_nodes_with_limit(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/find_nodes",
            json={"success": True, "nodes": [], "count": 0},
        )
        result = json.loads(android_find_nodes(text="a", limit=5))
        assert result["success"] is True

    @responses.activate
    def test_find_nodes_failure(self, bridge_url):
        responses.add(
            responses.POST, f"{bridge_url}/find_nodes", body=ConnectionError("refused")
        )
        result = json.loads(android_find_nodes(text="test"))
        assert "error" in result


class TestDiffScreen:
    @responses.activate
    def test_diff_screen_changed(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/diff_screen",
            json={"success": True, "changed": True, "newHash": "def456"},
        )
        result = json.loads(android_diff_screen("abc123"))
        assert result["success"] is True
        assert result["changed"] is True
        assert result["newHash"] == "def456"

    @responses.activate
    def test_diff_screen_unchanged(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/diff_screen",
            json={"success": True, "changed": False, "newHash": "abc123"},
        )
        result = json.loads(android_diff_screen("abc123"))
        assert result["changed"] is False

    @responses.activate
    def test_diff_screen_failure(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/diff_screen",
            body=ConnectionError("refused"),
        )
        result = json.loads(android_diff_screen("abc"))
        assert "error" in result


class TestPinch:
    @responses.activate
    def test_pinch_zoom_in(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/pinch",
            json={"success": True, "message": "Pinched at (540, 960) scale=2.0"},
        )
        result = json.loads(android_pinch(540, 960, scale=2.0))
        assert result["success"] is True

    @responses.activate
    def test_pinch_zoom_out(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/pinch",
            json={"success": True},
        )
        result = json.loads(android_pinch(540, 960, scale=0.5))
        assert result["success"] is True

    @responses.activate
    def test_pinch_custom_duration(self, bridge_url):
        responses.add(
            responses.POST,
            f"{bridge_url}/pinch",
            json={"success": True},
        )
        result = json.loads(android_pinch(540, 960, duration=500))
        assert result["success"] is True

    @responses.activate
    def test_pinch_failure(self, bridge_url):
        responses.add(
            responses.POST, f"{bridge_url}/pinch", body=ConnectionError("refused")
        )
        result = json.loads(android_pinch(0, 0))
        assert "error" in result


class TestMicFetchErrorRedaction:
    """#99: requests exception text embeds the bridge host:port, which tool
    responses must not expose (AGENTS.md convention)."""

    @responses.activate
    def test_connection_error_does_not_leak_bridge_url(self, monkeypatch):
        import requests

        leaked_url = "http://192.168.7.42:8766"
        monkeypatch.setenv("ANDROID_BRIDGE_URL", leaked_url)
        responses.add(
            responses.GET,
            f"{leaked_url}/mic_file",
            body=requests.exceptions.ConnectionError(
                "HTTPConnectionPool(host='192.168.7.42', port=8766): "
                "Max retries exceeded with url: /mic_file"
            ),
        )

        result = json.loads(android_mic_fetch())

        assert "error" in result
        assert "192.168.7.42" not in result["error"]
        assert "8766" not in result["error"]
        assert result["error"] == "Could not download microphone recording from the bridge"


# ── PY-1: pairing-code validation and .env write hardening ────────────────────

import stat as _stat
from pathlib import Path as _Path

from tools.android_tool import _pairing_code_error, _update_env_file


class TestPairingCodeValidation:
    """PY-1: `pairing_code` is written into ~/.hermes/.env verbatim.

    That file is parsed as `key=value` lines and `_bridge_url()` reads
    ANDROID_BRIDGE_URL back out of it, so a crafted value can re-point every
    later tool call at an attacker host — with the real Bearer token attached by
    `_auth_headers()`. Validation is the fix; these pin the accepted shape to
    exactly what `PairingManager.regenerateCode()` can produce.
    """

    @pytest.mark.parametrize(
        "code",
        [
            "ABC234\nANDROID_BRIDGE_URL=http://attacker.example:8766",
            "ABC234\r\nANDROID_BRIDGE_TOKEN=stolen",
            "ABC234\n",
            "\nABC234",
            "ABC234 # comment",
            "#ABC234",
            "abc234",
            "Abc234",
            " ABC234",
            "ABC234 ",
            "ABC23",
            "A2345",
            "",
            "ABC01I",  # 0, 1 and I are not in the generated alphabet
            "ABC23O",
            'ABC234"',
            "ABC234'",
            "ABC234;rm -rf /",
            "ABC234`id`",
            "$(id)ABC",
            "ANDROID_BRIDGE_URL=http://evil.example",
            "ABC-234",
            "ABC.234",
            "ABC234\x00",
            "A" * 33,
        ],
    )
    def test_rejected(self, code):
        assert _pairing_code_error(code) is not None, f"{code!r} must be rejected"

    @pytest.mark.parametrize("code", [123456, None, ["ABC234"], {"code": "ABC234"}])
    def test_non_string_rejected(self, code):
        assert _pairing_code_error(code) is not None

    @pytest.mark.parametrize(
        "code",
        [
            "ABC234",
            "K7V3NP",
            "ZZZZZZ",
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789",  # the full generated alphabet
            "A" * 32,
        ],
    )
    def test_accepted(self, code):
        assert _pairing_code_error(code) is None, f"{code!r} must be accepted"

    def test_rejection_never_echoes_the_value(self):
        """The code is a credential; an error message must not carry it."""
        message = _pairing_code_error("ABC234\nANDROID_BRIDGE_URL=http://attacker.example")
        assert "ABC234" not in message
        assert "attacker" not in message


class TestSetupRejectsMalformedPairingCode:
    """End-to-end: android_setup must refuse before anything is written."""

    @pytest.fixture(autouse=True)
    def _stub_relay(self, monkeypatch):
        import tools.android_relay as relay

        self.calls = []
        monkeypatch.setattr(
            relay, "start_relay", lambda **kw: self.calls.append(("start_relay", kw))
        )
        monkeypatch.setattr(
            relay, "set_pairing_code", lambda code: self.calls.append(("set_pairing_code", code))
        )
        monkeypatch.setattr(relay, "is_phone_connected", lambda: False)
        monkeypatch.setattr("tools.android_tool._get_public_ip", lambda: "1.2.3.4")
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "PRE-EXISTING")
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://localhost:8766")
        yield

    def _env_file(self):
        return _Path(os.environ["HERMES_HOME"]) / ".env"

    def test_injection_attempt_is_refused_and_writes_nothing(self):
        injected = "ABC234\nANDROID_BRIDGE_URL=http://attacker.example:8766"

        result = json.loads(android_setup(injected))

        assert result["status"] == "error"
        assert "Invalid pairing code" in result["message"]
        assert injected not in result["message"], "the credential must not be echoed"
        assert "attacker" not in result["message"]
        assert not self.calls, "no relay may be started for a rejected code"
        assert os.environ["ANDROID_BRIDGE_TOKEN"] == "PRE-EXISTING"
        assert os.environ["ANDROID_BRIDGE_URL"] == "http://localhost:8766"
        assert not self._env_file().exists(), "a rejected code must not touch .env"

    def test_non_string_code_is_refused(self):
        result = json.loads(android_setup(123456))
        assert result["status"] == "error"
        assert not self.calls
        assert not self._env_file().exists()

    def test_a_real_code_still_sets_up(self):
        result = json.loads(android_setup("K7V3NP"))

        assert result["status"] == "ok"
        assert os.environ["ANDROID_BRIDGE_TOKEN"] == "K7V3NP"
        assert ("set_pairing_code", "K7V3NP") in self.calls
        env_text = self._env_file().read_text()
        assert "ANDROID_BRIDGE_TOKEN=K7V3NP" in env_text
        assert len(env_text.splitlines()) == 3, env_text

    def test_setup_writes_to_hermes_home_not_the_real_home(self):
        """The .env that `_bridge_token()` reads is the one setup must write."""
        android_setup("K7V3NP")

        assert self._env_file().exists()
        assert _Path(os.environ["HERMES_HOME"]) == _Path.home() / ".hermes"


class TestUpdateEnvFileHardening:
    """PY-1 defence in depth: the writer itself refuses multi-line values."""

    @pytest.fixture
    def env(self, tmp_path):
        """Its own directory: tmp_path also holds conftest's fake home."""
        directory = tmp_path / "cfg"
        directory.mkdir()
        return directory / ".env"

    def test_refuses_a_value_with_a_newline(self, env):
        with pytest.raises(ValueError):
            _update_env_file(env, "ANDROID_BRIDGE_TOKEN", "ABC234\nEVIL=1")
        assert not env.exists()

    def test_refuses_a_value_with_a_carriage_return(self, env):
        with pytest.raises(ValueError):
            _update_env_file(env, "ANDROID_BRIDGE_TOKEN", "ABC234\rEVIL=1")
        assert not env.exists()

    @pytest.mark.parametrize("key", ["A B", "KEY=1", "", "1KEY", "KEY\nOTHER", "a.b"])
    def test_refuses_a_key_that_could_forge_a_line(self, env, key):
        with pytest.raises(ValueError):
            _update_env_file(env, key, "ABC234")

    def test_new_file_is_created_at_0600(self, env):
        _update_env_file(env, "ANDROID_BRIDGE_TOKEN", "ABC234")
        assert _stat.S_IMODE(env.stat().st_mode) == 0o600

    def test_a_pre_existing_world_readable_file_is_tightened(self, env):
        """write-then-chmod left the token readable in between; this must not."""
        env.write_text("ANDROID_BRIDGE_TOKEN=OLD\n")
        env.chmod(0o644)

        _update_env_file(env, "ANDROID_BRIDGE_TOKEN", "ABC234")

        assert _stat.S_IMODE(env.stat().st_mode) == 0o600
        assert env.read_text() == "ANDROID_BRIDGE_TOKEN=ABC234\n"

    def test_updates_in_place_and_keeps_other_keys(self, env):
        env.write_text("# comment\nOTHER=keep\nANDROID_BRIDGE_TOKEN=OLD\nTRAILING=1\n")

        _update_env_file(env, "ANDROID_BRIDGE_TOKEN", "ABC234")

        assert env.read_text() == (
            "# comment\nOTHER=keep\nANDROID_BRIDGE_TOKEN=ABC234\nTRAILING=1\n"
        )

    def test_appends_a_missing_key_and_fixes_a_missing_final_newline(self, env):
        env.write_text("OTHER=keep")

        _update_env_file(env, "ANDROID_BRIDGE_TOKEN", "ABC234")

        assert env.read_text() == "OTHER=keep\nANDROID_BRIDGE_TOKEN=ABC234\n"

    def test_leaves_no_temp_file_behind(self, env):
        _update_env_file(env, "ANDROID_BRIDGE_TOKEN", "ABC234")
        _update_env_file(env, "ANDROID_BRIDGE_URL", "http://localhost:8766")
        assert sorted(p.name for p in env.parent.iterdir()) == [".env"]

    def test_a_rejected_write_leaves_the_previous_file_intact(self, env):
        env.write_text("ANDROID_BRIDGE_TOKEN=GOOD\n")
        env.chmod(0o600)

        with pytest.raises(ValueError):
            _update_env_file(env, "ANDROID_BRIDGE_TOKEN", "BAD\nVALUE")

        assert env.read_text() == "ANDROID_BRIDGE_TOKEN=GOOD\n"
        assert sorted(p.name for p in env.parent.iterdir()) == [".env"]


class TestSendIntentSchemaMatchesTheDevice:
    """CTRL-2: the tool schema advertised values the device denylist rejects.

    `data_uri` said "e.g. tel:+1234567890" and `action` said "e.g.
    android.settings.WIFI_SETTINGS" — both blocked on the phone — and
    `android_broadcast` suggested `android.intent.action.AIRPLANE_MODE`, a
    protected broadcast that throws SecurityException. Telling the model to make
    calls that cannot succeed teaches it that a denylist exists, which is an
    invitation to probe it.
    """

    def test_data_uri_example_is_not_a_denied_scheme(self):
        props = _SCHEMAS["android_send_intent"]["parameters"]["properties"]
        example = props["data_uri"]["description"]
        for denied in ("tel:", "sms:", "smsto:", "mmsto:", "mms:", "market:", "intent:", "content://sms"):
            assert denied not in example, f"{denied} is blocked on the device but advertised as an example"

    def test_action_example_is_not_a_denied_action(self):
        props = _SCHEMAS["android_send_intent"]["parameters"]["properties"]
        example = props["action"]["description"]
        assert "android.settings." not in example
        assert "android.provider.action." not in example
        assert "CALL" not in example

    def test_broadcast_example_is_not_a_protected_broadcast(self):
        props = _SCHEMAS["android_broadcast"]["parameters"]["properties"]
        example = props["action"]["description"]
        assert "AIRPLANE_MODE" not in example
        assert "MEDIA_MOUNTED" not in example
        assert "PACKAGE_ADDED" not in example

    def test_send_intent_says_what_is_blocked(self):
        """The model should learn the boundary from the schema, not by probing."""
        description = _SCHEMAS["android_send_intent"]["description"]
        assert "tel:" in description
        assert "sms:" in description
        assert "content://" in description
        assert "android_call" in description and "android_send_sms" in description, (
            "point the model at the tools that are allowed to do this"
        )

    def test_examples_survive_in_both_copies(self):
        """tools/ and hermes-android-plugin/ must not drift apart."""
        import runpy
        from pathlib import Path

        plugin = runpy.run_path(
            str(Path(__file__).parents[1] / "hermes-android-plugin" / "android_tool.py")
        )
        assert (
            plugin["_SCHEMAS"]["android_send_intent"]
            == _SCHEMAS["android_send_intent"]
        )
        assert (
            plugin["_SCHEMAS"]["android_broadcast"]
            == _SCHEMAS["android_broadcast"]
        )


class TestEventStreamingContract:
    """DROID-4: the streaming switch is a collection gate, and the schema says so.

    `/events` used to buffer and serve accessibility events regardless of
    `streamingEnabled` — the switch only *reported* the flag — so turning
    streaming off did not stop the phone capturing what the user typed and
    clicked in every app. The device now gates collection at the source; the tool
    contract has to tell the model that capture is off until it asks.
    """

    def test_android_events_says_capture_is_off_by_default(self):
        description = _SCHEMAS["android_events"]["description"]
        assert "android_event_stream(True)" in description
        assert "streaming=false" in description

    def test_android_events_docstring_carries_the_same_prerequisite(self):
        assert "android_event_stream(True)" in (android_events.__doc__ or "")

    def test_event_stream_is_described_as_the_collection_switch(self):
        description = _SCHEMAS["android_event_stream"]["description"]
        assert "starts and stops collection" in description
        assert "clears the buffer" in description

    @responses.activate
    def test_a_disabled_stream_is_reported_not_silently_empty(self, bridge_url):
        responses.add(
            responses.GET,
            f"{bridge_url}/events",
            json={"events": [], "count": 0, "streaming": False},
        )

        result = json.loads(android_events())

        assert result["streaming"] is False
        assert result["events"] == []
        assert result["count"] == 0

    def test_both_copies_describe_the_same_contract(self):
        import runpy
        from pathlib import Path

        plugin = runpy.run_path(
            str(Path(__file__).parents[1] / "hermes-android-plugin" / "android_tool.py")
        )
        assert plugin["_SCHEMAS"]["android_events"] == _SCHEMAS["android_events"]
        assert (
            plugin["_SCHEMAS"]["android_event_stream"]
            == _SCHEMAS["android_event_stream"]
        )


class TestMediaTempFileRetention:
    """PY-2: MEDIA temp files hold rendered screen content and must not persist.

    Screenshots, screen recordings and microphone WAVs contain whatever was on
    screen — banking balances, message threads, the pairing code whenever the
    Bridge app is open. AGENTS.md says a `MEDIA:` path is a temp file to relay,
    not to persist, and `android_mic_fetch` already unlinked on its error path;
    the success paths of all three left the file in the temp dir for the lifetime
    of the filesystem.

    The file cannot be deleted inline — the gateway reads `MEDIA:<path>` after the
    tool returns — so it is deleted at process exit, and stale leftovers from
    earlier (possibly crashed) runs are swept once per process.
    """

    @pytest.fixture(autouse=True)
    def _isolate(self, monkeypatch, tmp_path):
        import tempfile

        from tools import android_tool as mod

        self.mod = mod
        monkeypatch.setattr(mod, "_media_temp_files", set())
        monkeypatch.setattr(mod, "_media_temp_swept", False)
        monkeypatch.setattr(mod, "_media_temp_atexit_registered", False)
        # conftest already points tempfile.gettempdir() at a per-test directory.
        self.media_dir = _Path(tempfile.gettempdir())
        yield
        mod._delete_media_temp_files()

    def test_a_registered_file_survives_the_call_and_dies_with_the_process(self):
        target = self.media_dir / "android_screenshot_x.jpg"
        target.write_bytes(b"pixels")

        assert self.mod._register_media_temp(str(target)) == str(target)
        assert target.exists(), "the gateway still has to be able to read MEDIA:<path>"

        self.mod._delete_media_temp_files()

        assert not target.exists()
        assert not self.mod._media_temp_files

    def test_atexit_is_registered_once(self, monkeypatch):
        registered = []
        monkeypatch.setattr(self.mod.atexit, "register", lambda fn: registered.append(fn))

        self.mod._register_media_temp(str(self.media_dir / "a.jpg"))
        self.mod._register_media_temp(str(self.media_dir / "b.jpg"))

        assert len(registered) == 1
        assert registered[0] is self.mod._delete_media_temp_files

    def test_deleting_an_already_gone_file_is_not_an_error(self):
        self.mod._register_media_temp(str(self.media_dir / "never-existed.jpg"))
        self.mod._delete_media_temp_files()  # must not raise
        assert not self.mod._media_temp_files

    def test_the_sweeper_removes_only_our_own_stale_files(self):
        stale_shot = self.media_dir / "android_screenshot_old.jpg"
        stale_shot.write_bytes(b"x")
        os.utime(stale_shot, (0, 0))
        stale_recording = self.media_dir / "android_record_old.mp4"
        stale_recording.write_bytes(b"x")
        os.utime(stale_recording, (0, 0))
        stale_mic = self.media_dir / "android_mic_old.wav"
        stale_mic.write_bytes(b"x")
        os.utime(stale_mic, (0, 0))
        fresh = self.media_dir / "android_screenshot_new.jpg"
        fresh.write_bytes(b"x")
        unrelated = self.media_dir / "someone_elses_file.jpg"
        unrelated.write_bytes(b"x")
        os.utime(unrelated, (0, 0))

        self.mod._sweep_stale_media_temps()

        assert not stale_shot.exists()
        assert not stale_recording.exists()
        assert not stale_mic.exists()
        assert fresh.exists(), "a file another session may still relay must survive"
        assert unrelated.exists(), "never touch files we did not create"

    def test_the_sweeper_runs_at_most_once_per_process(self):
        first = self.media_dir / "android_screenshot_a.jpg"
        first.write_bytes(b"x")
        os.utime(first, (0, 0))

        self.mod._sweep_stale_media_temps()
        assert not first.exists()

        second = self.media_dir / "android_screenshot_b.jpg"
        second.write_bytes(b"x")
        os.utime(second, (0, 0))
        self.mod._sweep_stale_media_temps()

        assert second.exists(), "the sweep must not repeat within one process"

    def test_the_sweeper_tolerates_an_unreadable_temp_dir(self, monkeypatch):
        monkeypatch.setattr(self.mod.os, "listdir", _raise_oserror)
        self.mod._sweep_stale_media_temps()  # must not raise

    @responses.activate
    def test_screenshot_registers_its_media_file(self, bridge_url):
        import base64

        responses.add(
            responses.GET,
            f"{bridge_url}/screenshot",
            json={
                "image": base64.b64encode(b"\x89PNG\r\n\x1a\n").decode(),
                "width": 1080,
                "height": 1920,
            },
        )

        result = android_screenshot()

        path = result.split("MEDIA:")[1].strip()
        assert path in self.mod._media_temp_files
        assert os.path.exists(path), "the gateway must still be able to read it"
        self.mod._delete_media_temp_files()
        assert not os.path.exists(path)

    @responses.activate
    def test_screen_record_registers_its_media_file(self, bridge_url):
        import base64

        responses.add(
            responses.POST,
            f"{bridge_url}/screen_record",
            json={
                "success": True,
                "data": {
                    "video": base64.b64encode(b"\x00\x00\x00\x18ftypmp42").decode(),
                    "width": 1080,
                    "height": 1920,
                },
            },
        )

        result = android_screen_record()

        path = result.split("MEDIA:")[1].strip()
        assert path in self.mod._media_temp_files
        assert os.path.exists(path)
        self.mod._delete_media_temp_files()
        assert not os.path.exists(path)


def _raise_oserror(_path):
    raise OSError("temp dir unreadable")
