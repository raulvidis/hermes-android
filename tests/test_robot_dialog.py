import json
import os
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest

from tools.robot_dialog import (
    BACKEND_GPT_LIVE,
    BACKEND_HERMES_LOCAL,
    BACKEND_HERMES_STANDARD,
    ConversationArchive,
    ConversationRecorder,
    DialogProcessLock,
    DialogConfig,
    DialogError,
    HermesChatProvider,
    HermesWorkerClient,
    LmsCliChatProvider,
    OpenAIResponsesProvider,
    RobotDialogService,
    WhisperCliTranscriber,
    WhisperWarmTranscriber,
    build_available_providers,
    child_safety_reply,
    clean_reply,
    iter_sentences,
    load_env_file,
    redact_for_memory,
    redact_personal_identifiers,
)


def config(**overrides):
    values = {
        "relay_url": "http://127.0.0.1:8766",
        "bridge_token": "TEST01",
        "record_seconds": 2,
        "history_turns": 12,
    }
    values.update(overrides)
    return DialogConfig(**values)


class FakeBridge:
    def __init__(self):
        self.states = []
        self.backend_states = []
        self.posts = []

    def set_robot_state(self, phase, caption, show=False, backend=None):
        self.states.append((phase, caption, show))
        if backend is not None:
            self.backend_states.append((phase, caption, backend))

    def post(self, path, body, timeout=30):
        self.posts.append((path, body))
        return {"status": "ok"}


class FakeTranscriber:
    def __init__(self, transcript):
        self.transcript = transcript

    def transcribe(self, wav_path, output_dir):
        return self.transcript


class FakeProvider:
    def __init__(self, reply="Das ist eine tolle Frage!"):
        self.reply = reply
        self.histories = []

    def generate(self, history):
        self.histories.append(history)
        return self.reply


class FakeStreamingProvider(FakeProvider):
    def __init__(self, chunks):
        super().__init__("")
        self.chunks = chunks

    def stream(self, history):
        self.histories.append(history)
        yield from self.chunks


class FakeRecorder:
    def __init__(self):
        self.turns = []
        self.ended = 0

    def record_turn(self, user_text, assistant_text, backend):
        self.turns.append((user_text, assistant_text, backend))

    def end_session(self):
        self.ended += 1


class TestPrivacyHelpers:
    def test_cloud_redaction_removes_intro_email_and_phone(self):
        text = "Hallo, hier ist Justus. Mail test@example.com, Telefon +49 123 456789."
        redacted = redact_personal_identifiers(text)
        assert "Justus" not in redacted
        assert "test@example.com" not in redacted
        assert "456789" not in redacted

    def test_memory_redaction_removes_contact_data_and_secret_values(self):
        redacted = redact_for_memory(
            "Mail test@example.com, Telefon +49 123 456789, Passwort: geheim123"
        )
        assert "example.com" not in redacted
        assert "456789" not in redacted
        assert "geheim123" not in redacted

    def test_clean_reply_removes_links_and_markdown(self):
        reply = clean_reply("**Hallo!** Schau auf https://example.com nach.")
        assert "**" not in reply
        assert "http" not in reply
        assert reply == "Hallo! Schau auf nach."

    def test_high_risk_input_uses_adult_escalation(self):
        reply = child_safety_reply("Jemand schlägt mich und ich bin in Gefahr")
        assert reply is not None
        assert "Erwachsenen" in reply

    def test_normal_input_has_no_fixed_safety_reply(self):
        assert child_safety_reply("Warum ist der Himmel blau?") is None

    def test_stream_is_split_as_soon_as_sentence_boundary_arrives(self):
        assert list(iter_sentences(["Erster Satz.", " Zweiter ", "Satz?"])) == [
            "Erster Satz.",
            "Zweiter Satz?",
        ]


class TestCloudConfiguration:
    def test_openai_requires_api_key(self):
        with pytest.raises(DialogError, match="OPENAI_API_KEY"):
            OpenAIResponsesProvider(
                model="test-model",
                persona="test persona",
                api_key="",
            )

    def test_selecting_openai_backend_is_sufficient_explicit_configuration(self):
        provider = OpenAIResponsesProvider(
            model="test-model",
            persona="test persona",
            api_key="configured-on-mac",
        )
        assert provider.model == "test-model"

    def test_chatgpt_instant_alias_is_the_default_cloud_model(self):
        assert config().openai_model == "chat-latest"

    def test_openai_streams_text_and_redacts_personal_data(self, monkeypatch):
        captured = {}

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def __iter__(self):
                return iter(
                    [
                        b'data: {"type":"response.output_text.delta","delta":"Hallo. "}\n',
                        b'data: {"type":"response.output_text.delta","delta":"Wie geht es dir?"}\n',
                        b'data: {"type":"response.completed"}\n',
                    ]
                )

        def fake_urlopen(request, timeout):
            captured["timeout"] = timeout
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return FakeResponse()

        monkeypatch.setattr("tools.robot_dialog.urlopen", fake_urlopen)
        provider = OpenAIResponsesProvider(
            model="chat-latest",
            persona="test persona",
            api_key="configured-on-mac",
        )

        chunks = list(
            provider.stream(
                [
                    {
                        "role": "user",
                        "content": "Hallo, hier ist Justus. Mail test@example.com.",
                    }
                ]
            )
        )

        assert chunks == ["Hallo. ", "Wie geht es dir?"]
        assert captured["timeout"] == 90
        assert captured["payload"]["stream"] is True
        assert captured["payload"]["store"] is False
        cloud_text = captured["payload"]["input"][0]["content"]
        assert "Justus" not in cloud_text
        assert "test@example.com" not in cloud_text

    def test_both_hermes_backends_are_available_without_openai(self):
        providers = build_available_providers(config())

        assert set(providers) == {BACKEND_HERMES_LOCAL, BACKEND_HERMES_STANDARD}

    def test_hermes_modes_use_distinct_model_aliases(self):
        providers = build_available_providers(
            config(
                hermes_local_model="local-test", hermes_standard_model="standard-test"
            )
        )

        assert providers[BACKEND_HERMES_LOCAL].model == "local-test"
        assert providers[BACKEND_HERMES_STANDARD].model == "standard-test"


class TestEnvLoading:
    def test_env_file_does_not_override_existing_values(self, tmp_path, monkeypatch):
        env_path = tmp_path / ".env"
        env_path.write_text("EXISTING=saved\nNEW_VALUE=hello\n", encoding="utf-8")
        monkeypatch.setenv("EXISTING", "process")
        monkeypatch.delenv("NEW_VALUE", raising=False)
        load_env_file(env_path)
        assert __import__("os").environ["EXISTING"] == "process"
        assert __import__("os").environ["NEW_VALUE"] == "hello"

    def test_invalid_relay_url_is_rejected(self, monkeypatch):
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "TEST01")
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "file:///tmp/socket")
        with pytest.raises(DialogError, match="http"):
            DialogConfig.from_env()

    def test_general_profile_is_the_default(self, monkeypatch):
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "TEST01")
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://127.0.0.1:8766")
        monkeypatch.delenv("ROBOT_DIALOG_PROFILE", raising=False)

        assert DialogConfig.from_env().profile == "general"

    def test_explicit_robot_relay_url_overrides_direct_bridge_url(self, monkeypatch):
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "TEST01")
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://pixel-direct:8765")
        monkeypatch.setenv("ROBOT_DIALOG_RELAY_URL", "http://mac-relay:8765/")

        assert DialogConfig.from_env().relay_url == "http://mac-relay:8765"

    def test_transcriber_keeps_configured_model(self):
        transcriber = WhisperCliTranscriber("/bin/echo", "small", "de")
        assert transcriber.model == "small"


class TestTurboProviders:
    def test_lms_cli_strips_terminal_controls_and_streams_text(self, tmp_path):
        fake_lms = tmp_path / "fake-lms"
        fake_lms.write_text(
            "#!/bin/sh\ncat >/dev/null\nprintf '\\r\\033[K\\033[?25hErster Satz. Zweiter Satz?\\n'\n",
            encoding="utf-8",
        )
        fake_lms.chmod(0o700)
        provider = LmsCliChatProvider(
            str(fake_lms),
            "Test persona",
            "test-9b",
        )

        reply = provider.generate([{"role": "user", "content": "Hallo"}])

        assert reply == "Erster Satz. Zweiter Satz?\n"

    def test_whisper_worker_process_is_reused(self, tmp_path):
        worker = tmp_path / "fake_worker.py"
        worker.write_text(
            "import json, sys\n"
            "print(json.dumps({'status': 'ready'}), flush=True)\n"
            "for line in sys.stdin:\n"
            " request=json.loads(line)\n"
            " if request.get('command') == 'shutdown': break\n"
            " print(json.dumps({'id': request['id'], 'status': 'ok', 'text': 'Hallo'}), flush=True)\n",
            encoding="utf-8",
        )
        audio = tmp_path / "turn.wav"
        audio.write_bytes(b"test")
        transcriber = WhisperWarmTranscriber(
            "/bin/echo",
            sys.executable,
            "small",
            "de",
            worker_path=worker,
        )
        try:
            transcriber.warm()
            assert transcriber.process is not None
            first_pid = transcriber.process.pid
            assert transcriber.transcribe(audio, tmp_path) == "Hallo"
            assert transcriber.transcribe(audio, tmp_path) == "Hallo"
            assert transcriber.process.pid == first_pid
        finally:
            transcriber.close()


class TestHermesDialogProvider:
    def test_process_lock_rejects_a_duplicate_companion(self, tmp_path):
        first = DialogProcessLock(tmp_path / "companion.lock")
        second = DialogProcessLock(tmp_path / "companion.lock")
        first.acquire()
        try:
            with pytest.raises(DialogError, match="already running"):
                second.acquire()
            assert os.stat(first.path).st_mode & 0o777 == 0o600
        finally:
            first.release()

    def test_worker_receives_spoken_text_only_on_stdin_and_persists_session_id(
        self, tmp_path
    ):
        root = tmp_path / "hermes-agent"
        python = root / "venv" / "bin" / "python"
        worker = tmp_path / "worker.py"
        python.parent.mkdir(parents=True)
        (root / "cli.py").write_text("# placeholder\n", encoding="utf-8")
        python.write_text("", encoding="utf-8")
        worker.write_text("", encoding="utf-8")
        calls = []

        def fake_runner(command, **kwargs):
            calls.append((command, kwargs))
            return SimpleNamespace(
                returncode=0,
                stdout=json.dumps(
                    {
                        "status": "ok",
                        "response": "Hallo!",
                        "session_id": "20260812_120000_test01",
                    }
                ),
                stderr="",
            )

        cfg = config(
            hermes_root=str(root),
            hermes_python=str(python),
            hermes_worker=str(worker),
            runtime_dir=str(tmp_path / "runtime"),
        )
        client = HermesWorkerClient(cfg, runner=fake_runner)
        provider = HermesChatProvider(
            client,
            session_key="local",
            model="local",
            persona="Test",
        )

        assert (
            provider.generate([{"role": "user", "content": "Privater Satz"}])
            == "Hallo!"
        )
        command, kwargs = calls[0]
        assert "Privater Satz" not in " ".join(command)
        assert json.loads(kwargs["input"])["message"] == "Privater Satz"
        assert client.state.get("local") == "20260812_120000_test01"
        assert os.stat(client.state.path).st_mode & 0o777 == 0o600

    def test_private_archive_is_jsonl_with_restrictive_permissions(self, tmp_path):
        archive = ConversationArchive(tmp_path / "transcripts")

        archive.record_turn("Hallo", "Guten Tag", BACKEND_HERMES_LOCAL)
        archive.record_event(
            "user",
            "Hallo live",
            BACKEND_GPT_LIVE,
            "item_live_1",
        )

        files = list((tmp_path / "transcripts").glob("*.jsonl"))
        assert len(files) == 1
        assert os.stat(files[0]).st_mode & 0o777 == 0o600
        entries = [
            json.loads(line)
            for line in files[0].read_text(encoding="utf-8").splitlines()
        ]
        assert entries[0]["type"] == "turn"
        assert entries[0]["user"] == "Hallo"
        assert entries[0]["assistant"] == "Guten Tag"
        assert {
            key: entries[1][key]
            for key in ("type", "backend", "role", "text", "itemId")
        } == {
            "type": "transcript",
            "backend": BACKEND_GPT_LIVE,
            "role": "user",
            "text": "Hallo live",
            "itemId": "item_live_1",
        }


class TestDialogTurn:
    def make_service(self, transcript, provider=None, profile="general", recorder=None):
        bridge = FakeBridge()
        chat = provider or FakeProvider()
        service = RobotDialogService(
            config(profile=profile),
            bridge,
            FakeTranscriber(transcript),
            chat,
            recorder=recorder,
        )
        service._record_turn = lambda temp_dir: Path(temp_dir) / "turn.wav"
        return service, bridge, chat

    def test_normal_turn_updates_face_speaks_and_keeps_in_memory_history(
        self, monkeypatch
    ):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)
        service, bridge, provider = self.make_service("Warum ist der Himmel blau?")

        service.handle_turn()

        phases = [phase for phase, _caption, _show in bridge.states]
        assert phases == ["listening", "thinking", "speaking", "ready"]
        assert bridge.posts == [
            ("/speak", {"text": "Das ist eine tolle Frage!", "queue": 0})
        ]
        assert provider.histories[0][-1] == {
            "role": "user",
            "content": "Warum ist der Himmel blau?",
        }
        assert service.history[-1]["role"] == "assistant"
        assert {backend for _phase, _caption, backend in bridge.backend_states} == {
            BACKEND_HERMES_LOCAL
        }

    def test_completed_turn_is_archived_and_forwarded_to_memory_recorder(
        self, monkeypatch
    ):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)
        recorder = FakeRecorder()
        service, _bridge, _provider = self.make_service(
            "Hallo Hermes", recorder=recorder
        )

        service.handle_turn()

        assert recorder.turns == [
            ("Hallo Hermes", "Das ist eine tolle Frage!", BACKEND_HERMES_LOCAL)
        ]

    def test_high_risk_turn_never_calls_model(self, monkeypatch):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)
        provider = FakeProvider()
        service, bridge, _ = self.make_service(
            "Jemand tut mir weh", provider, profile="child"
        )

        service.handle_turn()

        assert provider.histories == []
        spoken = " ".join(
            body["text"] for path, body in bridge.posts if path == "/speak"
        )
        assert "Erwachsenen" in spoken

    def test_general_profile_has_no_fixed_child_content_gate(self, monkeypatch):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)
        provider = FakeProvider("Ich höre dir zu.")
        service, bridge, _ = self.make_service("Jemand tut mir weh", provider)

        service.handle_turn()

        assert len(provider.histories) == 1
        assert bridge.posts[-1][1]["text"] == "Ich höre dir zu."

    def test_streamed_sentences_are_spoken_immediately_in_tts_queue(self, monkeypatch):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)
        provider = FakeStreamingProvider(["Erster Satz. ", "Zweiter Satz?"])
        service, bridge, _ = self.make_service("Hallo", provider)

        service.handle_turn()

        assert bridge.posts == [
            ("/speak", {"text": "Erster Satz.", "queue": 0}),
            ("/speak", {"text": "Zweiter Satz?", "queue": 1}),
        ]
        assert [phase for phase, _caption, _show in bridge.states] == [
            "listening",
            "thinking",
            "speaking",
            "speaking",
            "ready",
        ]
        assert service.history[-1]["content"] == "Erster Satz. Zweiter Satz?"

    def test_empty_transcript_is_not_sent_to_model(self, monkeypatch):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)
        service, bridge, provider = self.make_service("   ")

        service.handle_turn()

        assert provider.histories == []
        assert bridge.states[-1][0] == "ready"
        assert bridge.posts == []

    def test_recording_requests_pixel_side_silence_detection(
        self, tmp_path, monkeypatch
    ):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)

        class RecordingBridge(FakeBridge):
            def get(self, path):
                assert path == "/mic_status"
                return {"phase": "ready", "recording": False, "latest": "turn.wav"}

            def fetch_recording(self, name, destination):
                assert name == "turn.wav"
                destination.write_bytes(b"placeholder")

        bridge = RecordingBridge()
        service = RobotDialogService(
            config(record_seconds=10, vad_silence_ms=700),
            bridge,
            FakeTranscriber("Hallo"),
            FakeProvider(),
        )
        monkeypatch.setattr(service, "_validate_wav", lambda _path: None)

        service._record_turn(tmp_path)

        assert bridge.posts == [
            (
                "/mic_start",
                {"duration": 10, "stop_on_silence": True, "silence_ms": 700},
            )
        ]

    def test_backend_switch_clears_history_between_hermes_models(self):
        local = FakeProvider("Lokal")
        cloud = FakeProvider("Cloud")
        bridge = FakeBridge()
        service = RobotDialogService(
            config(),
            bridge,
            FakeTranscriber("Hallo"),
            local,
            providers={BACKEND_HERMES_LOCAL: local, BACKEND_HERMES_STANDARD: cloud},
        )
        service.history = [{"role": "user", "content": "Nur lokal"}]
        service.last_activity = 123.0

        assert service.select_backend(BACKEND_HERMES_STANDARD) is True

        assert service.provider is cloud
        assert service.active_backend == BACKEND_HERMES_STANDARD
        assert service.history == []
        assert service.last_activity == 0.0
        assert bridge.backend_states[-1][2] == BACKEND_HERMES_STANDARD

    def test_unconfigured_gpt_live_choice_keeps_hermes_local_backend(self, monkeypatch):
        monkeypatch.delenv("OPENAI_API_KEY", raising=False)
        local = FakeProvider("Lokal")
        bridge = FakeBridge()
        service = RobotDialogService(
            config(),
            bridge,
            FakeTranscriber("Hallo"),
            local,
        )

        assert service.select_backend(BACKEND_GPT_LIVE) is False

        assert service.provider is local
        assert service.active_backend == BACKEND_HERMES_LOCAL
        assert bridge.backend_states[-1][2] == BACKEND_HERMES_LOCAL
        assert "nicht eingerichtet" in bridge.backend_states[-1][1]

    def test_realtime_transcripts_are_paired_and_recorded(self):
        recorder = FakeRecorder()
        service, _bridge, _provider = self.make_service("unused", recorder=recorder)

        service.handle_realtime_transcript(
            {"role": "user", "text": "Hallo live", "itemId": "user_1"}
        )
        service.handle_realtime_transcript(
            {"role": "assistant", "text": "Hallo zurück", "itemId": "assistant_1"}
        )

        assert recorder.turns == [("Hallo live", "Hallo zurück", BACKEND_GPT_LIVE)]


class TestDialogLoopResilience:
    def test_phone_may_be_offline_when_service_starts(self, monkeypatch):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)

        class OfflineThenEventBridge(FakeBridge):
            def __init__(self):
                super().__init__()
                self.poll_calls = []
                self.state_attempts = 0

            def poll_robot_events(self, after, timeout=25):
                self.poll_calls.append(after)
                if len(self.poll_calls) == 1:
                    return {"latest": 0, "events": []}
                return {
                    "latest": 1,
                    "events": [{"id": 1, "event": "robot.talk_requested"}],
                }

            def set_robot_state(self, phase, caption, show=False, backend=None):
                self.state_attempts += 1
                raise DialogError("phone offline")

        bridge = OfflineThenEventBridge()
        service = RobotDialogService(
            config(), bridge, FakeTranscriber("Hallo"), FakeProvider()
        )
        turns = []
        service.handle_turn = lambda: turns.append("handled")

        service.run(once=True, show=True)

        assert bridge.state_attempts == 1
        assert turns == ["handled"]

    def test_relay_sequence_reset_is_detected(self, monkeypatch):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)

        class RestartingRelayBridge(FakeBridge):
            def __init__(self):
                super().__init__()
                self.poll_calls = []

            def poll_robot_events(self, after, timeout=25):
                self.poll_calls.append(after)
                if len(self.poll_calls) == 1:
                    return {"latest": 5, "events": []}
                if len(self.poll_calls) == 2:
                    return {"latest": 1, "events": []}
                return {
                    "latest": 1,
                    "events": [{"id": 1, "event": "robot.talk_requested"}],
                }

        bridge = RestartingRelayBridge()
        service = RobotDialogService(
            config(), bridge, FakeTranscriber("Hallo"), FakeProvider()
        )
        turns = []
        service.handle_turn = lambda: turns.append("handled")

        service.run(once=True)

        assert bridge.poll_calls == [0, 5, 0]
        assert turns == ["handled"]

    def test_backend_event_is_applied_before_following_talk_event(self, monkeypatch):
        monkeypatch.setattr("tools.robot_dialog.time.sleep", lambda _seconds: None)

        class SelectionBridge(FakeBridge):
            def __init__(self):
                super().__init__()
                self.poll_calls = 0

            def poll_robot_events(self, after, timeout=25):
                self.poll_calls += 1
                if self.poll_calls == 1:
                    return {"latest": 0, "events": []}
                return {
                    "latest": 2,
                    "events": [
                        {"id": 1, "event": "robot.backend_hermes_standard"},
                        {"id": 2, "event": "robot.talk_requested"},
                    ],
                }

        local = FakeProvider("Lokal")
        cloud = FakeProvider("Cloud")
        bridge = SelectionBridge()
        service = RobotDialogService(
            config(),
            bridge,
            FakeTranscriber("Hallo"),
            local,
            providers={BACKEND_HERMES_LOCAL: local, BACKEND_HERMES_STANDARD: cloud},
        )
        selected_when_talking = []
        service.handle_turn = lambda: selected_when_talking.append(
            service.active_backend
        )

        service.run(once=True)

        assert selected_when_talking == [BACKEND_HERMES_STANDARD]
