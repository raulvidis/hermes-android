# Security

## Overview

hermes-android gives a remote AI agent full control of an Android device via AccessibilityService. This is powerful and inherently sensitive — treat it with the same caution as remote desktop access.

## Current Security Model

### Authentication
- **Pairing code**: A random 6-character alphanumeric code generated on the phone
- The phone and server must share this code to establish a connection
- Codes use characters `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no ambiguous 0/O/1/I)
- **One code, three trust boundaries**: the same secret authenticates the relay
  WebSocket, the phone's `:8765` LAN server, and the tool side's HTTP calls to the
  relay. Leaking it anywhere leaks it everywhere (AUTH-3 in the audit).
- **Rotation takes effect immediately** (AUTH-1). `PairingManager` is the single
  source of truth on the device: tapping **Regenerate** drops the live relay
  session and re-dials presenting the new code, and the app says which of those
  happened. On the server, `android_setup("<new code>")` pushes the code to an
  already-running relay and closes any session that authenticated with the old
  one. Before the fix, rotation was cosmetic and an attacker holding the old code
  kept full device control.
- Stored in plaintext `SharedPreferences`, not the Keystore (AUTH-4 in the audit).

### Rate Limiting
- Failed WebSocket authentication attempts are rate-limited per IP
- After 5 failed attempts in 60 seconds, the IP is blocked for 5 minutes
- Blocked IPs receive HTTP 429

### WebSocket Authentication Transport
- The phone sends the pairing code as an `Authorization: Bearer` header on the
  WebSocket handshake — never in the URL, so it does not land in reverse-proxy
  access logs
- Legacy `?token=` query authentication is rejected so pairing codes cannot land
  in reverse-proxy access logs

### Secret-field redaction
- Nodes the platform marks `isPassword` are never serialised as text: `/screen`,
  `/find_nodes`, `/describe_node` and `/events` emit `"[redacted]"` in place of
  the text and drop `contentDescription`/`hintText` for those nodes. The node
  itself stays listed (id, class, bounds, editable) so an agent can still tap and
  type into it.
- Text *searches* match the redacted value too, so `/find_nodes` cannot be used
  as a character-by-character oracle against a secret field.
- **Not covered:** `/screenshot` and `/screen_record` capture rendered pixels, so
  anything visible on screen — a password mid-typing, an OTP the IME
  auto-filled — is in the image. Android blanks `FLAG_SECURE` windows; most login
  screens do not set it.

### Connection Architecture
- The phone connects **out** to the relay (NAT-friendly); the relay accepts one
  phone at a time and proxies every tool command to it
- **The phone also listens.** `BridgeApplication.onCreate` starts a Ktor HTTP
  server bound to `0.0.0.0:8765` for the lifetime of the app process, for USB/LAN
  development. There is no UI toggle and no way to stop it short of disabling the
  app, so "the phone is never directly exposed" is **not** true: anything on the
  same Wi-Fi can reach it and attempt to authenticate. It is guarded only by the
  same pairing code plus a per-IP throttle — and that throttle buckets the socket
  peer, so behind the reverse proxy this document recommends every caller shares
  one bucket (ROUTE-1 and AUTH-2 in the audit; both open product/architecture
  decisions).

## Known Limitations (Prototype)

### Plaintext transports allow command injection, not just sniffing
The scheme you type is the scheme the app dials: `wss://`/`https://` gets TLS,
`ws://`/`http://` and a **bare** host (`relay.example.com`, `203.0.113.7:8766`)
get plaintext `ws://` — as does the phone's own LAN server on `:8765`, which has
no TLS option at all. A bare host cannot default to `wss://` because the phone has
no way to know whether the relay was given a certificate; when that was tried, the
address `android_setup` printed failed the TLS handshake against a plaintext
relay. `android_setup` now prints the scheme the relay is really serving
(`wss://ip:port` when `ANDROID_RELAY_CERT`/`ANDROID_RELAY_KEY` are set,
`ws://ip:port` otherwise), so the documented setup is the TLS one whenever TLS
exists. Plaintext means:
- Commands, screen content, and screenshots travel unencrypted and can be read by
  anyone on the network path
- **A plaintext WebSocket has no integrity protection.** An on-path attacker does
  not need the pairing code and does not need to break anything: they can append
  server→client frames to the established TCP stream, and the phone dispatches
  every arriving frame as an already-authenticated command. Reading the traffic is
  the lesser problem — injecting `/send_sms`, `/call` or `/intent` is the real one
- **There is no server authentication on `ws://`.** The phone sends its long-lived
  pairing code to whatever host the name resolves to, so DNS spoofing or a rogue
  access point harvests the credential — and because that one code is shared by
  the relay and the `:8765` server, it works against both
- The app shows a persistent red warning on the relay status line (and a toast)
  when you connect over `ws://` to a non-private host
- **Mitigation**: use `wss://`. The relay can terminate TLS itself
  (`ANDROID_RELAY_CERT` / `ANDROID_RELAY_KEY`), or sit behind a reverse proxy with
  Let's Encrypt (nginx/caddy). Restrict `ws://` to a LAN/loopback address you
  control, and never to a public host

### Full Device Access
Once paired, the agent has unrestricted access to the device. The list below is
the shipped surface, mapped to what it needs — not a subset chosen to look calm.
Grant the permissions with this table in front of you.

| Capability | Endpoints | Requires |
|---|---|---|
| Read all screen content, in any app | `/screen` `/find_nodes` `/describe_node` `/screen_hash` `/diff_screen` `/widgets` `/wait` `/current_app` | Accessibility Service |
| Full-frame still images and video of the screen | `/screenshot` `/screen_record` | MediaProjection prompt |
| Inject input anywhere: tap, long-press, drag, swipe, pinch, scroll, type, hardware keys, media keys | `/tap` `/tap_text` `/long_press` `/drag` `/swipe` `/pinch` `/scroll` `/type` `/press_key` `/media` | Accessibility Service |
| Launch any app | `/open_app` `/apps` | — |
| **Record the microphone** and download the WAV | `/mic_start` `/mic_stop` `/mic_status` `/mic_file` | `RECORD_AUDIO` |
| **Send SMS** | `/send_sms` | `SEND_SMS` |
| **Place phone calls** | `/call` | `CALL_PHONE` (otherwise opens the dialler) |
| **Read contacts** | `/contacts` | `READ_CONTACTS` |
| **Read GPS location** | `/location` | `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` |
| **Read and overwrite the clipboard** | `/clipboard` | Accessibility Service |
| **Read notifications, including dismissed ones** | `/notifications` | Notification Listener |
| Interaction history across every app | `/events` `/events/stream` | Accessibility Service |
| **Fire arbitrary intents and broadcasts** | `/intent` `/broadcast` | — (denylist-filtered, see below) |
| **Run Termux scripts** (opt-in revival watchdog) | RelayClient `RUN_COMMAND` | Termux → "Allow external apps" |
| Speak via TTS | `/speak` `/stop_speaking` | — |
| Battery state | `/battery` `/ping` | — |

There is no granular permission system and no per-command confirmation on the
device: an authenticated caller (or a relay, or anyone who can inject into a
plaintext `ws://` session) can do all of the above, in banking apps and message
threads included. A malicious or compromised relay is fully trusted by design
(RELAY-1), and the destructive-action confirmation gate is an open product
decision (CTRL-1).

`/intent` and `/broadcast` are denylist-filtered rather than allowlisted:
- `/intent` rejects package install/uninstall, factory reset and shutdown,
  direct-dial telephony actions (`CALL`, `CALL_PRIVILEGED`), every
  `android.settings.*` and `android.provider.action.*` action, the
  `tel:`/`sms:`/`smsto:`/`mms:`/`mmsto:`/`market:`/`intent:` URI schemes, and
  personal `content://` providers (SMS, MMS, MMS-SMS, call log, telephony,
  contacts, ICC, settings). The URI is trimmed and lower-cased before the check,
  so leading whitespace and mixed case are not bypasses.
- `/broadcast` rejects the media/package/storage state broadcasts plus shutdown,
  master clear and factory reset.

`packageOverride` on `/intent` is **not** constrained, and a denylist can always
be gapped — an allowlist is the only shape that cannot be.

- **Mitigation**: only pair with Hermes instances you trust; disconnect (or force
  stop the app) when not in use; rotate the pairing code if it may have leaked —
  rotation now actually invalidates the old one; keep the status overlay on so you
  can see when the bridge is active.

### No Command Audit Log
There is no persistent log on the phone of what commands the agent executed.
- The relay does log: `contrib/hermes-relay-daemon.py` runs it at INFO, so
  commands (method + path, with PII-bearing body fields redacted) land in
  journald/stdout on the server. That is a server-side trail, not a device-side
  one, and it disappears with the relay's journal.
- **Mitigation**: keep relay INFO logging on and retain the journal if you need an
  audit trail; the phone itself keeps none.

## Recommendations for Production Use

1. **Add TLS**: give the relay `ANDROID_RELAY_CERT`/`ANDROID_RELAY_KEY`, or put it
   behind a reverse proxy (nginx/caddy) with Let's Encrypt certificates. Note that
   a reverse proxy collapses the per-IP auth throttle into one bucket unless you
   forward and honour `X-Forwarded-For` (AUTH-2).
2. **Use a strong pairing code**: Don't share your code publicly
3. **Disconnect when idle**: Tap Disconnect in the app when you're not actively using it
4. **Monitor the phone**: Keep the status overlay enabled to see when the bridge is active
5. **Don't pair on public WiFi**: an unencrypted connection can be read *and
   injected into*, and a rogue AP can harvest the pairing code from the handshake
6. **Assume `:8765` is open** on any network the phone joins, until there is a
   toggle for it (ROUTE-1)

## Reporting Security Issues

If you find a security vulnerability, please open an issue on GitHub or contact the maintainers directly. Do not exploit vulnerabilities on other people's devices.
