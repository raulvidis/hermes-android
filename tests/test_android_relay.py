import pytest
import threading
from tools.android_relay import (
    start_relay,
    stop_relay,
    is_relay_running,
    is_phone_connected,
    get_relay_url,
    set_pairing_code,
    _RelayState,
    _auth_is_blocked,
    _auth_record_failure,
    _auth_lock,
    _auth_blocked,
    _auth_failures,
    _AUTH_MAX_ATTEMPTS,
    _mask_token,
    _device_aliases,
)


@pytest.fixture(autouse=True)
def reset_relay():
    yield
    stop_relay()


@pytest.fixture(autouse=True)
def reset_auth_state():
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    yield
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()


class TestRelayLifecycle:
    def test_start_with_specific_port(self):
        start_relay(pairing_code="TEST01", port=19876)
        assert is_relay_running()
        url = get_relay_url()
        assert "19876" in url
        stop_relay()

    def test_stop_when_not_running(self):
        stop_relay()

    def test_double_start_is_noop(self):
        start_relay(pairing_code="TEST01", port=19877)
        start_relay(pairing_code="TEST02", port=19877)
        assert is_relay_running()
        stop_relay()

    def test_is_phone_connected_false(self):
        assert not is_phone_connected()

    def test_is_relay_running_false_initially(self):
        assert not is_relay_running()

    def test_get_relay_url_returns_default_when_stopped(self):
        url = get_relay_url()
        assert url is not None
        assert "localhost" in url

    def test_set_pairing_code(self):
        set_pairing_code("NEPCODE")
        start_relay(pairing_code="NEPCODE", port=19878)
        assert is_relay_running()
        stop_relay()

    def test_state_accepts_multiple_pairing_codes(self):
        state = _RelayState("FIRST1,SECOND2", 19879)
        assert state.pairing_codes == {"FIRST1", "SECOND2"}

    def test_state_accepts_tokens_from_env(self, monkeypatch):
        monkeypatch.setenv("ANDROID_BRIDGE_TOKENS", "THIRD3,FOURTH4")
        state = _RelayState("FIRST1", 19880)
        assert state.pairing_codes == {"FIRST1", "THIRD3", "FOURTH4"}

    def test_device_aliases_from_env(self, monkeypatch):
        monkeypatch.setenv("ANDROID_BRIDGE_DEVICES", "old=LRW2U7,new=EMDMFU")
        state = _RelayState("LRW2U7,EMDMFU", 19881)
        assert state.device_aliases == {"old": "LRW2U7", "new": "EMDMFU"}
        assert _device_aliases(state, "LRW2U7") == ["old", "LRW2U7"]


class TestRateLimiting:
    def test_not_blocked_initially(self):
        assert not _auth_is_blocked("1.2.3.4")

    def test_blocked_after_max_failures(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _auth_record_failure("1.2.3.4")
        assert _auth_is_blocked("1.2.3.4")

    def test_different_ip_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS):
            _auth_record_failure("1.2.3.4")
        assert not _auth_is_blocked("5.6.7.8")

    def test_under_limit_not_blocked(self):
        for _ in range(_AUTH_MAX_ATTEMPTS - 1):
            _auth_record_failure("1.2.3.4")
        assert not _auth_is_blocked("1.2.3.4")


class TestTokenMasking:
    """Verify that bad auth tokens are masked in log output, not logged in plaintext."""

    def test_normal_token_masked(self):
        token = "SECRET123"
        masked = _mask_token(token)
        assert masked == "SE****"
        assert token not in masked

    def test_short_token_fully_masked(self):
        assert _mask_token("X") == "****"
        assert _mask_token("AB") == "AB****"

    def test_empty_token_fully_masked(self):
        assert _mask_token("") == "****"
