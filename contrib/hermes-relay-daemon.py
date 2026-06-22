#!/usr/bin/env python3
"""Standalone relay daemon for hermes-android. Runs the WebSocket relay persistently."""

import os
import sys
import signal
import time
import logging
import threading

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s [%(name)s] %(levelname)s: %(message)s"
)
logger = logging.getLogger("hermes-relay-daemon")


def main():
    pairing_code = os.getenv("ANDROID_BRIDGE_TOKEN")
    port = int(os.getenv("ANDROID_RELAY_PORT", "8766"))

    if not pairing_code:
        logger.error(
            "ANDROID_BRIDGE_TOKEN not set. Set it in ~/.hermes/.env or environment."
        )
        sys.exit(1)

    script_dir = os.path.dirname(os.path.abspath(__file__))
    parent_dir = os.path.dirname(script_dir)
    tools_dir = os.path.join(parent_dir, "tools")
    if os.path.isdir(tools_dir):
        sys.path.insert(0, parent_dir)

    from tools.android_relay import start_relay, stop_relay

    start_relay(pairing_code=pairing_code, port=port)
    logger.info("Relay started on port %d with pairing code ****", port)

    stop_event = threading.Event()

    def handle_signal(signum, frame):
        logger.info("Received signal %s, shutting down...", signum)
        stop_event.set()

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    stop_event.wait()
    stop_relay()
    logger.info("Daemon exiting")


if __name__ == "__main__":
    main()
