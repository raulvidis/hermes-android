package com.hermesandroid.bridge.robot

/** Safe, relay-allow-listed GPT Live quality levels shown on the Pixel. */
internal enum class RobotRealtimeTier(val wireName: String) {
    MINI("mini"),
    STANDARD("standard"),
    TOP("top"),
}
