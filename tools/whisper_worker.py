#!/usr/bin/env python3
"""Private JSON-lines worker that keeps one Whisper model resident in memory."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


def emit(message: dict) -> None:
    sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--language", required=True)
    args = parser.parse_args()

    try:
        import whisper

        model = whisper.load_model(args.model)
    except Exception as exc:
        emit({"status": "error", "error": type(exc).__name__})
        return 2

    emit({"status": "ready"})
    for line in sys.stdin:
        request: dict = {}
        try:
            request = json.loads(line)
            if request.get("command") == "shutdown":
                return 0
            request_id = request.get("id")
            audio_path = Path(request.get("path", ""))
            if not isinstance(request_id, int) or not audio_path.is_file():
                raise ValueError("invalid request")
            result = model.transcribe(
                str(audio_path),
                language=args.language,
                fp16=False,
                verbose=False,
            )
            text = result.get("text", "") if isinstance(result, dict) else ""
            emit({"id": request_id, "status": "ok", "text": str(text)})
        except Exception as exc:
            emit(
                {
                    "id": request.get("id") if isinstance(request, dict) else None,
                    "status": "error",
                    "error": type(exc).__name__,
                }
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
