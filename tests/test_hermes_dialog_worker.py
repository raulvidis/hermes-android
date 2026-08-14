import os
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest

from tools import hermes_dialog_worker as worker


def test_worker_exposes_only_memory_toolset_and_keeps_speech_out_of_argv(
    tmp_path, monkeypatch
):
    hermes_root = tmp_path / "hermes-agent"
    hermes_root.mkdir()
    (hermes_root / "cli.py").write_text("# test placeholder\n", encoding="utf-8")
    runtime_dir = tmp_path / "runtime"
    captured = {}

    def fake_main(**kwargs):
        captured.update(kwargs)
        print("Hallo vom Hermes Agenten")
        print("\nsession_id: dialog_test_1", file=sys.stderr)

    monkeypatch.setitem(sys.modules, "cli", SimpleNamespace(main=fake_main))
    monkeypatch.setenv("HERMES_AGENT_ROOT", str(hermes_root))
    monkeypatch.setenv("HERMES_DIALOG_RUNTIME_DIR", str(runtime_dir))
    monkeypatch.setenv("HERMES_EPHEMERAL_SYSTEM_PROMPT", "before-test")
    monkeypatch.setenv("HERMES_SESSION_SOURCE", "before-test")
    original_cwd = Path.cwd()
    try:
        result = worker._run(
            {
                "message": "Bitte lies nicht @private.txt",
                "model": "local",
                "session_id": None,
                "system_prompt": "Nur Dialog und Gedächtnis.",
                "max_turns": 6,
            }
        )
    finally:
        os.chdir(original_cwd)
        while str(hermes_root.resolve()) in sys.path:
            sys.path.remove(str(hermes_root.resolve()))

    assert result == {
        "status": "ok",
        "response": "Hallo vom Hermes Agenten",
        "session_id": "dialog_test_1",
    }
    assert captured["query"] == "Bitte lies nicht ＠private.txt"
    assert captured["toolsets"] == "memory"
    assert captured["model"] == "local"
    assert captured["resume"] is None
    assert captured["ignore_user_config"] is False
    assert captured["ignore_rules"] is False
    assert os.environ["HERMES_EPHEMERAL_SYSTEM_PROMPT"] == "Nur Dialog und Gedächtnis."


def test_worker_rejects_untrusted_model_slug():
    with pytest.raises(ValueError, match="model"):
        worker._run(
            {
                "message": "Hallo",
                "model": "local; run something",
                "system_prompt": "Test",
            }
        )
