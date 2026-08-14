#!/usr/bin/env python3
"""Run one restricted Hermes dialog turn without putting speech in argv.

The parent process sends exactly one JSON object on stdin.  Hermes is imported
from the user's local installation and receives only the ``memory`` toolset.
The worker exits after the turn so provider clients and session resources are
always finalized by Hermes itself.
"""

from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import re
import sys
from typing import Any

MAX_MESSAGE_CHARS = 12_000
MAX_RESPONSE_CHARS = 12_000
MAX_REQUEST_BYTES = 128 * 1024
SESSION_ID_RE = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
SESSION_LINE_RE = re.compile(r"(?:^|\n)session_id:\s*([A-Za-z0-9_.:-]{1,128})\s*$")


def _error(message: str) -> int:
    print(json.dumps({"status": "error", "error": message}), flush=True)
    return 1


def _read_request() -> dict[str, Any]:
    raw = sys.stdin.buffer.read(MAX_REQUEST_BYTES + 1)
    if not raw:
        raise ValueError("empty request")
    if len(raw) > MAX_REQUEST_BYTES:
        raise ValueError("request is too large")
    value = json.loads(raw.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("request must be an object")
    return value


def _validated_text(request: dict[str, Any], key: str, maximum: int) -> str:
    value = request.get(key, "")
    if not isinstance(value, str):
        raise ValueError(f"{key} must be text")
    value = value.strip()
    if not value or len(value) > maximum or "\x00" in value:
        raise ValueError(f"{key} is invalid")
    return value


def _validated_optional_slug(request: dict[str, Any], key: str) -> str | None:
    value = request.get(key)
    if value in (None, ""):
        return None
    if not isinstance(value, str) or not re.fullmatch(
        r"[A-Za-z0-9_.:/+-]{1,160}", value
    ):
        raise ValueError(f"{key} is invalid")
    return value


def _run(request: dict[str, Any]) -> dict[str, Any]:
    message = _validated_text(request, "message", MAX_MESSAGE_CHARS)
    model = _validated_optional_slug(request, "model")
    provider = _validated_optional_slug(request, "provider")
    session_id = _validated_optional_slug(request, "session_id")
    if session_id is not None and SESSION_ID_RE.fullmatch(session_id) is None:
        raise ValueError("session_id is invalid")

    try:
        max_turns = int(request.get("max_turns", 6))
    except (TypeError, ValueError) as exc:
        raise ValueError("max_turns is invalid") from exc
    max_turns = max(2, min(12, max_turns))

    hermes_root = (
        Path(
            os.environ.get("HERMES_AGENT_ROOT", "")
            or Path.home() / ".hermes" / "hermes-agent"
        )
        .expanduser()
        .resolve()
    )
    if not (hermes_root / "cli.py").is_file():
        raise RuntimeError("Hermes installation was not found")

    runtime_dir = Path(
        os.environ.get("HERMES_DIALOG_RUNTIME_DIR", "")
        or Path.home() / ".hermes" / "robot-dialog"
    ).expanduser()
    runtime_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        runtime_dir.chmod(0o700)
    except OSError:
        pass
    os.chdir(runtime_dir)

    sys.path.insert(0, str(hermes_root))
    os.environ["HERMES_SESSION_SOURCE"] = "tool"
    system_prompt = request.get("system_prompt")
    if isinstance(system_prompt, str) and system_prompt.strip():
        os.environ["HERMES_EPHEMERAL_SYSTEM_PROMPT"] = system_prompt[:8_000]

    # Hermes' CLI expands @file references before the agent sees a prompt.
    # Spoken dialog must never become an implicit local-file read, so use the
    # visually equivalent full-width character.  The actual transcript is
    # archived separately by the parent unchanged.
    hermes_message = message.replace("@", "＠")

    captured_stdout = io.StringIO()
    captured_stderr = io.StringIO()
    exit_code = 0
    with redirect_stdout(captured_stdout), redirect_stderr(captured_stderr):
        try:
            from cli import main as hermes_main

            hermes_main(
                query=hermes_message,
                toolsets="memory",
                model=model,
                provider=provider,
                max_turns=max_turns,
                quiet=True,
                compact=True,
                resume=session_id,
                checkpoints=False,
                pass_session_id=False,
                ignore_user_config=False,
                ignore_rules=False,
            )
        except SystemExit as exc:
            exit_code = exc.code if isinstance(exc.code, int) else 1

    stderr_value = captured_stderr.getvalue()
    session_match = SESSION_LINE_RE.search(stderr_value)
    effective_session_id = session_match.group(1) if session_match else session_id
    response = captured_stdout.getvalue().strip()

    if exit_code != 0 or not response:
        raise RuntimeError("Hermes did not complete the dialog turn")
    if effective_session_id is None:
        raise RuntimeError("Hermes returned no session id")

    return {
        "status": "ok",
        "response": response[:MAX_RESPONSE_CHARS],
        "session_id": effective_session_id,
    }


def main() -> int:
    try:
        request = _read_request()
        result = _run(request)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
        return _error("invalid request")
    except Exception as exc:
        # Deliberately omit provider output and prompt text.  They can contain
        # personal speech or credentials echoed by an upstream error.
        return _error(f"Hermes worker failed ({type(exc).__name__})")
    print(json.dumps(result, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
