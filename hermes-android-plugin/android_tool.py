"""
hermes-android tool — 38 android_* tool handlers + schemas.

NOTE: This file must be kept in sync with tools/android_tool.py.
      The only difference is the import path for android_relay (see android_setup).
      Apply any bug fixes or feature changes to BOTH files.

Used by the plugin's __init__.py to register tools into hermes-agent
via ctx.register_tool().
"""

import json
import os
import time
import contextvars
import requests
from typing import Optional
from urllib.parse import quote, urlencode

# ── Config ────────────────────────────────────────────────────────────────────
#
# Architecture: Phone connects OUT to Hermes server via WebSocket (NAT-friendly).
# A relay server runs on localhost and bridges HTTP tool calls to the phone.
#
#   Tools ──HTTP──> Relay (localhost:8766) ──WebSocket──> Phone
#
# For local/USB dev, tools can also talk directly to the phone's HTTP server
# by setting ANDROID_BRIDGE_URL to the phone's IP.


def _bridge_url() -> str:
    """URL of the relay (default) or direct phone connection."""
    return os.getenv("ANDROID_BRIDGE_URL", "http://localhost:8766")


def _bridge_token() -> Optional[str]:
    return os.getenv("ANDROID_BRIDGE_TOKEN")


_CURRENT_DEVICE: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "android_device", default=None
)
_ACTIVE_DEVICE: Optional[str] = None


def _selected_device(device: Optional[str] = None) -> Optional[str]:
    return (
        device
        or _CURRENT_DEVICE.get()
        or _ACTIVE_DEVICE
        or os.getenv("ANDROID_DEFAULT_DEVICE")
    )


def _relay_port() -> int:
    return int(os.getenv("ANDROID_RELAY_PORT", "8766"))


def _timeout() -> float:
    return float(os.getenv("ANDROID_BRIDGE_TIMEOUT", "30"))


def _auth_headers() -> dict:
    """Build auth headers with pairing code if configured."""
    token = _bridge_token()
    if token:
        return {"Authorization": f"Bearer {token}"}
    return {}


def _check_requirements() -> bool:
    """Returns True if the relay is running and a phone is connected."""
    try:
        r = requests.get(f"{_bridge_url()}/ping", headers=_auth_headers(), timeout=2)
        if r.status_code == 200:
            data = r.json()
            return data.get("phone_connected", False) or data.get(
                "accessibilityService", False
            )
        return False
    except Exception:
        return False


def _extract_response(r: requests.Response) -> dict:
    """Extract JSON from a response, preserving error body on HTTP errors."""
    try:
        body = r.json()
    except (ValueError, requests.exceptions.JSONDecodeError):
        body = {"error": r.text or f"HTTP {r.status_code}"}

    if not r.ok:
        # If the body doesn't already have an error key, wrap it
        if "error" not in body:
            body = {"error": body}
        raise requests.exceptions.HTTPError(json.dumps(body), response=r)

    return body


def _with_device(path: str, device: Optional[str] = None) -> str:
    selected = _selected_device(device)
    if not selected or path.startswith("/devices"):
        return path
    separator = "&" if "?" in path else "?"
    return f"{path}{separator}{urlencode({'device': selected})}"


def _post(path: str, payload: dict, device: Optional[str] = None) -> dict:
    r = requests.post(
        f"{_bridge_url()}{_with_device(path, device)}",
        json=payload,
        headers=_auth_headers(),
        timeout=_timeout(),
    )
    return _extract_response(r)


def _get(path: str, device: Optional[str] = None) -> dict:
    r = requests.get(
        f"{_bridge_url()}{_with_device(path, device)}",
        headers=_auth_headers(),
        timeout=_timeout(),
    )
    return _extract_response(r)


# ── Tool implementations ───────────────────────────────────────────────────────


def android_ping() -> str:
    try:
        data = _get("/ping")
        return json.dumps({"status": "ok", "bridge": data})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def android_devices() -> str:
    """List configured Android devices and whether each one is connected."""
    try:
        data = _get("/devices")
        data["active_device"] = _selected_device()
        data["usage"] = (
            "Use android_select_device(device) before a multi-step task, or pass "
            "device to individual android_* calls. Call android_read_screen after "
            "selecting a device before tapping."
        )
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_select_device(device: str) -> str:
    """
    Select the Android device that subsequent android_* tool calls should target.
    Use a device alias from android_devices(), such as 'old' or 'lrw2u7'.
    """
    global _ACTIVE_DEVICE
    try:
        requested = str(device).strip()
        if not requested:
            return json.dumps({"error": "device is required"})

        data = _get("/devices")
        devices = data.get("devices", [])
        connected = []
        for item in devices:
            aliases = [item.get("device"), *item.get("aliases", [])]
            aliases = [str(alias) for alias in aliases if alias]
            if item.get("connected"):
                connected.append(item.get("device"))
            if requested.lower() in {alias.lower() for alias in aliases}:
                if not item.get("connected"):
                    return json.dumps(
                        {
                            "error": f"Android device '{requested}' is configured but not connected.",
                            "devices": devices,
                        }
                    )
                _ACTIVE_DEVICE = item.get("device") or requested
                return json.dumps(
                    {
                        "status": "ok",
                        "active_device": _ACTIVE_DEVICE,
                        "message": (
                            f"Selected Android device '{_ACTIVE_DEVICE}'. "
                            "Subsequent android_* calls will target it unless a device argument is provided."
                        ),
                        "devices": devices,
                    }
                )

        return json.dumps(
            {
                "error": (
                    f"Unknown Android device '{requested}'. Call android_devices and choose "
                    "one of the listed device names or aliases."
                ),
                "connected_devices": connected,
                "devices": devices,
            }
        )
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_read_screen(include_bounds: bool = False) -> str:
    """
    Returns the accessibility tree of the current screen as JSON.
    Each node has: nodeId, text, contentDescription, className,
                   clickable, focusable, bounds (if include_bounds=True)
    """
    try:
        data = _get(f"/screen?bounds={str(include_bounds).lower()}")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_tap(
    x: Optional[int] = None, y: Optional[int] = None, node_id: Optional[str] = None
) -> str:
    """
    Tap at screen coordinates (x, y) or by accessibility node_id.
    Prefer node_id when available — it's more reliable than coordinates.
    """
    try:
        payload = {}
        if node_id:
            payload["nodeId"] = node_id
        elif x is not None and y is not None:
            payload["x"] = x
            payload["y"] = y
        else:
            return json.dumps({"error": "Provide either (x, y) or node_id"})
        data = _post("/tap", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_tap_text(text: str, exact: bool = False) -> str:
    """
    Tap the first element whose visible text matches `text`.
    exact=False uses contains matching. exact=True requires full match.
    Useful when you can see text on screen but don't have node IDs.
    """
    try:
        data = _post("/tap_text", {"text": text, "exact": exact})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_type(text: str, clear_first: bool = False) -> str:
    """
    Type text into the currently focused input field.
    Set clear_first=True to clear existing content before typing.
    """
    try:
        data = _post("/type", {"text": text, "clearFirst": clear_first})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_swipe(direction: str, distance: str = "medium") -> str:
    """
    Swipe in direction: up, down, left, right.
    distance: short, medium, long
    """
    try:
        data = _post("/swipe", {"direction": direction, "distance": distance})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_open_app(package: str) -> str:
    """
    Launch an app by its package name.
    Common packages:
      com.ubercab        - Uber
      com.whatsapp       - WhatsApp
      com.spotify.music  - Spotify
      com.google.android.apps.maps - Google Maps
      com.android.chrome - Chrome
      com.google.android.gm - Gmail
    """
    try:
        data = _post("/open_app", {"package": package})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_press_key(key: str) -> str:
    """
    Press a key. Supported keys:
      back, home, recents, power, volume_up, volume_down,
      enter, delete, tab, escape, search, notifications
    """
    try:
        data = _post("/press_key", {"key": key})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_screenshot() -> str:
    """
    Capture a screenshot of the Android screen.
    Saves to a temp file and returns the path.
    The gateway will auto-send the image to the user via MEDIA: tag.
    """
    try:
        import base64
        import tempfile

        data = _get("/screenshot")
        if "error" in data:
            return json.dumps(data)

        # Extract base64 image from the nested result
        result = data.get("data", data)
        img_b64 = result.get("image", "")
        if not img_b64:
            return json.dumps({"error": "No image data returned"})

        # Save to temp file
        img_bytes = base64.b64decode(img_b64)
        tmp = tempfile.NamedTemporaryFile(
            suffix=".jpg", prefix="android_screenshot_", delete=False
        )
        tmp.write(img_bytes)
        tmp.close()

        w = result.get("width", "?")
        h = result.get("height", "?")

        return f"Screenshot captured ({w}x{h})\nMEDIA:{tmp.name}"
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_scroll(direction: str, node_id: Optional[str] = None) -> str:
    """
    Scroll within a scrollable element or the whole screen.
    direction: up, down, left, right
    """
    try:
        payload = {"direction": direction}
        if node_id:
            payload["nodeId"] = node_id
        data = _post("/scroll", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_wait(
    text: str = None, class_name: str = None, timeout_ms: int = 5000
) -> str:
    """
    Wait for an element to appear on screen.
    Polls every 500ms up to timeout_ms.
    Returns the matching node if found, error if timeout.
    """
    try:
        payload = {"timeoutMs": timeout_ms}
        if text:
            payload["text"] = text
        if class_name:
            payload["className"] = class_name
        data = _post("/wait", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_get_apps() -> str:
    """List all installed apps with their package names and labels."""
    try:
        data = _get("/apps")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_current_app() -> str:
    """Get the package name and activity of the current foreground app."""
    try:
        data = _get("/current_app")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_clipboard_read() -> str:
    """
    Read the current text content of the Android device clipboard.
    Returns the clipboard text or empty string if clipboard is empty.
    """
    try:
        data = _get("/clipboard")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_clipboard_write(text: str) -> str:
    """
    Write text to the Android device clipboard.
    Useful for pasting into input fields or sharing text between apps.
    """
    try:
        data = _post("/clipboard", {"text": text})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_notifications(limit: int = 50, since: int = 0) -> str:
    """
    Read recent notifications from the Android device.
    Requires notification listener permission to be enabled.
    Returns list of notifications with package, title, text, and timestamp.
    Use since (unix ms) to get only notifications after a given time.
    """
    try:
        data = _get(f"/notifications?limit={limit}&since={since}")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_long_press(
    x: Optional[int] = None,
    y: Optional[int] = None,
    node_id: Optional[str] = None,
    duration: int = 500,
) -> str:
    """
    Perform a long press at coordinates (x, y) or on a node by node_id.
    Default duration is 500ms. Useful for context menus, widget moving, text selection.
    """
    try:
        payload = {"duration": duration}
        if node_id:
            payload["nodeId"] = node_id
        elif x is not None and y is not None:
            payload["x"] = x
            payload["y"] = y
        else:
            return json.dumps({"error": "Provide either (x, y) or node_id"})
        data = _post("/long_press", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_drag(
    start_x: int, start_y: int, end_x: int, end_y: int, duration: int = 500
) -> str:
    """
    Drag from (start_x, start_y) to (end_x, end_y).
    Useful for rearranging apps, pulling notification shade, map pin dragging.
    """
    try:
        data = _post(
            "/drag",
            {
                "startX": start_x,
                "startY": start_y,
                "endX": end_x,
                "endY": end_y,
                "duration": duration,
            },
        )
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_describe_node(node_id: str) -> str:
    """
    Get detailed properties of a specific UI node by its node_id.
    Returns all properties including checked state, hint text, bounds, child count, etc.
    """
    try:
        data = _post("/describe_node", {"nodeId": node_id})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_screen_hash() -> str:
    """
    Get a hash of the current screen content. Lightweight alternative to
    android_read_screen for detecting screen changes. Use in polling loops
    to avoid transferring the full accessibility tree repeatedly.
    """
    try:
        data = _get("/screen_hash")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_macro(steps: list, name: str = "unnamed") -> str:
    """
    Execute a sequence of android tool calls in order.
    Each step is a dict with 'tool' (tool name) and 'args' (dict of arguments).
    Stops on first failure. Use for automating repeated workflows.
    """
    import time as _time

    results = []
    for i, step in enumerate(steps):
        tool_name = step.get("tool", "")
        args = step.get("args", {})

        handler = _HANDLERS.get(tool_name)
        if handler is None:
            return json.dumps(
                {
                    "error": f"Step {i}: unknown tool '{tool_name}'",
                    "completed": i,
                    "results": results,
                }
            )

        try:
            result_str = handler(args)
            result = (
                json.loads(result_str) if isinstance(result_str, str) else result_str
            )
            results.append({"step": i, "tool": tool_name, "result": result})

            if isinstance(result, dict) and not result.get("success", True):
                return json.dumps(
                    {
                        "error": f"Step {i} ({tool_name}) failed",
                        "completed": i,
                        "results": results,
                    }
                )
        except Exception as e:
            return json.dumps(
                {
                    "error": f"Step {i} ({tool_name}) raised: {e}",
                    "completed": i,
                    "results": results,
                }
            )

        _time.sleep(0.5)

    return json.dumps(
        {"success": True, "name": name, "completed": len(steps), "results": results}
    )


def android_location() -> str:
    """
    Get the phone's current GPS location (latitude, longitude, accuracy).
    Requires location services to be enabled on the phone.
    """
    try:
        data = _get("/location")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_send_sms(to: str, body: str) -> str:
    """
    Send an SMS message directly without navigating the UI.
    Requires SMS permission on the phone.
    """
    try:
        data = _post("/send_sms", {"to": to, "body": body})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_call(number: str) -> str:
    """
    Initiate a phone call directly. Requires CALL_PHONE permission.
    The call UI will open on the phone.
    """
    try:
        data = _post("/call", {"number": number})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_speak(text: str, flush: bool = False) -> str:
    """
    Speak text aloud through the phone's speaker using text-to-speech.
    Use flush=True to interrupt current speech and speak immediately.
    """
    try:
        data = _post("/speak", {"text": text, "queue": 0 if flush else 1})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_speak_stop() -> str:
    """Stop any ongoing text-to-speech on the phone."""
    try:
        data = _post("/stop_speaking", {})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_events(limit: int = 50, since: int = 0) -> str:
    """
    Read recent accessibility events from the phone in real-time.
    Events include clicks, text changes, window transitions, scrolls, etc.
    Use since (unix ms) to get only events after a given time.
    Useful for detecting what the user is doing or what changed.
    """
    try:
        data = _get(f"/events?limit={limit}&since={since}")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_event_stream(enabled: bool = True) -> str:
    """
    Enable or disable real-time accessibility event streaming.
    When enabled, events are captured and stored for retrieval via android_events.
    Disable to stop capturing and clear the event buffer.
    """
    try:
        data = _post("/events/stream", {"enabled": enabled})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_screen_record(duration_ms: int = 5000) -> str:
    """
    Record a short video clip of the Android screen (default 5 seconds).
    Requires MediaProjection permission (granted via prompt on first use).
    Returns base64-encoded MP4 video. Save to file for playback.
    """
    try:
        import base64
        import tempfile

        data = _post("/screen_record", {"durationMs": duration_ms})
        if isinstance(data, dict) and data.get("success") and "data" in data:
            video_data = data["data"]
            video_b64 = video_data.get("video", "")
            if video_b64:
                video_bytes = base64.b64decode(video_b64)
                tmp = tempfile.NamedTemporaryFile(
                    suffix=".mp4", prefix="android_record_", delete=False
                )
                tmp.write(video_bytes)
                tmp.close()
                w = video_data.get("width", "?")
                h = video_data.get("height", "?")
                return f"Screen recorded ({w}x{h}, {duration_ms}ms)\nMEDIA:{tmp.name}"
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_find_nodes(
    text: str = None, class_name: str = None, clickable: bool = None, limit: int = 20
) -> str:
    """
    Search the current screen for nodes matching criteria.
    Returns matching nodes without dumping the full accessibility tree.
    Faster than android_read_screen when looking for specific elements.
    """
    try:
        payload = {"limit": limit}
        if text:
            payload["text"] = text
        if class_name:
            payload["className"] = class_name
        if clickable is not None:
            payload["clickable"] = clickable
        data = _post("/find_nodes", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_diff_screen(previous_hash: str) -> str:
    """
    Compare the current screen state against a previous hash.
    Returns whether the screen changed and the new hash.
    Use with android_screen_hash() for efficient change detection.
    """
    try:
        data = _post("/diff_screen", {"previousHash": previous_hash})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_pinch(x: int, y: int, scale: float = 1.5, duration: int = 300) -> str:
    """
    Perform a pinch gesture at coordinates (x, y).
    scale > 1.0 zooms in, scale < 1.0 zooms out.
    Useful for maps and photo galleries.
    """
    try:
        data = _post("/pinch", {"x": x, "y": y, "scale": scale, "duration": duration})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_read_widgets() -> str:
    """
    Read home screen widgets (weather, calendar, tasks, etc.) without
    opening apps. Goes to home screen first, then reads widget content.
    """
    try:
        data = _get("/widgets")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_media(action: str) -> str:
    """
    Control media playback on the phone. More reliable than tapping media app UI.
    Actions: play, pause, toggle (play/pause), next, previous.
    """
    try:
        data = _post("/media", {"action": action})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_search_contacts(query: str, limit: int = 20) -> str:
    """
    Search the phone's contacts by name. Returns name and phone numbers.
    Useful for finding numbers to call or send SMS to.
    """
    try:
        data = _get(f"/contacts?query={quote(query)}&limit={limit}")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_send_intent(
    action: str, data_uri: str = None, extras: dict = None, package: str = None
) -> str:
    """
    Send an Android intent to start an activity. Opens up deep linking,
    setting toggles, and app-specific APIs.
    Example: android_send_intent("android.settings.WIFI_SETTINGS")
    """
    try:
        payload = {"action": action}
        if data_uri:
            payload["dataUri"] = data_uri
        if extras:
            payload["extras"] = extras
        if package:
            payload["packageOverride"] = package
        data = _post("/intent", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_broadcast(action: str, extras: dict = None) -> str:
    """
    Send an Android broadcast intent. Useful for triggering system events
    or app-specific receivers.
    """
    try:
        payload = {"action": action}
        if extras:
            payload["extras"] = extras
        data = _post("/broadcast", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def _get_public_ip() -> str:
    """Detect this server's public IP address."""
    for service in [
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://icanhazip.com",
    ]:
        try:
            r = requests.get(service, timeout=3)
            if r.status_code == 200:
                return r.text.strip()
        except Exception:
            continue
    # Fallback: hostname
    import socket

    try:
        return socket.gethostbyname(socket.gethostname())
    except Exception:
        return "<your-server-ip>"


def android_setup(pairing_code: str) -> str:
    """
    Start the Android bridge relay and configure the pairing code.
    The relay runs on this server and waits for the phone to connect via WebSocket.

    The user needs to:
    1. Open the Hermes Bridge app on their phone
    2. Enter this server's public IP and the pairing code
    3. The phone connects to the relay automatically

    Call this when the user provides their pairing code from the Hermes Bridge app.
    Example: android_setup("K7V3NP")
    """
    try:
        port = _relay_port()
        public_ip = _get_public_ip()
        pairing_codes = [
            code.strip().upper()
            for code in pairing_code.replace(";", ",").split(",")
            if code.strip()
        ]
        bridge_token = pairing_codes[0] if pairing_codes else pairing_code

        # Save config to ~/.hermes/.env
        relay_url = f"http://localhost:{port}"
        try:
            from hermes_cli.config import save_env_value

            save_env_value("ANDROID_BRIDGE_URL", relay_url)
            save_env_value("ANDROID_BRIDGE_TOKEN", bridge_token)
            save_env_value("ANDROID_BRIDGE_TOKENS", ",".join(pairing_codes))
            save_env_value("ANDROID_RELAY_PORT", str(port))
        except ImportError:
            from pathlib import Path

            env_path = Path.home() / ".hermes" / ".env"
            env_path.parent.mkdir(parents=True, exist_ok=True)
            _update_env_file(env_path, "ANDROID_BRIDGE_URL", relay_url)
            _update_env_file(env_path, "ANDROID_BRIDGE_TOKEN", bridge_token)
            _update_env_file(env_path, "ANDROID_BRIDGE_TOKENS", ",".join(pairing_codes))
            _update_env_file(env_path, "ANDROID_RELAY_PORT", str(port))

        # Update current process env
        os.environ["ANDROID_BRIDGE_URL"] = relay_url
        os.environ["ANDROID_BRIDGE_TOKEN"] = bridge_token
        os.environ["ANDROID_BRIDGE_TOKENS"] = ",".join(pairing_codes)

        # Start the relay server
        try:
            from .android_relay import start_relay, is_phone_connected

            start_relay(pairing_code=pairing_code, port=port)

            # Check if phone is already connected
            time.sleep(1)
            phone_connected = is_phone_connected()

            server_address = f"{public_ip}:{port}"

            if phone_connected:
                return json.dumps(
                    {
                        "status": "ok",
                        "message": "Phone is connected and ready!",
                        "phone_connected": True,
                        "server_address": server_address,
                    }
                )
            else:
                return json.dumps(
                    {
                        "status": "ok",
                        "message": "Relay is running. Now tell the user to connect their phone.",
                        "phone_connected": False,
                        "server_address": server_address,
                        "user_instructions": (
                            f"Open the Hermes Bridge app on your phone and enter:\n"
                            f"  Server: {server_address}\n"
                            f"  Pairing code: {pairing_code}\n"
                            f"Then tap Connect."
                        ),
                    }
                )
        except ImportError:
            return json.dumps(
                {
                    "status": "error",
                    "message": "android_relay module not found. Make sure hermes-android plugin is installed.",
                }
            )

    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def _update_env_file(env_path, key: str, value: str):
    """Simple .env file updater (fallback when hermes_cli.config not available)."""
    lines = []
    if env_path.exists():
        lines = env_path.read_text(encoding="utf-8", errors="replace").splitlines(True)
    found = False
    for i, line in enumerate(lines):
        if line.strip().startswith(f"{key}="):
            lines[i] = f"{key}={value}\n"
            found = True
            break
    if not found:
        if lines and not lines[-1].endswith("\n"):
            lines[-1] += "\n"
        lines.append(f"{key}={value}\n")
    env_path.write_text("".join(lines), encoding="utf-8")


# ── Schema definitions ─────────────────────────────────────────────────────────

_SCHEMAS = {
    "android_ping": {
        "name": "android_ping",
        "description": "Check if the Android bridge is reachable. Call this first before any other android_ tools.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_devices": {
        "name": "android_devices",
        "description": "List configured Android devices, aliases, connection state, and the currently selected target device. Use this before choosing a device.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_select_device": {
        "name": "android_select_device",
        "description": "Select which Android device subsequent android_* calls should target. Call android_devices first when the user refers to a phone ambiguously. After selecting, call android_read_screen before tapping.",
        "parameters": {
            "type": "object",
            "properties": {
                "device": {
                    "type": "string",
                    "description": "Device alias from android_devices, such as old, new, lrw2u7, or emdmfu.",
                }
            },
            "required": ["device"],
        },
    },
    "android_read_screen": {
        "name": "android_read_screen",
        "description": "Get the accessibility tree of the current Android screen. Returns all visible UI nodes with text, class names, node IDs, and interactability. Use this to understand what's on screen before tapping.",
        "parameters": {
            "type": "object",
            "properties": {
                "include_bounds": {
                    "type": "boolean",
                    "description": "Include pixel coordinates for each node. Default false.",
                    "default": False,
                }
            },
            "required": [],
        },
    },
    "android_tap": {
        "name": "android_tap",
        "description": "Tap a UI element by node_id (preferred) or by screen coordinates (x, y). Always prefer node_id over coordinates — it's more reliable. Get node_ids from android_read_screen.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate in pixels"},
                "y": {"type": "integer", "description": "Y coordinate in pixels"},
                "node_id": {
                    "type": "string",
                    "description": "Accessibility node ID from android_read_screen",
                },
            },
            "required": [],
        },
    },
    "android_tap_text": {
        "name": "android_tap_text",
        "description": "Tap the first visible UI element matching the given text. Useful when you see text on screen and want to tap it without needing node IDs.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to find and tap"},
                "exact": {
                    "type": "boolean",
                    "description": "Exact match (true) or contains match (false, default)",
                    "default": False,
                },
            },
            "required": ["text"],
        },
    },
    "android_type": {
        "name": "android_type",
        "description": "Type text into the currently focused input field. Tap the field first using android_tap or android_tap_text.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to type"},
                "clear_first": {
                    "type": "boolean",
                    "description": "Clear existing content before typing",
                    "default": False,
                },
            },
            "required": ["text"],
        },
    },
    "android_swipe": {
        "name": "android_swipe",
        "description": "Perform a swipe gesture on screen.",
        "parameters": {
            "type": "object",
            "properties": {
                "direction": {
                    "type": "string",
                    "enum": ["up", "down", "left", "right"],
                },
                "distance": {
                    "type": "string",
                    "enum": ["short", "medium", "long"],
                    "default": "medium",
                },
            },
            "required": ["direction"],
        },
    },
    "android_open_app": {
        "name": "android_open_app",
        "description": "Launch an Android app by its package name. Use android_get_apps to find package names.",
        "parameters": {
            "type": "object",
            "properties": {
                "package": {
                    "type": "string",
                    "description": "App package name e.g. com.ubercab",
                },
            },
            "required": ["package"],
        },
    },
    "android_press_key": {
        "name": "android_press_key",
        "description": "Press a hardware or software key.",
        "parameters": {
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "enum": [
                        "back",
                        "home",
                        "recents",
                        "power",
                        "volume_up",
                        "volume_down",
                        "enter",
                        "delete",
                        "tab",
                        "escape",
                        "search",
                        "notifications",
                    ],
                }
            },
            "required": ["key"],
        },
    },
    "android_screenshot": {
        "name": "android_screenshot",
        "description": "Take a screenshot of the current Android screen. Returns base64 PNG. Use when the accessibility tree is missing context or the screen uses canvas/game rendering.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_scroll": {
        "name": "android_scroll",
        "description": "Scroll the screen or a specific scrollable element.",
        "parameters": {
            "type": "object",
            "properties": {
                "direction": {
                    "type": "string",
                    "enum": ["up", "down", "left", "right"],
                },
                "node_id": {
                    "type": "string",
                    "description": "Node ID of scrollable container (optional, defaults to screen scroll)",
                },
            },
            "required": ["direction"],
        },
    },
    "android_wait": {
        "name": "android_wait",
        "description": "Wait for a UI element to appear on screen. Use after actions that trigger loading or navigation.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "Wait for element with this text",
                },
                "class_name": {
                    "type": "string",
                    "description": "Wait for element of this class",
                },
                "timeout_ms": {
                    "type": "integer",
                    "description": "Max wait time in milliseconds",
                    "default": 5000,
                },
            },
            "required": [],
        },
    },
    "android_get_apps": {
        "name": "android_get_apps",
        "description": "List all installed apps on the Android device with their package names and display labels.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_current_app": {
        "name": "android_current_app",
        "description": "Get the package name and activity name of the currently active (foreground) Android app.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_setup": {
        "name": "android_setup",
        "description": "Start the Android bridge relay and set the pairing code. Call this when the user wants to connect their phone. The relay runs on this server — the phone connects to it remotely via WebSocket. Only needs the pairing code shown in the Hermes Bridge app on the phone.",
        "parameters": {
            "type": "object",
            "properties": {
                "pairing_code": {
                    "type": "string",
                    "description": "6-character pairing code shown in the Hermes Bridge app on the phone",
                },
            },
            "required": ["pairing_code"],
        },
    },
    "android_clipboard_read": {
        "name": "android_clipboard_read",
        "description": "Read the current text content of the Android device clipboard. Returns the clipboard text or empty string if clipboard is empty.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_clipboard_write": {
        "name": "android_clipboard_write",
        "description": "Write text to the Android device clipboard. Useful for pasting into input fields or sharing text between apps.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "Text to copy to the clipboard",
                },
            },
            "required": ["text"],
        },
    },
    "android_notifications": {
        "name": "android_notifications",
        "description": "Read recent notifications from the Android device. Requires notification listener permission. Returns notifications with package name, title, text, and timestamp.",
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Max number of notifications to return (default 50)",
                    "default": 50,
                },
                "since": {
                    "type": "integer",
                    "description": "Only return notifications after this Unix timestamp in milliseconds (default 0 = all)",
                    "default": 0,
                },
            },
            "required": [],
        },
    },
    "android_long_press": {
        "name": "android_long_press",
        "description": "Long press a UI element to trigger context menus, text selection, or widget moving. Supports coordinates or node_id.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate in pixels"},
                "y": {"type": "integer", "description": "Y coordinate in pixels"},
                "node_id": {
                    "type": "string",
                    "description": "Accessibility node ID from android_read_screen",
                },
                "duration": {
                    "type": "integer",
                    "description": "Press duration in milliseconds (default 500)",
                    "default": 500,
                },
            },
            "required": [],
        },
    },
    "android_drag": {
        "name": "android_drag",
        "description": "Drag from one point to another. Useful for rearranging apps, pulling notification shade, map pin dragging, and scrollable content.",
        "parameters": {
            "type": "object",
            "properties": {
                "start_x": {"type": "integer", "description": "Start X coordinate"},
                "start_y": {"type": "integer", "description": "Start Y coordinate"},
                "end_x": {"type": "integer", "description": "End X coordinate"},
                "end_y": {"type": "integer", "description": "End Y coordinate"},
                "duration": {
                    "type": "integer",
                    "description": "Drag duration in milliseconds (default 500)",
                    "default": 500,
                },
            },
            "required": ["start_x", "start_y", "end_x", "end_y"],
        },
    },
    "android_describe_node": {
        "name": "android_describe_node",
        "description": "Get detailed properties of a specific UI node. Returns all properties including checked state, hint text, bounds, child count, viewIdResourceName, etc.",
        "parameters": {
            "type": "object",
            "properties": {
                "node_id": {
                    "type": "string",
                    "description": "Accessibility node ID from android_read_screen",
                },
            },
            "required": ["node_id"],
        },
    },
    "android_screen_hash": {
        "name": "android_screen_hash",
        "description": "Get a lightweight hash of the current screen content. Use for change detection in polling loops instead of repeatedly calling android_read_screen.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_macro": {
        "name": "android_macro",
        "description": "Execute a sequence of android tool calls in order. Each step is a dict with 'tool' and 'args'. Stops on first failure. Use for automating repeated multi-step workflows.",
        "parameters": {
            "type": "object",
            "properties": {
                "steps": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "tool": {
                                "type": "string",
                                "description": "Tool name e.g. android_tap",
                            },
                            "args": {
                                "type": "object",
                                "description": "Arguments for the tool",
                            },
                        },
                        "required": ["tool"],
                    },
                    "description": "Ordered list of tool calls to execute",
                },
                "name": {
                    "type": "string",
                    "description": "Name for this macro (for logging)",
                    "default": "unnamed",
                },
            },
            "required": ["steps"],
        },
    },
    "android_location": {
        "name": "android_location",
        "description": "Get the phone's current GPS location. Returns latitude, longitude, accuracy, and provider. Requires location services enabled.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_send_sms": {
        "name": "android_send_sms",
        "description": "Send an SMS message directly without navigating the messaging app. Requires SMS permission.",
        "parameters": {
            "type": "object",
            "properties": {
                "to": {"type": "string", "description": "Phone number to send SMS to"},
                "body": {"type": "string", "description": "SMS message text"},
            },
            "required": ["to", "body"],
        },
    },
    "android_call": {
        "name": "android_call",
        "description": "Initiate a phone call. Opens the phone call UI on the device.",
        "parameters": {
            "type": "object",
            "properties": {
                "number": {"type": "string", "description": "Phone number to call"},
            },
            "required": ["number"],
        },
    },
    "android_speak": {
        "name": "android_speak",
        "description": "Speak text aloud through the phone speaker using text-to-speech. Useful for voice feedback, reading messages aloud, or agent personality.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to speak aloud"},
                "flush": {
                    "type": "boolean",
                    "description": "Interrupt current speech and speak immediately (default false)",
                    "default": False,
                },
            },
            "required": ["text"],
        },
    },
    "android_speak_stop": {
        "name": "android_speak_stop",
        "description": "Stop any ongoing text-to-speech playback on the phone.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_events": {
        "name": "android_events",
        "description": "Read recent accessibility events (clicks, text changes, window transitions, scrolls) from the phone. Real-time UI change detection.",
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Max events to return (default 50)",
                    "default": 50,
                },
                "since": {
                    "type": "integer",
                    "description": "Only events after this Unix timestamp in ms (default 0 = all)",
                    "default": 0,
                },
            },
            "required": [],
        },
    },
    "android_event_stream": {
        "name": "android_event_stream",
        "description": "Enable/disable accessibility event streaming. When enabled, events are captured for retrieval via android_events.",
        "parameters": {
            "type": "object",
            "properties": {
                "enabled": {
                    "type": "boolean",
                    "description": "Enable (true) or disable (false) event streaming",
                    "default": True,
                },
            },
            "required": [],
        },
    },
    "android_screen_record": {
        "name": "android_screen_record",
        "description": "Record a short video clip of the Android screen (default 5 seconds). Returns MP4 video file. Useful for animated UIs, debugging, and demos.",
        "parameters": {
            "type": "object",
            "properties": {
                "duration_ms": {
                    "type": "integer",
                    "description": "Recording duration in milliseconds (default 5000)",
                    "default": 5000,
                },
            },
            "required": [],
        },
    },
    "android_read_widgets": {
        "name": "android_read_widgets",
        "description": "Read home screen widgets (weather, calendar, tasks, etc.). Goes to home screen and reads widget content without opening apps.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_find_nodes": {
        "name": "android_find_nodes",
        "description": "Search the current screen for UI nodes matching text, class name, or clickability. Returns matching nodes without dumping the full tree. Faster than android_read_screen for finding specific elements.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "Text to search for (case-insensitive contains match)",
                },
                "class_name": {
                    "type": "string",
                    "description": "Android class name to filter by (e.g. android.widget.Button)",
                },
                "clickable": {
                    "type": "boolean",
                    "description": "Filter by clickability",
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results to return (default 20)",
                    "default": 20,
                },
            },
            "required": [],
        },
    },
    "android_diff_screen": {
        "name": "android_diff_screen",
        "description": "Compare current screen against a previous hash. Returns changed status and new hash. Use with android_screen_hash for efficient polling.",
        "parameters": {
            "type": "object",
            "properties": {
                "previous_hash": {
                    "type": "string",
                    "description": "Previous screen hash from android_screen_hash()",
                },
            },
            "required": ["previous_hash"],
        },
    },
    "android_pinch": {
        "name": "android_pinch",
        "description": "Pinch gesture at a point. scale > 1.0 zooms in, scale < 1.0 zooms out. Use for maps and photo galleries.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "Center X coordinate"},
                "y": {"type": "integer", "description": "Center Y coordinate"},
                "scale": {
                    "type": "number",
                    "description": "Zoom scale factor (>1 zoom in, <1 zoom out, default 1.5)",
                    "default": 1.5,
                },
                "duration": {
                    "type": "integer",
                    "description": "Gesture duration in ms (default 300)",
                    "default": 300,
                },
            },
            "required": ["x", "y"],
        },
    },
    "android_media": {
        "name": "android_media",
        "description": "Control media playback (play, pause, toggle, next, previous). Works system-wide without opening media apps.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["play", "pause", "toggle", "next", "previous"],
                    "description": "Media action",
                },
            },
            "required": ["action"],
        },
    },
    "android_search_contacts": {
        "name": "android_search_contacts",
        "description": "Search phone contacts by name. Returns contact names and phone numbers. Use before android_call or android_send_sms.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Name to search for"},
                "limit": {
                    "type": "integer",
                    "description": "Max results (default 20)",
                    "default": 20,
                },
            },
            "required": ["query"],
        },
    },
    "android_send_intent": {
        "name": "android_send_intent",
        "description": "Send an Android intent to start an activity. Enables deep linking, settings, and app-specific APIs.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "Intent action e.g. android.settings.WIFI_SETTINGS",
                },
                "data_uri": {
                    "type": "string",
                    "description": "Optional data URI e.g. tel:+1234567890",
                },
                "extras": {
                    "type": "object",
                    "description": "Optional string extras as key-value pairs",
                },
                "package": {
                    "type": "string",
                    "description": "Optional target package name",
                },
            },
            "required": ["action"],
        },
    },
    "android_broadcast": {
        "name": "android_broadcast",
        "description": "Send an Android broadcast intent. Triggers system events or app-specific receivers.",
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "Broadcast action e.g. android.intent.action.AIRPLANE_MODE",
                },
                "extras": {
                    "type": "object",
                    "description": "Optional string extras as key-value pairs",
                },
            },
            "required": ["action"],
        },
    },
}

_DEVICE_PARAMETER = {
    "type": "string",
    "description": "Optional Android device alias or pairing token to target when multiple devices are connected.",
}

for _tool_name, _schema in _SCHEMAS.items():
    if _tool_name in {"android_setup", "android_devices", "android_select_device"}:
        continue
    _schema["parameters"].setdefault("properties", {})["device"] = _DEVICE_PARAMETER


# ── Tool handlers map ──────────────────────────────────────────────────────────


def _with_target_device(args: dict, handler):
    call_args = dict(args or {})
    device = call_args.pop("device", None)
    token = _CURRENT_DEVICE.set(device)
    try:
        return handler(call_args)
    finally:
        _CURRENT_DEVICE.reset(token)

_HANDLERS = {
    "android_ping": lambda args, **kw: _with_target_device(args, lambda _args: android_ping()),
    "android_devices": lambda args, **kw: android_devices(),
    "android_select_device": lambda args, **kw: android_select_device(**args),
    "android_read_screen": lambda args, **kw: _with_target_device(args, lambda call_args: android_read_screen(**call_args)),
    "android_tap": lambda args, **kw: _with_target_device(args, lambda call_args: android_tap(**call_args)),
    "android_tap_text": lambda args, **kw: _with_target_device(args, lambda call_args: android_tap_text(**call_args)),
    "android_type": lambda args, **kw: _with_target_device(args, lambda call_args: android_type(**call_args)),
    "android_swipe": lambda args, **kw: _with_target_device(args, lambda call_args: android_swipe(**call_args)),
    "android_open_app": lambda args, **kw: _with_target_device(args, lambda call_args: android_open_app(**call_args)),
    "android_press_key": lambda args, **kw: _with_target_device(args, lambda call_args: android_press_key(**call_args)),
    "android_screenshot": lambda args, **kw: _with_target_device(args, lambda _args: android_screenshot()),
    "android_scroll": lambda args, **kw: _with_target_device(args, lambda call_args: android_scroll(**call_args)),
    "android_wait": lambda args, **kw: _with_target_device(args, lambda call_args: android_wait(**call_args)),
    "android_get_apps": lambda args, **kw: _with_target_device(args, lambda _args: android_get_apps()),
    "android_current_app": lambda args, **kw: _with_target_device(args, lambda _args: android_current_app()),
    "android_setup": lambda args, **kw: android_setup(**args),
    "android_clipboard_read": lambda args, **kw: _with_target_device(args, lambda _args: android_clipboard_read()),
    "android_clipboard_write": lambda args, **kw: _with_target_device(args, lambda call_args: android_clipboard_write(**call_args)),
    "android_notifications": lambda args, **kw: _with_target_device(args, lambda call_args: android_notifications(**call_args)),
    "android_long_press": lambda args, **kw: _with_target_device(args, lambda call_args: android_long_press(**call_args)),
    "android_drag": lambda args, **kw: _with_target_device(args, lambda call_args: android_drag(**call_args)),
    "android_describe_node": lambda args, **kw: _with_target_device(args, lambda call_args: android_describe_node(**call_args)),
    "android_screen_hash": lambda args, **kw: _with_target_device(args, lambda _args: android_screen_hash()),
    "android_macro": lambda args, **kw: _with_target_device(args, lambda call_args: android_macro(**call_args)),
    "android_location": lambda args, **kw: _with_target_device(args, lambda _args: android_location()),
    "android_send_sms": lambda args, **kw: _with_target_device(args, lambda call_args: android_send_sms(**call_args)),
    "android_call": lambda args, **kw: _with_target_device(args, lambda call_args: android_call(**call_args)),
    "android_speak": lambda args, **kw: _with_target_device(args, lambda call_args: android_speak(**call_args)),
    "android_speak_stop": lambda args, **kw: _with_target_device(args, lambda _args: android_speak_stop()),
    "android_events": lambda args, **kw: _with_target_device(args, lambda call_args: android_events(**call_args)),
    "android_event_stream": lambda args, **kw: _with_target_device(args, lambda call_args: android_event_stream(**call_args)),
    "android_screen_record": lambda args, **kw: _with_target_device(args, lambda call_args: android_screen_record(**call_args)),
    "android_read_widgets": lambda args, **kw: _with_target_device(args, lambda _args: android_read_widgets()),
    "android_find_nodes": lambda args, **kw: _with_target_device(args, lambda call_args: android_find_nodes(**call_args)),
    "android_diff_screen": lambda args, **kw: _with_target_device(args, lambda call_args: android_diff_screen(**call_args)),
    "android_pinch": lambda args, **kw: _with_target_device(args, lambda call_args: android_pinch(**call_args)),
    "android_media": lambda args, **kw: _with_target_device(args, lambda call_args: android_media(**call_args)),
    "android_search_contacts": lambda args, **kw: _with_target_device(args, lambda call_args: android_search_contacts(**call_args)),
    "android_send_intent": lambda args, **kw: _with_target_device(args, lambda call_args: android_send_intent(**call_args)),
    "android_broadcast": lambda args, **kw: _with_target_device(args, lambda call_args: android_broadcast(**call_args)),
}
