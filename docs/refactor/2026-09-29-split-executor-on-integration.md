# Split ActionExecutor / CommandDispatcher on `qwen/integration-split`

Ephemeral scratchpad. Delete when the work lands (docs/refactor/README.md).

## Situation

- Branch `qwen/integration-split`, based on `qwen/integration` (tip `eae75cf`).
- `qwen/split-executor` already did this split on top of OLD `main`
  (merge-base `92b4204`). Its **layout is the template**; its code is stale.
  Do **not** cherry-pick its refactor commits.
- Baseline before any change: `testDebugUnitTest` = **464 tests, 0 failures,
  0 skipped, 51 suites**; `assembleDebug` green (2m14s cold).
- Sizes before split: `ActionExecutor.kt` 1131 lines,
  `CommandDispatcher.kt` 621 lines.

## Behaviours that changed since the template branch (must re-verify, not copy)

1. **SMS reporting** — `sendSms` now divides the body, registers a per-call
   receiver, and answers via `SmsSendReport.describe(awaitTally(...))` /
   `describeUnobservable`. Statuses: `sent`, `queued_unconfirmed`,
   `partially_sent`, `failed`. The template's `sendSms` is the old
   fire-and-forget one.
2. **Password redaction** — `SensitiveText` gates `describeNode`'s
   text/contentDescription/hintText (PII-1).
3. **sendIntent denylist** — grew a lot: dotted package-manager actions are
   *not* exempt, `android.settings.*` / `android.provider.action.*` prefixes
   blocked, URI normalised once before scheme/prefix checks (CTRL-2).
4. **`bridgeTest()`** — `BridgeTestCoroutines.kt` gives `runTest` a 120 s
   wall-clock dispatch timeout. The template used ad-hoc
   `runTest(dispatchTimeoutMs = 60_000)`; use `bridgeTest()` instead.

## Test seams that must stay reachable at their current names

- `CommandDispatcher.batteryContext` (`internal var`)
- `CommandDispatcher.smsSender` (`internal var`)
- `CommandDispatcher.clampWaitTimeoutMs` / `clampRecordDurationMs` /
  `errorBody` / `DEFAULT_*` / `MAX_*` / `MIN_RECORD_DURATION_MS` constants
- `ActionExecutor.smsSendReportTimeoutMs` (`internal var`)
- `ActionExecutor.smsSentReportAction(callId)` (`internal fun`)
- `ScreenRecorder`'s seam, `WakeLockManager`, `NotificationStore`, `EventStore`
  streaming gate — untouched by the split.

Plan for the two `ActionExecutor` SMS seams: storage moves into
`DeviceServicesExecutor`; `ActionExecutor` keeps a delegating
`internal var` / `internal fun` at the same name so `ActionExecutorSendSmsTest`
is untouched.

## Target layout (same as template)

`executor/`
- `ActionExecutor` — pure facade, every public signature + defaults kept
- `NodeLookup` — `findNodeById`, `findNodeByIdInTree`
- `GestureExecutor` — tap, tapText, swipe, scroll, longPress, drag, pinch
- `TextEntryExecutor` — typeText, clipboardRead, clipboardWrite
- `SystemKeyExecutor` — pressKey, mediaControl
- `ScreenshotExecutor` — takeScreenshot
- `AppIntentExecutor` — openApp, getInstalledApps, sendIntent, sendBroadcast
- `DeviceServicesExecutor` — location, sendSms (+ SMS report helpers),
  makeCall, searchContacts, `Context.hasSelfPermission`
- `ScreenQueryExecutor` — waitForElement, findNodes, diffScreen, screenHash,
  describeNode, readWidgets (+ findInTree, both countNodes, collectWidgetInfo)
- `TtsExecutor` — tts/ttsReady state, ensureTts, speak, stopSpeaking, shutdownTts

`server/`
- `CommandDispatcher` — keeps the whole `(method, path)` routing table,
  `/ping` and the 404 arm inline; every other arm delegates
- `ScreenCommands` /screen /screenshot /describe_node /find_nodes /diff_screen
  /screen_hash /wait /widgets /current_app (+countAllNodes)
- `InputCommands` /tap /tap_text /type /swipe /scroll /long_press /drag /pinch
  /press_key /clipboard (GET+POST)
- `AppIntentCommands` /open_app /apps /intent /broadcast
- `DeviceCommands` /location /battery /send_sms /call /contacts /media
- `NotificationCommands` /notifications /events /events/stream
- `MediaCommands` /speak /stop_speaking /mic_start /mic_stop /mic_status
  /screen_record

## Rules

No logic edits, no route/JSON-field renames, public signatures (incl.
defaults) kept, one commit per extracted unit, `testDebugUnitTest` +
`assembleDebug` green after **every** commit. Verbatim-move proof: a
normalised diff of every function body before vs after (see
"Verbatim verification" below).

## Checklist

- [ ] 1. Characterization tests from `061abe8` + `b69fbfe`, adapted to current
      behaviour and to `bridgeTest()`. Committed and green before any refactor.
- [ ] 2a. executor: NodeLookup
- [ ] 2b. executor: GestureExecutor
- [ ] 2c. executor: TextEntryExecutor
- [ ] 2d. executor: SystemKeyExecutor
- [ ] 2e. executor: ScreenshotExecutor
- [ ] 2f. executor: AppIntentExecutor
- [ ] 2g. executor: DeviceServicesExecutor (incl. SMS send/report)
- [ ] 2h. executor: ScreenQueryExecutor
- [ ] 2i. executor: TtsExecutor (facade complete)
- [ ] 2j. server: ScreenCommands
- [ ] 2k. server: InputCommands
- [ ] 2l. server: AppIntentCommands
- [ ] 2m. server: DeviceCommands
- [ ] 2n. server: NotificationCommands
- [ ] 2o. server: MediaCommands
- [ ] 3. Pinch edge-clamp fix + regression test (own commit).
      Latent crash: `IllegalArgumentException` "Path bounds must not be
      negative" when the pinch centre is within `50*scale` px of an edge,
      e.g. `pinch(x=50)` at default `scale=1.5`. Check the top edge too.
- [ ] 4. CHANGELOG Unreleased entry (refactor + pinch fix); delete this doc.
- [ ] 5. `HOME=$(mktemp -d) HERMES_HOME=$(mktemp -d) .venv/bin/pytest -q`

## Verbatim verification (method)

Extract every top-level/member function body from `ActionExecutor.kt` and
`CommandDispatcher.kt` at the pre-split commit and from the post-split files,
normalise (strip leading indentation, drop blank lines, drop import/package
headers, qualify `findNodeById` -> `NodeLookup.findNodeById` and the handler
call sites), and diff the sorted multisets. Zero delta == pure move.

## Progress log

- Baseline recorded (464 tests green, assembleDebug green).
