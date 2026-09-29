"""MEDIA: temp-file handling — screenshots, screen recordings and microphone
downloads.

Three tools hand back a file instead of JSON, in the form the gateway parses:

    Screenshot captured (1080x1920)
    MEDIA:/tmp/android_screenshot_ab12cd.jpg

That line is a promise: the path exists, holds the bytes the phone sent, and is
the whole response (no JSON wrapper to parse first). These tests check both
halves — the promise on success, and that a failed transfer produces a JSON
error and *no* advertised file, because a MEDIA: line pointing at a truncated
or half-written file is worse than an error the agent can act on.

Everything runs against ``responses`` or an in-process stub response, and
``tempfile.tempdir`` is redirected into pytest's tmp_path, so no socket is
opened and no file is left in the real temp directory.

One branch is deliberately not covered: the 256 MiB guard *inside* the chunk
loop (``if written > 256 * 1024 * 1024``). It is a literal rather than a module
constant, so reaching it means writing a quarter of a gigabyte to disk in a unit
test. The declared-size check that fires before the first byte is covered, and
it rejects the same lie earlier.
"""

import base64
import json
import os
import re
import tempfile
import types
from pathlib import Path

import pytest
import requests
import responses

from tests.support import (
    StubStreamResponse,
    as_json,
    install_stub_get,
    is_error,
    media_path,
    wav_bytes,
)


@pytest.fixture(autouse=True)
def temp_dir(monkeypatch, tmp_path):
    """Send every NamedTemporaryFile the toolset creates into pytest's tmp dir.

    The MEDIA tools use delete=False on purpose — the gateway still has to read
    the file — so without this a test run litters /tmp with screenshots and
    recordings it will never clean up.
    """
    workdir = tmp_path / "media"
    workdir.mkdir()
    monkeypatch.setattr(tempfile, "tempdir", str(workdir))
    return workdir


def media_files(workdir):
    return sorted(str(p) for p in Path(workdir).iterdir())


def assert_single_media_file(workdir, result, suffix):
    """The result advertises exactly one file, and it is the one on disk."""
    path = media_path(result)
    assert path is not None, f"no MEDIA: line in {result!r}"
    assert os.path.isabs(path), f"MEDIA path must be absolute for the gateway: {path}"
    assert path.endswith(suffix), f"expected a {suffix} file, got {path}"
    assert os.path.exists(path), f"MEDIA path does not exist: {path}"
    assert media_files(workdir) == [path], (
        f"the tool left other files behind: {media_files(workdir)}"
    )
    return path


class TestScreenshotMedia:
    PAYLOAD = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00fake-jpeg-bytes"

    def _b64(self):
        return base64.b64encode(self.PAYLOAD).decode()

    def test_nested_result_becomes_a_media_file(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(
            responses.GET,
            f"{url}/screenshot",
            json={"data": {"image": self._b64(), "width": 1080, "height": 1920}},
        )

        result = tool_mod.android_screenshot()

        assert result.startswith("Screenshot captured (1080x1920)\n")
        path = assert_single_media_file(temp_dir, result, ".jpg")
        assert Path(path).read_bytes() == self.PAYLOAD
        assert Path(path).name.startswith("android_screenshot_")

    def test_flat_result_is_accepted_too(self, tool_mod, bridge, temp_dir):
        """The bridge has returned the image both wrapped in "data" and bare."""
        rsps, url = bridge
        rsps.add(
            responses.GET,
            f"{url}/screenshot",
            json={"image": self._b64(), "width": 10, "height": 20},
        )

        result = tool_mod.android_screenshot()

        path = assert_single_media_file(temp_dir, result, ".jpg")
        assert Path(path).read_bytes() == self.PAYLOAD
        assert "10x20" in result

    def test_missing_dimensions_are_shown_as_placeholders(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/screenshot", json={"data": {"image": self._b64()}})

        result = tool_mod.android_screenshot()

        assert result.startswith("Screenshot captured (?x?)\n")
        assert_single_media_file(temp_dir, result, ".jpg")

    def test_empty_image_is_an_error_not_an_empty_file(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/screenshot", json={"data": {"image": ""}})

        result = tool_mod.android_screenshot()

        assert as_json(result) == {"error": "No image data returned"}
        assert media_files(temp_dir) == []

    def test_missing_image_key_is_an_error(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/screenshot", json={"data": {"width": 10}})

        assert as_json(tool_mod.android_screenshot()) == {"error": "No image data returned"}
        assert media_files(temp_dir) == []

    def test_a_bridge_error_is_passed_through_verbatim(self, tool_mod, bridge, temp_dir):
        """{"error": ...} on a 200 is how the bridge reports a denied permission."""
        rsps, url = bridge
        rsps.add(
            responses.GET,
            f"{url}/screenshot",
            json={"error": "MediaProjection permission denied"},
        )

        result = tool_mod.android_screenshot()

        assert as_json(result) == {"error": "MediaProjection permission denied"}
        assert "MEDIA:" not in result
        assert media_files(temp_dir) == []

    def test_undecodable_base64_is_an_error(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/screenshot", json={"data": {"image": "!!!not b64!!!"}})

        result = tool_mod.android_screenshot()

        assert is_error(result)
        # The raw binascii message surfaces. Not very actionable for an agent, but
        # it is stable and it is not a MEDIA: line, which is the part that matters.
        assert as_json(result) == {"error": "Incorrect padding"}
        assert media_files(temp_dir) == []

    def test_a_non_dict_data_field_is_an_error_not_an_exception(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/screenshot", json={"data": "not-an-object"})

        result = tool_mod.android_screenshot()

        assert is_error(result)
        assert media_files(temp_dir) == []


class TestScreenRecordMedia:
    PAYLOAD = b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom"

    def _b64(self):
        return base64.b64encode(self.PAYLOAD).decode()

    def test_recording_becomes_an_mp4_media_file(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(
            responses.POST,
            f"{url}/screen_record",
            json={"success": True, "data": {"video": self._b64(), "width": 720, "height": 1280}},
        )

        result = tool_mod.android_screen_record(2500)

        assert result.startswith("Screen recorded (720x1280, 2500ms)\n")
        path = assert_single_media_file(temp_dir, result, ".mp4")
        assert Path(path).read_bytes() == self.PAYLOAD
        assert Path(path).name.startswith("android_record_")
        assert json.loads(rsps.calls[0].request.body) == {"durationMs": 2500}

    def test_missing_dimensions_are_shown_as_placeholders(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(
            responses.POST, f"{url}/screen_record", json={"success": True, "data": {"video": self._b64()}}
        )

        result = tool_mod.android_screen_record(1000)

        assert "(?x?, 1000ms)" in result
        assert_single_media_file(temp_dir, result, ".mp4")

    def test_empty_video_falls_back_to_the_raw_payload(self, tool_mod, bridge, temp_dir):
        """No bytes means nothing to hand over; the agent gets the bridge's own
        explanation instead of a zero-byte .mp4."""
        rsps, url = bridge
        rsps.add(
            responses.POST, f"{url}/screen_record", json={"success": True, "data": {"video": ""}}
        )

        result = tool_mod.android_screen_record(1000)

        assert "MEDIA:" not in result
        assert as_json(result) == {"success": True, "data": {"video": ""}}
        assert media_files(temp_dir) == []

    def test_success_without_a_data_field_is_returned_as_is(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/screen_record", json={"success": True})

        result = tool_mod.android_screen_record(1000)

        assert as_json(result) == {"success": True}
        assert media_files(temp_dir) == []

    def test_a_denied_recording_is_returned_as_is(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(
            responses.POST,
            f"{url}/screen_record",
            json={"success": False, "error": "MediaProjection not granted"},
        )

        result = tool_mod.android_screen_record(1000)

        assert as_json(result) == {"success": False, "error": "MediaProjection not granted"}
        assert media_files(temp_dir) == []

    def test_a_non_dict_data_field_is_an_error_not_an_exception(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/screen_record", json={"success": True, "data": "oops"})

        result = tool_mod.android_screen_record(1000)

        assert is_error(result)
        assert media_files(temp_dir) == []

    def test_a_non_dict_payload_is_returned_verbatim(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.POST, f"{url}/screen_record", json=["unexpected", "list"])

        result = tool_mod.android_screen_record(1000)

        assert as_json(result) == ["unexpected", "list"]
        assert media_files(temp_dir) == []


class TestMicFetchMedia:
    """android_mic_fetch streams instead of decoding base64, and it is the one
    tool that validates what it received before advertising a MEDIA path."""

    def test_wav_is_streamed_to_a_media_file(self, tool_mod, bridge, temp_dir):
        payload = wav_bytes()
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/mic_file", body=payload, status=200)

        result = tool_mod.android_mic_fetch()

        assert result.startswith(f"Microphone recording fetched ({len(payload)} bytes)\n")
        path = assert_single_media_file(temp_dir, result, ".wav")
        assert Path(path).read_bytes() == payload
        assert Path(path).name.startswith("android_mic_")

    def test_a_named_recording_is_requested_by_name(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/mic_file", body=wav_bytes(), status=200)

        tool_mod.android_mic_fetch("recording_3.wav")

        assert rsps.calls[0].request.url.endswith("/mic_file?name=recording_3.wav")

    def test_the_latest_recording_is_requested_without_a_name(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/mic_file", body=wav_bytes(), status=200)

        tool_mod.android_mic_fetch()

        assert rsps.calls[0].request.url.endswith("/mic_file")

    def test_a_uppercase_extension_is_accepted(self, tool_mod, bridge, temp_dir):
        rsps, url = bridge
        rsps.add(responses.GET, f"{url}/mic_file", body=wav_bytes(), status=200)

        assert not is_error(tool_mod.android_mic_fetch("REC.WAV"))

    def test_the_header_is_reassembled_across_small_chunks(self, tool_mod, monkeypatch, temp_dir):
        """The RIFF/WAVE check reads the first 12 bytes, which the phone may
        split across frames; the prefix buffer has to survive that."""
        payload = wav_bytes()
        stub = StubStreamResponse(
            status_code=200,
            headers={"Content-Length": str(len(payload))},
            chunks=[payload[i : i + 5] for i in range(0, len(payload), 5)],
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        result = tool_mod.android_mic_fetch()

        path = assert_single_media_file(temp_dir, result, ".wav")
        assert Path(path).read_bytes() == payload

    def test_empty_chunks_are_skipped_not_counted(self, tool_mod, monkeypatch, temp_dir):
        payload = wav_bytes()
        stub = StubStreamResponse(
            status_code=200,
            headers={"Content-Length": str(len(payload))},
            chunks=[b"", payload[:12], b"", payload[12:], b""],
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        result = tool_mod.android_mic_fetch()

        assert f"({len(payload)} bytes)" in result
        assert Path(media_path(result)).read_bytes() == payload

    def test_a_missing_content_length_is_tolerated(self, tool_mod, monkeypatch, temp_dir):
        """Chunked transfers carry no Content-Length; the download must still work."""
        payload = wav_bytes()
        stub = StubStreamResponse(status_code=200, headers={}, chunks=[payload])
        install_stub_get(tool_mod, monkeypatch, stub)

        result = tool_mod.android_mic_fetch()

        assert Path(media_path(result)).read_bytes() == payload

    def test_a_non_numeric_content_length_is_ignored(self, tool_mod, monkeypatch, temp_dir):
        payload = wav_bytes()
        stub = StubStreamResponse(
            status_code=200, headers={"Content-Length": "unknown"}, chunks=[payload]
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        assert Path(media_path(tool_mod.android_mic_fetch())).read_bytes() == payload

    def test_a_truncated_download_is_an_error_and_leaves_no_file(
        self, tool_mod, monkeypatch, temp_dir
    ):
        """The declared size is the phone's promise; a short body means the
        recording is incomplete and must not be handed over as playable audio."""
        payload = wav_bytes()
        stub = StubStreamResponse(
            status_code=200,
            headers={"Content-Length": str(len(payload))},
            chunks=[payload[:12]],
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        result = tool_mod.android_mic_fetch()

        assert as_json(result) == {"error": "Microphone recording download was incomplete"}
        assert media_files(temp_dir) == [], "the partial file was not cleaned up"

    def test_a_non_wav_body_is_rejected(self, tool_mod, monkeypatch, temp_dir):
        """An HTML error page with a 200 status is what a captive portal or a
        misconfigured proxy returns; writing it to a .wav would hand the agent a
        file that cannot play."""
        body = b"<html>not audio</html>" + b"\x00" * 32
        stub = StubStreamResponse(
            status_code=200, headers={"Content-Length": str(len(body))}, chunks=[body]
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        result = tool_mod.android_mic_fetch()

        assert as_json(result) == {"error": "Downloaded microphone recording is not a WAV file"}
        assert media_files(temp_dir) == []

    def test_a_riff_file_that_is_not_a_wave_is_rejected(self, tool_mod, monkeypatch, temp_dir):
        body = b"RIFF" + b"\x00\x00\x00\x00" + b"AVI " + b"\x00" * 32
        stub = StubStreamResponse(
            status_code=200, headers={"Content-Length": str(len(body))}, chunks=[body]
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        assert as_json(tool_mod.android_mic_fetch()) == {
            "error": "Downloaded microphone recording is not a WAV file"
        }

    def test_an_empty_body_is_rejected(self, tool_mod, monkeypatch, temp_dir):
        stub = StubStreamResponse(status_code=200, headers={"Content-Length": "0"}, chunks=[])
        install_stub_get(tool_mod, monkeypatch, stub)

        assert as_json(tool_mod.android_mic_fetch()) == {
            "error": "Downloaded microphone recording is not a WAV file"
        }
        assert media_files(temp_dir) == []

    def test_a_body_longer_than_declared_is_an_error(self, tool_mod, monkeypatch, temp_dir):
        payload = wav_bytes() + b"extra trailing bytes"
        stub = StubStreamResponse(
            status_code=200,
            headers={"Content-Length": str(len(payload) - 20)},
            chunks=[payload],
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        assert as_json(tool_mod.android_mic_fetch()) == {
            "error": "Microphone recording download was incomplete"
        }
        assert media_files(temp_dir) == []

    def test_a_declared_size_above_the_cap_is_refused_before_downloading(
        self, tool_mod, monkeypatch, temp_dir
    ):
        """256 MiB of audio is ~4 hours at 16 kHz mono; refusing up front keeps a
        malformed Content-Length from filling the server's disk."""
        stub = StubStreamResponse(
            status_code=200,
            headers={"Content-Length": str(300 * 1024 * 1024)},
            chunks=[b"never read"],
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        assert as_json(tool_mod.android_mic_fetch()) == {
            "error": "Microphone recording exceeds the download limit"
        }
        assert media_files(temp_dir) == []

    def test_a_mid_stream_connection_failure_is_redacted_and_cleaned_up(
        self, tool_mod, monkeypatch, temp_dir
    ):
        """The phone can drop its WebSocket halfway through a download. The
        requests message for that names the bridge host, so it must be replaced
        (#99) — and the partial file must go."""
        payload = wav_bytes()
        stub = StubStreamResponse(
            status_code=200,
            headers={"Content-Length": str(len(payload))},
            chunks=[payload[:12]],
            iter_error=requests.exceptions.ConnectionError(
                "HTTPConnectionPool(host='203.0.113.7', port=8766): Connection aborted."
            ),
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        result = tool_mod.android_mic_fetch()

        assert as_json(result) == {
            "error": "Could not download microphone recording from the bridge"
        }
        assert "203.0.113.7" not in result
        assert media_files(temp_dir) == [], "the partial file was not cleaned up"

    def test_an_unexpected_mid_stream_failure_keeps_its_message(self, tool_mod, monkeypatch, temp_dir):
        """Not a RequestException, so there is no connection detail to hide —
        the real cause is more useful to the agent than a generic string."""
        stub = StubStreamResponse(
            status_code=200,
            headers={"Content-Length": "999"},
            chunks=[b"RIFF"],
            iter_error=OSError("disk on fire"),
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        assert as_json(tool_mod.android_mic_fetch()) == {"error": "disk on fire"}
        assert media_files(temp_dir) == []

    def test_a_4xx_with_a_json_body_is_passed_through(self, tool_mod, monkeypatch, temp_dir):
        stub = StubStreamResponse(
            status_code=404, json_body={"error": "recording not found"}, text="ignored"
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        assert as_json(tool_mod.android_mic_fetch()) == {"error": "recording not found"}
        assert media_files(temp_dir) == []

    def test_a_4xx_with_a_non_json_body_reports_the_status(self, tool_mod, monkeypatch, temp_dir):
        """A proxy's HTML error page is not parseable, so the status is all there
        is — and it must not be written to a .wav file."""
        stub = StubStreamResponse(
            status_code=502, text="<html>Bad Gateway</html>", json_error=ValueError("no json")
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        assert as_json(tool_mod.android_mic_fetch()) == {
            "error": "Microphone download failed (HTTP 502)"
        }
        assert media_files(temp_dir) == []

    def test_a_5xx_with_an_empty_body_reports_the_status(self, tool_mod, monkeypatch, temp_dir):
        stub = StubStreamResponse(status_code=500, text="", json_error=ValueError("no json"))
        install_stub_get(tool_mod, monkeypatch, stub)

        assert as_json(tool_mod.android_mic_fetch()) == {
            "error": "Microphone download failed (HTTP 500)"
        }

    def test_a_4xx_body_that_is_not_an_object_is_still_returned(self, tool_mod, monkeypatch, temp_dir):
        """Pinned as-is: the bridge's own body is forwarded untouched, so a body
        with no "error" key arrives with no failure marker either. Changing that
        is a deliberate act."""
        stub = StubStreamResponse(status_code=500, json_body={"detail": "boom"}, text="ignored")
        install_stub_get(tool_mod, monkeypatch, stub)

        assert as_json(tool_mod.android_mic_fetch()) == {"detail": "boom"}


@pytest.mark.parametrize(
    "remote_path",
    ["/etc/passwd", "recordings/rec.wav", "../rec.wav", "..", "rec.mp3", "rec.wav ", "rec.wav\n", "a/b.wav"],
    ids=[
        "absolute",
        "subdirectory",
        "parent_traversal",
        "dotdot",
        "wrong_extension",
        "trailing_space",
        "trailing_newline",
        "nested",
    ],
)
class TestMicFetchRemotePathValidation:
    """remote_path is model-supplied and lands in a query parameter the phone
    resolves against its recordings directory, so anything that is not a bare
    .wav filename is refused before a request is made."""

    def test_rejected_without_a_request(self, tool_mod, bridge, remote_path):
        rsps, _ = bridge
        rsps.add(responses.GET, re.compile(r".*"), body=wav_bytes(), status=200)

        result = as_json(tool_mod.android_mic_fetch(remote_path))

        assert result == {"error": "remote_path must be a WAV filename, not a path"}
        assert not rsps.calls, f"{remote_path!r} reached the bridge"

    def test_no_file_is_created(self, tool_mod, bridge, remote_path, temp_dir):
        rsps, _ = bridge
        rsps.add(responses.GET, re.compile(r".*"), body=wav_bytes(), status=200)

        tool_mod.android_mic_fetch(remote_path)

        assert media_files(temp_dir) == []


class TestMicFetchRemotePathType:
    @pytest.mark.parametrize("remote_path", [None, 5, ["rec.wav"], {"name": "rec.wav"}, True])
    def test_a_non_string_is_refused_without_a_request(self, tool_mod, bridge, remote_path):
        rsps, _ = bridge
        rsps.add(responses.GET, re.compile(r".*"), body=wav_bytes(), status=200)

        result = as_json(tool_mod.android_mic_fetch(remote_path))

        assert result == {"error": "remote_path must be a WAV filename"}
        assert not rsps.calls


class OsShim:
    """Proxy over the real os module with unlink replaced.

    Patched onto the module under test only, so pytest's own tmp_path teardown
    keeps a working os.unlink.
    """

    def __init__(self, real, unlink):
        self._real = real
        self.unlink = unlink

    def __getattr__(self, name):
        return getattr(self._real, name)


class TestMicFetchCleanupFailures:
    """Every failure path unlinks the half-written file, and the unlink itself is
    wrapped: a cleanup failure must not replace the real error, or the agent is
    told about a filesystem problem instead of the dead download."""

    @staticmethod
    def _broken_unlink(module, monkeypatch):
        attempts = []

        def unlink(path):
            attempts.append(path)
            raise OSError("device or resource busy")

        monkeypatch.setattr(module, "os", OsShim(os, unlink))
        return attempts

    def test_a_transport_failure_is_still_reported_when_cleanup_fails(
        self, tool_mod, monkeypatch, temp_dir
    ):
        attempts = self._broken_unlink(tool_mod, monkeypatch)
        payload = wav_bytes()
        stub = StubStreamResponse(
            status_code=200,
            headers={"Content-Length": str(len(payload))},
            chunks=[payload[:12]],
            iter_error=requests.exceptions.ConnectionError("bridge went away"),
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        result = as_json(tool_mod.android_mic_fetch())

        assert result == {"error": "Could not download microphone recording from the bridge"}
        assert len(attempts) == 1, "the partial file was not even attempted to be removed"

    def test_a_validation_failure_is_still_reported_when_cleanup_fails(
        self, tool_mod, monkeypatch, temp_dir
    ):
        attempts = self._broken_unlink(tool_mod, monkeypatch)
        body = b"NOPE" * 16
        stub = StubStreamResponse(
            status_code=200, headers={"Content-Length": str(len(body))}, chunks=[body]
        )
        install_stub_get(tool_mod, monkeypatch, stub)

        result = as_json(tool_mod.android_mic_fetch())

        assert result == {"error": "Downloaded microphone recording is not a WAV file"}
        assert len(attempts) == 1


class TestMicFetchFailureBeforeTheFileExists:
    def test_a_failure_before_any_byte_is_written_still_reports_an_error(
        self, tool_mod, monkeypatch, temp_dir
    ):
        """The cleanup branches guard on `temp_path` being set; a failure before
        the file is created must take the other side of that guard rather than
        trying to unlink None."""

        def explode(url, **kwargs):
            raise ValueError("no adapter mounted")

        monkeypatch.setattr(
            tool_mod,
            "requests",
            types.SimpleNamespace(exceptions=requests.exceptions, get=explode),
        )

        result = as_json(tool_mod.android_mic_fetch())

        assert result == {"error": "no adapter mounted"}
        assert media_files(temp_dir) == []
