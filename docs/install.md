---
summary: "Full install: hermes-agent plugin + bridge APK + Python dev setup."
read_when:
  - "Installing from scratch"
  - "Debugging a failed install"
  - "Setting up local Python development"
---

# Install

## Plugin (hermes-agent v0.3.0+)

One-liner (`install.sh`):

```bash
curl -sSL https://raw.githubusercontent.com/raulvidis/hermes-android/main/install.sh | bash
```

What it does:
1. Shallow-clones the repo to a temp dir.
2. Copies `hermes-android-plugin/` → `~/.hermes/plugins/hermes-android`.
3. Installs `aiohttp` if missing (`pip`/`pip3`).
4. Cleans up the temp dir.

Then restart hermes-gateway and run `/plugins` to verify — should show `✓ hermes-android v0.3.0 (38 tools)`.

Manual alternative:
```bash
mkdir -p ~/.hermes/plugins
cp -r hermes-android-plugin ~/.hermes/plugins/hermes-android
```

## Bridge APK

**Option A — prebuilt:** download `hermes-android-<version>.apk` from the [Latest Build release](https://github.com/raulvidis/hermes-android/releases/tag/latest-build); install on-device or `adb install hermes-android-*.apk`.

**Option B — build from source:**
```bash
cd hermes-android-bridge
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/hermes-android-*.apk
```

It is an unsigned **debug** build (not on Play Store / F-Droid yet).

## Grant permissions on the phone

Full mapping of permission → tools is in the README "Permissions" table. Checklist (Settings paths vary by OEM):

1. Open Hermes Bridge → **Enable Accessibility Service** → toggle Hermes Bridge ON (required by all tools)
2. **Enable Status Overlay** → grant
3. **Grant Screen Recording** → approve the system dialog (`android_screen_record`)
4. Settings > Apps > Hermes Bridge > Permissions: **Location**, **Contacts**, **SMS**, **Phone**
5. Settings > Apps > Special app access > Notification access → enable Hermes Bridge (`android_notifications`/`android_events`)

Missing grants show up as per-tool failures — see [troubleshooting.md](troubleshooting.md) "Permissions" for the failed-tool → missing-grant mapping.

## Android Automotive (AAOS) head units

Sideload the same APK via `adb install` (USB to the head unit, or `adb connect <ip>:5555`). Grant Accessibility + overlay; skip phone-only perms. For network: `adb forward tcp:8766 tcp:8766` then enter `http://localhost:8766`, or use the relay's `http://<ip>:8766` on the same WiFi. SMS/calls/contacts return errors gracefully.

## Persistent relay (systemd)

`contrib/` ships a standalone relay daemon + unit file so the relay survives gateway restarts:

```bash
sudo mkdir -p /opt/hermes-android
sudo cp -r contrib tools /opt/hermes-android/          # unit hardcodes /opt/hermes-android paths
# ANDROID_BRIDGE_TOKEN (pairing code) + optional ANDROID_RELAY_PORT (default 8766) in ~/.hermes/.env
sudo cp contrib/hermes-android-relay.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now hermes-android-relay
```

Verify: `systemctl status hermes-android-relay` and `journalctl -u hermes-android-relay -n 20`. The daemon exits at startup if `ANDROID_BRIDGE_TOKEN` is unset.

## Python dev setup

```bash
pip install -e ".[dev]"
python -m pytest tests/
```

Requires Python >=3.11. See [configuration.md](configuration.md) for env vars.
