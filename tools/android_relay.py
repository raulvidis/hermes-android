"""
android_relay — WebSocket relay that bridges HTTP tool calls to a phone over WS.

The relay runs an aiohttp server exposing:
  - /ws          WebSocket endpoint the phone connects to (Authorization: Bearer *** header for auth)
  - /ping, /screen, /tap, /tap_text, /type, /swipe, /open_app, /press_key,
    /screenshot, /scroll, /wait, /apps, /current_app   HTTP endpoints matching
    the bridge API consumed by android_tool.py

Flow:
  1. Phone connects via WebSocket with Bearer token in Authorization header
  2. Python tool makes an HTTP request to e.g. /screen
  3. Relay wraps request as JSON command, sends over WS to phone
  4. Phone executes command, sends JSON response back over WS
  5. Relay returns the phone's response to the HTTP caller

Command JSON format:
  Relay -> Phone:  {"request_id": "uuid", "method": "GET|POST", "path": "/screen",
                    "params": {...}, "body": {...}}
  Phone -> Relay:  {"request_id": "uuid", "result": {...}, "status": 200}
"""

# TLS: Set ANDROID_RELAY_CERT and ANDROID_RELAY_KEY to PEM file paths
# to enable wss:// connections. Without these, the relay uses plaintext http.

import asyncio
import hashlib
import hmac
import json
import logging
import os
import re
import threading
import time
import uuid
from typing import Optional

import aiohttp
from aiohttp import web

logger = logging.getLogger("android_relay")

# ── Module-level state ────────────────────────────────────────────────────────

_relay_lock = threading.Lock()
_relay_instance: Optional["_RelayState"] = None


class _RelayState:
    """Holds all mutable state for one running relay instance."""

    def __init__(self, pairing_code: str, port: int):
        self.pairing_code: str = pairing_code
        self.port: int = port

        # asyncio loop running in the background thread
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.thread: Optional[threading.Thread] = None

        # aiohttp plumbing
        self.app: Optional[web.Application] = None
        self.runner: Optional[web.AppRunner] = None
        self.site: Optional[web.TCPSite] = None

        # The single connected phone WebSocket (or None)
        self.phone_ws: Optional[web.WebSocketResponse] = None
        self.phone_ws_lock = asyncio.Lock()  # created lazily in the event loop

        # Pending requests: request_id -> asyncio.Future
        self.pending: dict[str, asyncio.Future] = {}
        self.pending_lock: Optional[asyncio.Lock] = None  # created lazily

        # Binary HTTP responses (currently microphone WAV downloads):
        # request_id -> bounded queue of ("message"|"chunk"|"error", payload).
        self.streams: dict[str, asyncio.Queue] = {}
        self.streams_lock: Optional[asyncio.Lock] = None

        # Shutdown event
        self.shutdown_event: Optional[asyncio.Event] = None


# ── Public API (called from sync code) ────────────────────────────────────────


def start_relay(pairing_code: str, port: int = 0) -> None:
    """Start the relay in a background thread.  No-op if already running."""
    global _relay_instance
    if port == 0:
        port = int(os.getenv("ANDROID_RELAY_PORT", "8766"))

    with _relay_lock:
        if (
            _relay_instance is not None
            and _relay_instance.thread is not None
            and _relay_instance.thread.is_alive()
        ):
            logger.info("Relay already running on port %d", _relay_instance.port)
            return

        state = _RelayState(pairing_code, port)
        _relay_instance = state

        ready = threading.Event()
        t = threading.Thread(
            target=_run_loop, args=(state, ready), daemon=True, name="android-relay"
        )
        state.thread = t
        t.start()
        # Wait until the server is actually listening (up to 10 s)
        if not ready.wait(timeout=10):
            logger.error("Relay failed to start within 10 seconds")
            raise RuntimeError("Relay failed to start")
        logger.info("Relay started on port %d", port)


def stop_relay() -> None:
    """Gracefully stop the relay."""
    global _relay_instance
    with _relay_lock:
        state = _relay_instance
        if state is None:
            return
        _relay_instance = None

    # Signal the event loop to shut down
    if state.loop is not None and state.shutdown_event is not None:
        state.loop.call_soon_threadsafe(state.shutdown_event.set)
    if state.thread is not None:
        state.thread.join(timeout=5)
    logger.info("Relay stopped")


def is_relay_running() -> bool:
    with _relay_lock:
        s = _relay_instance
        return s is not None and s.thread is not None and s.thread.is_alive()


def is_phone_connected() -> bool:
    with _relay_lock:
        s = _relay_instance
        if s is None:
            return False
        ws = s.phone_ws
        return ws is not None and not ws.closed


def get_relay_url() -> str:
    with _relay_lock:
        s = _relay_instance
        port = s.port if s else int(os.getenv("ANDROID_RELAY_PORT", "8766"))
    return f"http://localhost:{port}"


def set_pairing_code(code: str) -> None:
    """Update the pairing code (e.g. when user reconnects with new code)."""
    with _relay_lock:
        s = _relay_instance
        if s is not None:
            s.pairing_code = code
            logger.info("Pairing code updated")


# ── Background event-loop entry point ─────────────────────────────────────────


def _run_loop(state: _RelayState, ready: threading.Event) -> None:
    """Runs in the background thread — creates an event loop and serves."""
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    state.loop = loop
    state.phone_ws_lock = asyncio.Lock()
    state.pending_lock = asyncio.Lock()
    state.streams_lock = asyncio.Lock()
    state.shutdown_event = asyncio.Event()

    try:
        loop.run_until_complete(_serve(state, ready))
    except Exception:
        logger.exception("Relay event loop crashed")
    finally:
        loop.run_until_complete(loop.shutdown_asyncgens())
        loop.close()


def _ssl_context() -> Optional["ssl.SSLContext"]:
    """Build SSL context from env vars if configured."""
    import ssl as _ssl

    cert_path = os.getenv("ANDROID_RELAY_CERT")
    key_path = os.getenv("ANDROID_RELAY_KEY")

    if not cert_path or not key_path:
        return None

    ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert_path, key_path)
    return ctx


async def _serve(state: _RelayState, ready: threading.Event) -> None:
    """Build the aiohttp app, start the site, and block until shutdown."""
    app = web.Application()
    state.app = app

    # WebSocket endpoint
    async def websocket_handler(request: web.Request) -> web.WebSocketResponse:
        return await _handle_ws(request, state)

    app.router.add_get("/ws", websocket_handler)

    # HTTP bridge endpoints — method per path
    ROUTES = {
        # GET-only
        "/ping":          "GET",
        "/screen":        "GET",
        "/screenshot":    "GET",
        "/apps":          "GET",
        "/current_app":   "GET",
        "/notifications": "GET",
        "/contacts":      "GET",
        "/events":        "GET",
        "/screen_hash":   "GET",
        "/location":      "GET",
        "/widgets":       "GET",
        "/mic_status":    "GET",
        "/mic_file":      "GET",
        # POST-only
        "/tap":           "POST",
        "/tap_text":      "POST",
        "/type":          "POST",
        "/swipe":         "POST",
        "/open_app":      "POST",
        "/press_key":     "POST",
        "/scroll":        "POST",
        "/wait":          "POST",
        "/long_press":    "POST",
        "/drag":          "POST",
        "/describe_node": "POST",
        "/find_nodes":    "POST",
        "/diff_screen":   "POST",
        "/pinch":         "POST",
        "/send_sms":      "POST",
        "/call":          "POST",
        "/media":         "POST",
        "/intent":        "POST",
        "/broadcast":     "POST",
        "/speak":         "POST",
        "/stop_speaking": "POST",
        "/screen_record": "POST",
        "/events/stream": "POST",
        "/mic_start":     "POST",
        "/mic_stop":      "POST",
        # READ + WRITE
        "/clipboard":     "BOTH",
    }

    for path, method in ROUTES.items():
        async def handler(request: web.Request, route_path: str = path) -> web.StreamResponse:
            return await _handle_http(request, state, route_path)

        if method in ("GET", "BOTH"):
            app.router.add_get(path, handler)
        if method in ("POST", "BOTH"):
            app.router.add_post(path, handler)

    runner = web.AppRunner(app)
    state.runner = runner
    await runner.setup()

    ssl_ctx = _ssl_context()
    host = os.getenv("ANDROID_RELAY_HOST", "localhost")
    site = web.TCPSite(runner, host, state.port, ssl_context=ssl_ctx)
    state.site = site
    await site.start()

    scheme = "https" if ssl_ctx else "http"
    logger.info("Relay listening on %s://%s:%d", scheme, host, state.port)

    if not ssl_ctx and host != "localhost":
        logger.warning(
            "⚠️  TLS is NOT configured — all traffic (including pairing tokens) is "
            "sent in cleartext. Set ANDROID_RELAY_CERT and ANDROID_RELAY_KEY env vars "
            "to enable TLS. Binding to %s without TLS is insecure for internet-facing use.",
            host,
        )
    ready.set()

    # Block until shutdown is signalled
    await state.shutdown_event.wait()

    # Cleanup
    await _cleanup_phone(state, reason="relay shutdown")
    await runner.cleanup()
    logger.info("Relay server cleaned up")


# ── Rate limiting for WebSocket auth ─────────────────────────────────────────

_AUTH_MAX_ATTEMPTS = 5  # max failed attempts before blocking
_AUTH_WINDOW_SECONDS = 60  # sliding window for counting failures
_AUTH_BLOCK_SECONDS = 300  # how long to block an IP (5 minutes)
_AUTH_CLEANUP_INTERVAL = 120  # seconds between cleanup sweeps

# {ip: [timestamp, timestamp, ...]} — tracks failed auth attempt times per IP
_auth_failures: dict[str, list[float]] = {}
# {ip: unblock_timestamp} — IPs currently blocked
_auth_blocked: dict[str, float] = {}
_auth_lock = threading.Lock()
_auth_last_cleanup: float = 0.0


def _auth_cleanup() -> None:
    """Remove expired entries from the failure and block dicts."""
    global _auth_last_cleanup
    now = time.monotonic()
    if now - _auth_last_cleanup < _AUTH_CLEANUP_INTERVAL:
        return
    _auth_last_cleanup = now

    # Remove expired blocks
    expired_blocks = [ip for ip, until in _auth_blocked.items() if now >= until]
    for ip in expired_blocks:
        del _auth_blocked[ip]

    # Remove stale failure windows
    cutoff = now - _AUTH_WINDOW_SECONDS
    stale = []
    for ip, timestamps in _auth_failures.items():
        _auth_failures[ip] = [t for t in timestamps if t > cutoff]
        if not _auth_failures[ip]:
            stale.append(ip)
    for ip in stale:
        del _auth_failures[ip]


def _auth_is_blocked(ip: str) -> bool:
    """Check whether *ip* is currently blocked. Also triggers periodic cleanup."""
    now = time.monotonic()
    with _auth_lock:
        _auth_cleanup()
        until = _auth_blocked.get(ip)
        if until is not None:
            if now < until:
                return True
            # Block expired — remove it
            del _auth_blocked[ip]
    return False


def _auth_record_failure(ip: str) -> None:
    """Record a failed auth attempt for *ip*; block if threshold reached."""
    now = time.monotonic()
    with _auth_lock:
        timestamps = _auth_failures.setdefault(ip, [])
        timestamps.append(now)
        # Prune timestamps outside the window
        cutoff = now - _AUTH_WINDOW_SECONDS
        _auth_failures[ip] = [t for t in timestamps if t > cutoff]

        if len(_auth_failures[ip]) >= _AUTH_MAX_ATTEMPTS:
            _auth_blocked[ip] = now + _AUTH_BLOCK_SECONDS
            _auth_failures.pop(ip, None)
            logger.warning(
                "IP %s blocked for %ds after %d failed auth attempts",
                ip,
                _AUTH_BLOCK_SECONDS,
                _AUTH_MAX_ATTEMPTS,
            )


# ── WebSocket handler (phone side) ───────────────────────────────────────────


def _mask_token(token: str) -> str:
    # Never emit even a prefix: the pairing token grants full device control.
    return "****"


# Request-body fields that may carry PII or secrets (phone numbers, SMS text,
# typed text incl. passwords/OTPs, clipboard content, intent extras).
# AGENTS.md: strip phone numbers, recipients, location from tool logs.
_SENSITIVE_BODY_KEYS = frozenset(
    {"to", "number", "body", "text", "message", "extras", "data", "uri", "content"}
)


def _safe_body_repr(body: dict) -> str:
    """JSON repr of a request body with PII-bearing fields redacted."""
    if not body:
        return "{}"
    redacted = {
        k: "<redacted>" if k in _SENSITIVE_BODY_KEYS else v for k, v in body.items()
    }
    return json.dumps(redacted)[:200]


async def _handle_ws(request: web.Request, state: _RelayState) -> web.WebSocketResponse:
    # Rate limiting — check before token validation
    remote_ip = request.remote or "unknown"
    if _auth_is_blocked(remote_ip):
        logger.warning("Auth attempt from blocked IP %s — returning 429", remote_ip)
        raise web.HTTPTooManyRequests(
            text="Too many failed authentication attempts. Try again later."
        )

    # Authorization header only — the ?token= query string fallback was removed
    # because query strings are logged verbatim by reverse proxies (nginx,
    # Cloudflare, Apache), leaking the pairing code into plaintext access logs.
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Bearer "):
        token = auth_header.removeprefix("Bearer ").strip()
    else:
        token = ""
    # Constant-time comparison to mitigate timing side-channel attacks that
    # could otherwise leak the pairing code byte-by-byte.
    # PairingManager generates uppercase-only codes — compare exact-case to
    # preserve full key-space entropy.
    if not hmac.compare_digest(token, state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning(
            "Phone WS rejected — bad token (got %s) from %s", _mask_token(token), remote_ip
        )
        raise web.HTTPForbidden(text="Invalid pairing code")

    ws = web.WebSocketResponse(heartbeat=15.0)
    await ws.prepare(request)

    # Only one phone at a time — kick previous if any
    async with state.phone_ws_lock:
        if state.phone_ws is not None and not state.phone_ws.closed:
            logger.info("Replacing previous phone connection")
            await _fail_inflight(state, reason="phone connection replaced")
            await state.phone_ws.close(
                code=aiohttp.WSCloseCode.GOING_AWAY, message=b"replaced"
            )
        state.phone_ws = ws

    logger.info("Phone connected from %s", request.remote)

    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                await _on_phone_message(state, msg.data)
            elif msg.type == aiohttp.WSMsgType.BINARY:
                await _on_phone_binary(state, msg.data)
            elif msg.type == aiohttp.WSMsgType.ERROR:
                logger.error("Phone WS error: %s", ws.exception())
                break
    finally:
        await _cleanup_phone(state, reason="phone disconnected", expected_ws=ws)

    return ws


async def _on_phone_message(state: _RelayState, raw: str) -> None:
    """Route an incoming message from the phone to the matching pending future."""
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        logger.warning("Non-JSON message from phone: %s", raw[:200])
        return

    request_id = data.get("request_id")
    if not request_id:
        logger.warning("Phone message missing request_id: %s", raw[:200])
        return

    async with state.streams_lock:
        stream_queue = state.streams.get(request_id)
    if stream_queue is not None:
        await stream_queue.put(("message", data))
        return

    async with state.pending_lock:
        future = state.pending.pop(request_id, None)

    if future is None:
        logger.debug(
            "No pending future for request_id=%s (possibly timed out)", request_id
        )
        return

    if not future.done():
        future.set_result(data)


def _decode_stream_frame(raw: bytes) -> tuple[str, bytes]:
    """Decode a binary phone frame: uint16 request-id length, UTF-8 id, payload."""
    if len(raw) < 3:
        raise ValueError("Binary stream frame is too short")
    request_id_length = int.from_bytes(raw[:2], byteorder="big", signed=False)
    if request_id_length < 1 or request_id_length > 128:
        raise ValueError("Binary stream frame has an invalid request-id length")
    payload_offset = 2 + request_id_length
    if payload_offset > len(raw):
        raise ValueError("Binary stream frame is truncated")
    request_id = raw[2:payload_offset].decode("utf-8")
    return request_id, raw[payload_offset:]


async def _on_phone_binary(state: _RelayState, raw: bytes) -> None:
    try:
        request_id, payload = _decode_stream_frame(raw)
    except (UnicodeDecodeError, ValueError) as exc:
        logger.warning("Invalid binary stream frame from phone: %s", exc)
        return

    async with state.streams_lock:
        stream_queue = state.streams.get(request_id)
    if stream_queue is None:
        logger.debug("No active stream for request_id=%s", request_id)
        return
    await stream_queue.put(("chunk", payload))


async def _cleanup_phone(
    state: _RelayState,
    reason: str = "",
    expected_ws: Optional[web.WebSocketResponse] = None,
) -> None:
    """Clean up phone connection and cancel all pending requests."""
    async with state.phone_ws_lock:
        # A newly connected phone may already have replaced this socket. The
        # old handler must not tear down the replacement or its active requests.
        if expected_ws is not None and state.phone_ws is not expected_ws:
            return
        ws = state.phone_ws
        state.phone_ws = None

    if ws is not None and not ws.closed:
        await ws.close()

    await _fail_inflight(state, reason)


async def _fail_inflight(state: _RelayState, reason: str) -> None:
    """Fail requests that were assigned to a phone connection that is gone."""
    # Fail all pending futures
    async with state.pending_lock:
        pending = dict(state.pending)
        state.pending.clear()

    for rid, fut in pending.items():
        if not fut.done():
            fut.set_exception(ConnectionError(f"Phone disconnected ({reason})"))

    async with state.streams_lock:
        stream_queues = list(state.streams.values())
        state.streams.clear()
    for queue in stream_queues:
        while not queue.empty():
            try:
                queue.get_nowait()
            except asyncio.QueueEmpty:
                break
        queue.put_nowait(("error", f"Phone disconnected ({reason})"))

    if pending:
        logger.info("Cancelled %d pending requests (%s)", len(pending), reason)


# ── HTTP handler (tool side) ─────────────────────────────────────────────────

_RESPONSE_TIMEOUT = 30  # seconds


async def _handle_http(
    request: web.Request, state: _RelayState, path: str
) -> web.StreamResponse:
    """Forward an HTTP request from a tool to the phone over WebSocket."""
    # ── Auth check ──────────────────────────────────────────────────────────
    # Require Bearer token matching the pairing code.  The client already sends
    # this (android_tool._auth_headers), so the only effect is blocking
    # unauthenticated local processes from abusing the HTTP tool API.
    remote_ip = request.remote or "unknown"
    if _auth_is_blocked(remote_ip):
        logger.warning("HTTP auth attempt from blocked IP %s — 429", remote_ip)
        return web.json_response(
            {"error": "Too many failed authentication attempts. Try again later."},
            status=429,
        )

    auth_header = request.headers.get("Authorization", "")
    token = auth_header.removeprefix("Bearer ").strip() if auth_header.startswith("Bearer ") else ""
    if not hmac.compare_digest(token, state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning(
            "HTTP %s %s rejected — bad auth from %s (header=%s)",
            request.method, path, remote_ip, _mask_token(token),
        )
        return web.json_response({"error": "Unauthorized"}, status=401)

    # ── Phone connectivity check ────────────────────────────────────────────
    async with state.phone_ws_lock:
        ws = state.phone_ws
        if ws is None or ws.closed:
            return web.json_response(
                {
                    "error": "No phone connected. Open the Hermes app on your phone and connect."
                },
                status=503,
            )

    # Build the command envelope
    request_id = str(uuid.uuid4())
    method = request.method  # GET or POST
    params = dict(request.query)

    body = {}
    if method == "POST":
        try:
            body = await request.json()
        except Exception:
            body = {}

    command = {
        "request_id": request_id,
        "method": method,
        "path": path,
        "params": params,
        "body": body,
    }
    logger.debug(">>> %s %s body=%s", method, path, _safe_body_repr(body))

    # Register the response target *before* sending so we never miss a reply.
    is_binary_stream = path == "/mic_file"
    future = None
    stream_queue = None
    if is_binary_stream:
        stream_queue = asyncio.Queue(maxsize=8)
        async with state.streams_lock:
            state.streams[request_id] = stream_queue
    else:
        future = state.loop.create_future()
        async with state.pending_lock:
            state.pending[request_id] = future

    try:
        await ws.send_json(command)
    except Exception as exc:
        if is_binary_stream:
            async with state.streams_lock:
                state.streams.pop(request_id, None)
        else:
            async with state.pending_lock:
                state.pending.pop(request_id, None)
        logger.error("Failed to send command to phone: %s", exc)
        return web.json_response(
            {"error": f"Failed to send command to phone: {exc}"},
            status=502,
        )

    if is_binary_stream:
        return await _relay_binary_stream(request, state, request_id, stream_queue)

    # Wait for the phone's response
    try:
        response_data = await asyncio.wait_for(future, timeout=_RESPONSE_TIMEOUT)
    except asyncio.TimeoutError:
        async with state.pending_lock:
            state.pending.pop(request_id, None)
        logger.warning(
            "Phone did not respond within %ds for %s %s",
            _RESPONSE_TIMEOUT,
            method,
            path,
        )
        return web.json_response(
            {"error": f"Phone did not respond within {_RESPONSE_TIMEOUT}s"},
            status=504,
        )
    except ConnectionError as exc:
        return web.json_response({"error": str(exc)}, status=502)

    # Return the phone's result
    status = response_data.get("status", 200)
    result = response_data.get("result", {})
    return web.json_response(result, status=status)


_MAX_STREAM_BYTES = 256 * 1024 * 1024


async def _relay_binary_stream(
    request: web.Request,
    state: _RelayState,
    request_id: str,
    queue: asyncio.Queue,
) -> web.StreamResponse:
    """Stream binary phone frames to the authenticated HTTP caller with backpressure."""
    response = None
    try:
        kind, first = await asyncio.wait_for(queue.get(), timeout=_RESPONSE_TIMEOUT)
        if kind == "error":
            return web.json_response({"error": first}, status=502)
        if kind != "message":
            return web.json_response({"error": "Phone sent stream data before metadata"}, status=502)

        stream = first.get("stream") if isinstance(first, dict) else None
        if not isinstance(stream, dict) or stream.get("event") != "start":
            status = first.get("status", 502) if isinstance(first, dict) else 502
            result = first.get("result", {"error": "Invalid stream response"}) if isinstance(first, dict) else {"error": "Invalid stream response"}
            return web.json_response(result, status=status)

        expected_size = int(stream.get("size", -1))
        if expected_size < 0 or expected_size > _MAX_STREAM_BYTES:
            return web.json_response({"error": "Recording size is invalid"}, status=413)

        raw_filename = os.path.basename(str(stream.get("filename", "recording.wav")))
        filename = re.sub(r"[^A-Za-z0-9._-]", "_", raw_filename)
        if not filename.lower().endswith(".wav"):
            filename = "recording.wav"
        mime_type = str(stream.get("mimeType", "audio/wav"))
        if mime_type != "audio/wav":
            mime_type = "application/octet-stream"

        response = web.StreamResponse(
            status=200,
            headers={
                "Cache-Control": "no-store",
                "Content-Type": mime_type,
                "Content-Disposition": f'attachment; filename="{filename}"',
                "Content-Length": str(expected_size),
            },
        )
        await response.prepare(request)

        digest = hashlib.sha256()
        total = 0
        while True:
            kind, payload = await asyncio.wait_for(queue.get(), timeout=_RESPONSE_TIMEOUT)
            if kind == "error":
                raise ConnectionError(str(payload))
            if kind == "chunk":
                total += len(payload)
                if total > expected_size or total > _MAX_STREAM_BYTES:
                    raise ValueError("Phone sent more recording data than declared")
                digest.update(payload)
                await response.write(payload)
                continue
            if kind != "message":
                raise ValueError("Unknown stream event")

            stream_event = payload.get("stream") if isinstance(payload, dict) else None
            event_name = stream_event.get("event") if isinstance(stream_event, dict) else None
            if event_name == "error":
                raise IOError(str(stream_event.get("message", "Phone stream failed")))
            if event_name != "end":
                raise ValueError("Invalid stream completion event")
            if total != expected_size or int(stream_event.get("bytes", -1)) != total:
                raise IOError("Recording stream length does not match metadata")
            expected_sha = str(stream_event.get("sha256", ""))
            if not re.fullmatch(r"[0-9a-f]{64}", expected_sha) or not hmac.compare_digest(
                digest.hexdigest(), expected_sha
            ):
                raise IOError("Recording stream checksum mismatch")
            await response.write_eof()
            return response
    except asyncio.TimeoutError:
        logger.warning("Timed out receiving microphone stream request_id=%s", request_id)
        if response is None:
            return web.json_response({"error": "Phone stream timed out"}, status=504)
        response.force_close()
        return response
    except Exception as exc:
        logger.warning("Microphone stream failed request_id=%s: %s", request_id, exc)
        if response is None:
            return web.json_response({"error": "Phone stream failed"}, status=502)
        response.force_close()
        return response
    finally:
        async with state.streams_lock:
            if state.streams.get(request_id) is queue:
                state.streams.pop(request_id, None)
        # If the HTTP consumer disappeared while the bounded queue was full,
        # free any waiting WebSocket producer immediately.
        while not queue.empty():
            try:
                queue.get_nowait()
            except asyncio.QueueEmpty:
                break
