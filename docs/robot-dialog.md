---
summary: "Robot dialog mode: three voice backends, cinematic Pixel face, private transcripts, and Hermes long-term memory."
read_when:
  - "Setting up the robot face or conversation mode"
  - "Changing GPT Live or Hermes dialog routing"
  - "Reviewing transcript and long-term-memory behavior"
---

# Robot dialog mode

Robot dialog mode turns the Pixel into Cradata's conversational face without
giving the voice channel Hermes' device-control tools. The Pixel offers three
explicit choices:

1. **GPT Live** — direct, interruptible speech-to-speech with the OpenAI
   Realtime API over WebRTC.
2. **Hermes Lokal** — push-to-talk through the Hermes model alias `lokal`.
   In the current operator configuration this selects the local Ollama model.
3. **Hermes Standard** — push-to-talk through the Hermes model alias `schnell`.
   In the current operator configuration this selects DeepSeek V4 Flash.

The two Hermes modes use the same Pixel microphone, warm local Whisper worker,
sentence-queued Android TTS, and animated face. GPT Live carries microphone and
speaker audio over WebRTC, so it can listen and answer continuously until the
user taps **GPT Live beenden**, leaves the robot screen, or ends the session.

```text
Hermes Lokal / Standard
  Pixel push-to-talk -> temporary WAV -> local Whisper
  -> restricted Hermes worker (memory toolset only)
  -> sentence-queued Pixel TTS

GPT Live
  Pixel WebRTC audio <-> OpenAI Realtime
  Mac relay exchanges authenticated SDP; the API key never reaches Android
  -> final user/assistant transcripts return to the Mac

All three modes
  -> private JSONL transcript archive
  -> asynchronous Hermes memory observer (memory toolset only)
  -> selected durable facts in Hermes long-term memory
```

Switching backends clears the active answer session. Ending the conversation
starts a fresh transcript file and fresh Hermes answer sessions next time. The
memory observer intentionally keeps its own persistent Hermes session so it can
avoid duplicate or contradictory memories.

## Choosing a backend

The choice is made visibly on the Pixel's **ROBOTER-DIALOG** screen; it is not
inferred from the spoken content.

| Pixel choice | Response path | Default model choice | Best for |
| --- | --- | --- | --- |
| GPT Live | Pixel WebRTC ↔ OpenAI Realtime | visible tier: Sparsam, Stark, or Top | Natural, interruptible live conversation |
| Hermes Lokal | Pixel push-to-talk → Mac → Hermes | `lokal` | Private local-model experiments |
| Hermes Standard | Pixel push-to-talk → Mac → Hermes | `schnell` | Fast cloud-backed Hermes answers |

When GPT Live is selected, the Pixel shows three explicit quality/cost tiers.
Only the tier name crosses the private relay; the actual model mapping and API
key remain on the Mac:

| Pixel label | Wire tier | Default Realtime model | Intended tradeoff |
| --- | --- | --- | --- |
| Sparsam | `mini` | `gpt-realtime-2.1-mini` | Lowest cost and latency |
| Stark | `standard` | `gpt-realtime-2` | Balanced quality |
| Top | `top` | `gpt-realtime-2.1` | Highest configured voice quality |

The Mac-side environment can override each mapping. Restart the relay after a
change. Never put a model override or OpenAI key in the APK.

## Recording and memory semantics

"Record" means a text transcript, not retained microphone audio:

- Full text is written to `~/.hermes/robot-dialog/transcripts/` as private
  JSONL files (`0700` directory, `0600` files). Nothing is placed in the repo.
- GPT Live final transcript events are archived individually, so a delayed or
  missing answer cannot silently erase the user's side of the conversation.
- Completed question/answer pairs are queued to a passive Hermes memory
  observer. This happens in the background and does not delay the spoken reply.
- Obvious credentials, email addresses, and phone numbers are removed before a
  turn reaches that observer; the private full transcript remains unchanged.
- The observer stores only durable, non-sensitive facts, preferences,
  relationships, and open plans. It rejects credentials, phone numbers,
  email addresses, full addresses, and health or intimate details.
- Hermes' answer sessions can read existing memory. The voice workers receive
  only the `memory` toolset—no terminal, device, purchase, message, or call
  tools. Spoken `@file` syntax is neutralized before Hermes receives it.
- Push-to-talk WAV files are temporary and deleted after transcription. GPT
  Live audio is not written to disk by this project.

The transcript archive and curated long-term memory can be disabled separately
with `ROBOT_DIALOG_TRANSCRIPT_ARCHIVE=false` or
`ROBOT_DIALOG_HERMES_MEMORY=false`.

## Configuration

Values belong in `~/.hermes/.env`; never put them in the APK or Git:

```dotenv
ROBOT_DIALOG_PROFILE=general
ROBOT_DIALOG_BACKEND=hermes_local

# The normal Android bridge URL may point straight at the Pixel. Robot-dialog
# events must instead reach the Mac relay. Use the Mac's private Tailscale
# address here; never publish this address or a pairing code.
# ROBOT_DIALOG_RELAY_URL=http://<mac-tailscale-address>:8765

# Existing Hermes model aliases. Override only if your config uses other names.
ROBOT_DIALOG_HERMES_LOCAL_MODEL=lokal
ROBOT_DIALOG_HERMES_STANDARD_MODEL=schnell
ROBOT_DIALOG_HERMES_MEMORY_MODEL=schnell

ROBOT_DIALOG_HERMES_MEMORY=true
ROBOT_DIALOG_TRANSCRIPT_ARCHIVE=true
# Optional override; default is ~/.hermes/robot-dialog/transcripts
# ROBOT_DIALOG_TRANSCRIPT_DIR=/private/path/to/transcripts

ROBOT_DIALOG_WHISPER_MODEL=small
ROBOT_DIALOG_RECORD_SECONDS=10
ROBOT_DIALOG_VAD_SILENCE_MS=700

# Required only for GPT Live. API billing is separate from ChatGPT subscriptions.
OPENAI_API_KEY=...
# The Pixel selects mini/standard/top; these Mac-only variables map each tier.
ROBOT_DIALOG_OPENAI_REALTIME_MODEL_MINI=gpt-realtime-2.1-mini
ROBOT_DIALOG_OPENAI_REALTIME_MODEL_STANDARD=gpt-realtime-2
ROBOT_DIALOG_OPENAI_REALTIME_MODEL=gpt-realtime-2.1
ROBOT_DIALOG_OPENAI_TRANSCRIBE_MODEL=gpt-4o-mini-transcribe
ROBOT_DIALOG_OPENAI_REALTIME_VOICE=marin
```

Validate without recording or calling a model:

```bash
python -m tools.robot_dialog --check
```

Start the companion and bring the face forward:

```bash
python -m tools.robot_dialog --profile general --show
```

The default `general` profile is a normal conversation profile, not a child
content policy. The hard boundary is capability-based: this voice surface can
listen, answer, read memory, and curate memory, but cannot operate the phone or
perform external actions.

## Mac relay and companion runtime

The Mac relay and the dialog companion are separate processes. The relay must
be running before a Pixel button can start a conversation; the companion then
consumes the Pixel's backend and talk events. A direct `ANDROID_BRIDGE_URL` may
point to a phone for development, but robot events must use the private Mac
relay via `ROBOT_DIALOG_RELAY_URL`.

- Bind the relay only to the private Tailscale interface; do not publish an
  unauthenticated or plaintext relay to the internet.
- Keep `OPENAI_API_KEY` only in the Mac's private `~/.hermes/.env`. The relay
  reads it at startup; restart the relay after changing it.
- A Mac-local supervisor may keep the relay and companion alive across terminal
  closures. Such service definitions are deployment configuration, not project
  files, and must not contain secrets.
- To operate GPT Live: open **ROBOTER-DIALOG**, select **GPT Live**, tap
  **Sparsam**, **Stark**, or **Top**, tap **GPT Live starten**, and grant the
  Pixel microphone permission. End it with **GPT Live beenden**.
- OpenAI API billing is separate from a ChatGPT subscription. A
  `credit_balance_exhausted` response means the relay and key reached OpenAI,
  but the API account needs credits before a Realtime session can start.

## GPT Live implementation notes

The Pixel uses Android System WebView's WebRTC implementation. It sends its SDP
offer to the authenticated Mac relay, which adds the session configuration and
calls OpenAI's unified `/v1/realtime/calls` endpoint with the standard API key.
Only the SDP answer returns to Android. Final input and output transcripts go
back through the already-authenticated bridge event channel for the common
archive and Hermes memory observer.

The SDP payload is sent as `application/sdp` and its trailing CRLF is preserved;
removing that line ending can turn an otherwise valid WebRTC offer into an
`invalid_offer` response. Upstream failures are reduced to bounded error codes
before Android sees them, so provider messages or credentials are never
forwarded to the Pixel UI.

## Microphone ownership with the noise watcher

Android cannot reliably give the same microphone to the continuous loud-noise
watcher and a robot conversation at once. When RobotActivity opens, the bridge
therefore pauses only the watcher's microphone loop and waits for the recorder
to become available. Leaving the robot screen resumes the watcher with its
previous threshold, clip duration, cooldown, and active preference. It is not
necessary to permanently disable loud-noise monitoring before a conversation.

This follows OpenAI's recommended mobile/browser topology:

- [Realtime API with WebRTC](https://developers.openai.com/api/docs/guides/realtime-webrtc)
- [Realtime conversations](https://developers.openai.com/api/docs/guides/realtime-conversations)
- [Realtime transcription](https://developers.openai.com/api/docs/guides/realtime-transcription)

OpenAI Realtime sessions have a finite maximum duration; start a new GPT Live
session from the Pixel after one ends. If the relay, transcript channel, or
microphone permission is unavailable, the Pixel stops GPT Live instead of
continuing an unrecorded conversation.

## Installation packaging

The installer copies `robot_dialog.py`, `hermes_dialog_worker.py`, and the warm
Whisper worker into the Hermes plugin directory. The standalone development
copy remains under `tools/`; the APK itself has no Python dependency.
