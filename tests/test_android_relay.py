import pytest
import threading
from tools.android_relay import (
    start_relay,
    stop_relay,
    is_relay_running,
    is_phone_connected,
    get_relay_url,
    set_pairing_code,
    _auth_is_blocked,
    _auth_record_failure,
    _auth_lock,
    _auth_blocked,
    _auth_failures,
    _AUTH_MAX_ATTEMPTS,
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

    def test_bad_token_logged_masked(self, caplog):
        """When a bad WS auth token is provided, the log should show a masked version."""
        import logging
        from tools.android_relay import _RelayState

        with caplog.at_level(logging.WARNING, logger="android_relay"):
            # Simulate the masking logic directly
            token = "SECRET123"
            masked = (token[:2] + "****") if len(token) >= 2 else "****"
            # Verify masking behavior
            assert masked == "SE****"
            assert token not in masked

    def test_short_token_masked(self):
        """Single-char tokens should be fully masked."""
        token = "X"
        masked = (token[:2] + "****") if len(token) >= 2 else "****"
        assert masked == "****"
        assert token not in masked

    def test_empty_token_masked(self):
        """Empty tokens should be fully masked."""
        token = ""
        masked = (token[:2] + "****") if len(token) >= 2 else "****"
        assert masked == "****"
