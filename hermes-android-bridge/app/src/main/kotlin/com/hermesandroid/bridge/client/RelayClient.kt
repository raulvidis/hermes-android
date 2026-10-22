package com.hermesandroid.bridge.client

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.server.CommandDispatcher
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * WebSocket client that connects OUT to the Hermes relay server.
 * Receives commands over WebSocket, dispatches them to [CommandDispatcher],
 * and sends results back.
 *
 * Auto-reconnects on disconnect with exponential backoff (1s, 2s, 4s, 8s, max 30s).
 */
object RelayClient {

    private const val TAG = "RelayClient"
    private const val PREFS_NAME = "hermes_bridge_prefs"
    private const val KEY_SERVER_URL = "relay_server_url"

    /**
     * AUTH-1: this duplicate code store is gone. [PairingManager] is the single
     * source of truth for the code presented on the relay handshake, so tapping
     * **Regenerate** rotates what the relay client actually presents. The legacy
     * key is cleared in [init] so an upgraded install cannot keep a stale,
     * still-valid code on disk next to the rotated one.
     */
    private const val KEY_LEGACY_PAIRING_CODE = "relay_pairing_code"
    private const val MAX_BACKOFF_MS = 30_000L
    private const val MAX_RETRIES = 5

    // --- Termux revival watchdog ---
    // When the relay is unreachable past these thresholds, fire the
    // Termux RUN_COMMAND intent to restart the whole stack.
    private const val WATCHDOG_FAILURES_TO_TRIGGER = 3
    private const val WATCHDOG_COOLDOWN_MS = 600_000L  // 10 min
    private const val WATCHDOG_MAX_FIRES = RevivalPolicy.DEFAULT_MAX_FIRES
    private val revivalPolicy = RevivalPolicy(
        WATCHDOG_FAILURES_TO_TRIGGER,
        WATCHDOG_COOLDOWN_MS,
        maxFires = WATCHDOG_MAX_FIRES,
    )
    private const val KEY_FAILURES = "watchdog_failures"
    private const val KEY_FIRES = "watchdog_fires"
    private const val KEY_LAST_FIRE = "watchdog_last_fire_ms"
    private const val KEY_REVIVAL_ENABLED = "termux_revival_enabled"

    /** How long the watchdog waits after firing before retrying the connection. */
    internal const val REVIVAL_RECONNECT_DELAY_MS = 30_000L

    /**
     * Scope the post-revival delayed reconnect runs on (review F9).
     *
     * This must NOT be the per-connection [scope]. The reconnect calls
     * [connect], whose first statement is [disconnect] — and `disconnect()` does
     * `scope?.cancel(); scope = null`, cancelling the very scope the caller is
     * running on. It only ever worked because nothing between `disconnect()` and
     * the end of `doConnect()` suspends, so cancellation of the enclosing job
     * could not interrupt it. The moment `connect()` or `doConnect()` gains a
     * suspension point — `withContext(Dispatchers.Main)` to update the UI, an
     * `await` on DNS — the first one throws `CancellationException`, the
     * reconnect aborts silently, and the watchdog fires the script but never
     * reconnects: exactly the bug the comment at the call site claims was fixed.
     *
     * The scope outliving `disconnect()` is the point, and the reason the job has
     * to be tracked separately: see [revivalReconnectJob] (review F11).
     */
    internal var revivalScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Handle for the one pending post-revival reconnect (review F11).
     *
     * F9's remedy was a scope nobody cancels, which over-corrected: `disconnect()`
     * cleared `shouldReconnect` and tore the session down, but the coroutine
     * waiting out its 30 s was nobody's to stop, so a user who tapped
     * **Disconnect** — or **Connect** against a different relay — was silently
     * overridden by a dial they had already cancelled. The scope still outlives
     * the session; the job does not.
     *
     * The coroutine clears this handle itself, under the same monitor, before it
     * calls the connector: `connect()` cancels [revivalReconnectJob], and
     * cancelling the coroutine that is making the call would reintroduce exactly
     * F9's "fires but never reconnects".
     *
     * Readable outside the object so `RelayClientRevivalReconnectTest` can pin
     * [revivalReconnectMayDial] against the states this handle passes through, but
     * **not writable** (review F40): an `internal var` let any code in the module —
     * including a test — publish a job that `reconnectDelayed()` never scheduled,
     * which is a state production cannot reach and a test could assert on happily.
     * Only `reconnectDelayed()`, `cancelRevivalReconnect()` and the coroutine itself
     * assign it, and the tests reach every state they need through those.
     */
    @Volatile
    internal var revivalReconnectJob: Job? = null
        private set

    /** Seam so a test can observe the reconnect without opening a real WebSocket. */
    internal var revivalConnector: (url: String, code: String) -> Unit = { url, code ->
        connect(url, code)
    }

    // Termux RUN_COMMAND target. Termux requires an absolute path under its own
    // $HOME, and the user must have enabled Termux > Settings > "Allow external
    // apps" — otherwise startService throws SecurityException.
    internal const val TERMUX_PACKAGE = "com.termux"
    internal const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    internal const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    internal const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    internal const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    internal const val EXTRA_RUN_COMMAND_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"

    /**
     * Script the watchdog asks Termux to run.
     *
     * This was a hardcoded
     * `/data/data/com.termux/files/home/.termux/boot/start-zee.sh` — one
     * contributor's private stack launcher, shipped in a public repo and a public
     * APK, not configurable and not documented anywhere. Any other user who
     * enabled the "Termux Auto-Revival" switch had the bridge fire RUN_COMMAND at
     * a script that did not exist on their device: Termux logged an error, the
     * bridge showed "Stack unreachable — firing Termux revival…", nothing
     * recovered, and the user believed auto-recovery was on.
     *
     * The location is unchanged (Termux's own boot directory, so the same script
     * also runs when Termux starts); only the name is now the project's rather
     * than a person's. docs/configuration.md says what to put in it.
     */
    internal const val DEFAULT_REVIVAL_SCRIPT =
        "/data/data/com.termux/files/home/.termux/boot/start-hermes.sh"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(20))
        .build()

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private val reconnectPolicy = ReconnectPolicy(maxRetries = MAX_RETRIES, maxBackoffMs = MAX_BACKOFF_MS)
    private val rotationPolicy = PairingRotationPolicy()

    /**
     * Test seam: unit tests replace this to observe dial attempts (URL + code)
     * without opening a real socket. Production always routes to [doConnect].
     */
    internal var connector: (url: String, code: String) -> Unit = { url, code -> doConnect(url, code) }

    /**
     * Test seam: how a scheduled reconnect waits out its backoff. Production
     * behaviour is exactly [delay] — the default delegates to it and nothing
     * else changes. Tests substitute an instantaneous or parkable wait so
     * the retry cap and cancellation can be exercised without real time.
     */
    internal var backoffDelay: suspend (Long) -> Unit = { delay(it) }

    /** True between scheduling a reconnect and that attempt firing. Guards against
     *  onClosed + onFailure both scheduling for the same dead connection. */
    @Volatile
    private var reconnectPending: Boolean = false

    /** Bumped per connect attempt; callbacks from superseded sockets are ignored. */
    @Volatile
    private var generation: Int = 0

    /** `System.nanoTime()` at the last onOpen, or 0 when no session is open. */
    @Volatile
    private var sessionStartedNs: Long = 0L

    /**
     * HTTP status from the most recent failure **of the attempt now in flight**, or
     * 0 for a transport-level failure. The watchdog needs it: a 401/403 is a
     * credential problem that restarting Termux cannot fix, so it must not spend
     * the fire budget.
     *
     * Internal (test seam): `RelayClientFailureClassificationTest` pins that the
     * value describes one attempt — see [recordFailureHttpCode].
     */
    @Volatile
    internal var lastFailureHttpCode: Int = 0

    /** Guards the "watchdog gave up" status so it is reported once, not on every
     *  failure after the budget is spent. */
    @Volatile
    private var exhaustedNotified: Boolean = false

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var shouldReconnect: Boolean = false

    var serverUrl: String?
        get() = prefs?.getString(KEY_SERVER_URL, null)
        set(value) { prefs?.edit()?.putString(KEY_SERVER_URL, value)?.apply() }

    /**
     * The code presented to the relay. Always [PairingManager.getCode] — there is
     * deliberately no second store to drift out of sync (AUTH-1).
     */
    val pairingCode: String?
        get() = PairingManager.getCode().takeIf { it.isNotBlank() }

    /** Callback for UI updates. Called on main thread. */
    var onStatusChanged: ((connected: Boolean, message: String) -> Unit)? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        appContext = context.applicationContext
        // AUTH-1: drop the pre-fix duplicate code store; a rotated code must not
        // leave the previous one readable on disk.
        prefs?.edit()?.remove(KEY_LEGACY_PAIRING_CODE)?.apply()
    }

    // Shares the monitor with scheduleReconnect/beginReconnectAttempt: these run
    // on the main thread while callbacks schedule reconnects on OkHttp threads,
    // and an interleaving there can strand reconnectPending set with no
    // coroutine left to clear it, killing auto-reconnect for the process.
    @Synchronized
    fun connect(serverUrl: String, pairingCode: String = "") {
        // F11: an explicit connect supersedes a pending post-revival reconnect.
        // Before the code check, not only via disconnect() below — a connect that
        // bails out on a missing pairing code must still cancel the stale dial,
        // otherwise the watchdog reconnects to a URL the user just walked away from.
        cancelRevivalReconnect()
        // AUTH-1: the code always comes from PairingManager; the argument is only
        // a fallback for a manager that has no code yet (pre-init / unit tests).
        val code = effectivePairingCode(pairingCode)
        if (code == null) {
            notifyStatus(false, "No pairing code available — tap Regenerate")
            return
        }

        disconnect()

        this.serverUrl = serverUrl
        shouldReconnect = true
        reconnectPolicy.reset()
        // F12: the auth classification is per attempt. A code left over from a
        // previous connect cycle must not be read as this one's credential
        // failure — exhaustion can be reached through onClosed, which writes
        // nothing, so whatever is sitting here is what the watchdog believes.
        lastFailureHttpCode = 0
        // A manual Connect is the user re-arming, so the "gave up" notice is
        // allowed to be shown again if this attempt exhausts the budget too.
        // The fire budget itself is only refilled by a session that opens.
        exhaustedNotified = false

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        connector(serverUrl, code)
    }

    private fun effectivePairingCode(requested: String?): String? =
        PairingManager.getCode().takeIf { it.isNotBlank() }
            ?: requested?.takeIf { it.isNotBlank() }

    /**
     * Whether a woken post-revival reconnect may still dial (review F32).
     *
     * `shouldReconnect` alone is not enough. The coroutine wakes from its delay and
     * then has to wait for the monitor, and `disconnect()`/`connect()` are both
     * `@Synchronized` — so a user who disconnects and reconnects while it was waiting
     * leaves the flag **true**, because their own `connect()` set it. A flag-only
     * guard then dials the old URL over the top of the connection the user just
     * made: F11 again, one lock acquisition later.
     *
     * So the job checks itself as well — still uncancelled, and still the one
     * [reconnectDelayed] last scheduled. Called from inside the monitor the dial also
     * runs under, which is what makes the answer and the dial one step (F25).
     *
     * `@Synchronized` so it is correct standalone; reentrant from the coroutine, which
     * already holds the monitor.
     */
    @Synchronized
    internal fun revivalReconnectMayDial(self: Job?): Boolean {
        if (self == null) return false
        // Cancelled while it waited for the monitor: disconnect(), connect() or a
        // newer watchdog fire all cancel it, and only connect() re-arms the flag.
        if (self.isCancelled) return false
        // Superseded: a second fire replaced the handle, or a disconnect cleared it.
        if (revivalReconnectJob !== self) return false
        return shouldReconnect
    }

    /** F11: drop a reconnect the watchdog scheduled but the user has overtaken. */
    @Synchronized
    private fun cancelRevivalReconnect() {
        revivalReconnectJob?.cancel()
        revivalReconnectJob = null
    }

    /**
     * Record the HTTP status a failed attempt reported (review F12).
     *
     * The generation check and the write have to be one step. `onFailure` runs on
     * an OkHttp callback thread, and an inline `if (myGeneration != generation)`
     * followed by an assignment let a socket that had already been superseded pass
     * the check, be descheduled, and write *after* the attempt that replaced it had
     * connected and reset the field. The stale 401 then sat there until exhaustion
     * was reached through a clean `onClosed` — which writes nothing — and
     * `maybeFireTermuxRevival` read it as "a credential problem, do not spend the
     * fire budget": a wrong value does not just mislabel a log, it switches the
     * recovery path off for that whole cycle.
     *
     * Internal (test seam) so the guard can be pinned without opening a socket.
     */
    @Synchronized
    internal fun recordFailureHttpCode(attemptGeneration: Int, httpCode: Int) {
        if (attemptGeneration != generation) return
        lastFailureHttpCode = httpCode
    }

    /** Test seam: the attempt counter, so a test can hold a stale generation. */
    internal fun currentGeneration(): Int = generation

    /**
     * AUTH-1: called after [PairingManager.regenerateCode]. Any session that was
     * authenticated with the OLD code must stop working immediately, so the live
     * socket is dropped and — when a relay URL is configured — re-dialed with the
     * new code. Returns the action taken so the UI can report it instead of
     * silently claiming success.
     */
    @Synchronized
    fun onPairingCodeRotated(): PairingRotationPolicy.Action {
        val action = rotationPolicy.onCodeRotated(
            savedUrl = serverUrl,
            sessionActive = isConnected,
            reconnectArmed = shouldReconnect
        )
        when (action) {
            PairingRotationPolicy.Action.NONE -> Unit
            PairingRotationPolicy.Action.DROP -> disconnect()
            // connect() disconnects the superseded socket first, then dials with
            // the code PairingManager holds *now*.
            PairingRotationPolicy.Action.RECONNECT -> serverUrl?.let { connect(it) }
        }
        return action
    }

    @Synchronized
    fun disconnect() {
        shouldReconnect = false
        cancelRevivalReconnect()
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectPending = false
        reconnectPolicy.reset()
        sessionStartedNs = 0L
        generation++
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        scope?.cancel()
        scope = null
        isConnected = false
        BridgeAccessibilityService.instance?.stopForeground()
        notifyStatus(false, "Disconnected")
    }

    /** Try to auto-connect if server URL was previously saved. */
    fun autoConnect() {
        val url = serverUrl
        val code = pairingCode
        if (!url.isNullOrBlank() && !code.isNullOrBlank()) {
            Log.i(TAG, "Auto-connecting to $url")
            connect(url, code)
        }
    }

    private fun doConnect(serverUrl: String, pairingCode: String) {
        val myGeneration = ++generation
        val wsUrl = buildWsUrl(serverUrl)
        Log.i(TAG, "Connecting to $wsUrl")
        notifyStatus(false, "Connecting to $wsUrl ...")

        // Token goes in the Authorization header, not the URL — query strings
        // end up verbatim in reverse-proxy access logs.
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $pairingCode")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myGeneration != generation) {
                    webSocket.cancel()
                    return
                }
                Log.i(TAG, "WebSocket connected to ${buildWsUrl(serverUrl)}")
                isConnected = true
                // A session that actually opened is the re-arm: reset the failure
                // counter AND the fire budget, so a later unrelated outage is not
                // ignored for the rest of the process lifetime.
                prefs?.edit()?.putInt(KEY_FAILURES, 0)?.putInt(KEY_FIRES, 0)?.apply()
                exhaustedNotified = false
                lastFailureHttpCode = 0
                // NOT a policy reset: the budget is only refilled once this
                // session proves stable (see endSession), so a relay that
                // accepts and instantly drops us can't retry forever.
                sessionStartedNs = System.nanoTime()
                try {
                    BridgeAccessibilityService.instance?.startForeground()
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not promote bridge service to foreground", e)
                }
                notifyStatus(true, "Connected to $serverUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope?.launch {
                    handleMessage(webSocket, text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGeneration != generation) return
                Log.i(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                endSession()
                notifyStatus(false, "Closed: code=$code $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code ?: 0
                // Guarded write, before the early return: see recordFailureHttpCode.
                recordFailureHttpCode(myGeneration, httpCode)
                if (myGeneration != generation) return
                // Audited for F35 and deliberately left as-is: this is a transport
                // failure, so `t.message` can carry the relay's host — which line 381
                // already logs on purpose — and nothing from the device. It is also
                // the text the user sees on the relay status line, where "Unable to
                // resolve host" is the difference between a diagnosable failure and
                // an opaque one.
                val errorDetail = "Error: ${t.javaClass.simpleName}: ${t.message} (HTTP $httpCode)"
                Log.e(TAG, "WebSocket failure: $errorDetail", t)
                isConnected = false
                endSession()
                notifyStatus(false, errorDetail)
                scheduleReconnect()
            }
        })
    }

    // Invoked from OkHttp callback threads; the policy and the pending flag are
    // read-modify-written together, so serialize the whole decision.
    @Synchronized
    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val url = serverUrl ?: return
        val code = pairingCode ?: return
        // Resolve the scope BEFORE burning budget or raising reconnectPending:
        // a late callback after disconnect() finds a null scope, and committing
        // that state with no coroutine to clear it would stall reconnects for good.
        val activeScope = scope ?: return

        // onClosed and onFailure can both fire for one dead socket; only one of
        // them should turn into an attempt. This must come BEFORE the exhausted
        // check: otherwise the second callback sees the budget the first one
        // just spent and declares failure while that final attempt is still
        // waiting out its backoff, silently skipping the last retry.
        if (reconnectPending) return

        // Each failed attempt fires onFailure/onClosed, which lands back here.
        // Bail out once the shared attempt budget is spent — otherwise an
        // unreachable address reconnects forever.
        if (reconnectPolicy.isExhausted) {
            shouldReconnect = false
            reconnectPending = false
            // Retire the dead socket's listener too, otherwise a late callback
            // from it overwrites the terminal status the user needs to see.
            generation++
            // Its onClosed/onFailure will now be ignored, so drop the session
            // clock here — a stale start time would otherwise make the NEXT
            // session look long enough to refill the retry budget.
            sessionStartedNs = 0L
            webSocket?.cancel()
            webSocket = null
            notifyStatus(false, "Failed to connect after ${reconnectPolicy.limit} attempts. Tap Connect to retry.")
            maybeFireTermuxRevival()
            return
        }

        reconnectPending = true

        val backoff = reconnectPolicy.nextBackoffMs()
        val attempt = reconnectPolicy.attempts

        reconnectJob = activeScope.launch {
            Log.i(TAG, "Reconnecting in ${backoff}ms... (attempt $attempt/${reconnectPolicy.limit})")
            notifyStatus(false, "Reconnecting in ${backoff / 1000}s... (attempt $attempt/${reconnectPolicy.limit})")
            backoffDelay(backoff)
            beginReconnectAttempt(url, code)
        }
    }

    /**
     * Clearing [reconnectPending] and starting the attempt must happen as one
     * step under the same monitor as [scheduleReconnect]; clearing it earlier
     * lets a callback slip through and launch a second reconnect coroutine.
     */
    @Synchronized
    private fun beginReconnectAttempt(url: String, code: String) {
        reconnectPending = false
        if (!shouldReconnect || isConnected) return
        // Supersede the old listener BEFORE cancelling its socket: cancel()
        // drives that listener's onFailure, and if it still matched the current
        // generation it would re-enter scheduleReconnect and burn an attempt on
        // our own teardown.
        generation++
        // Retired listener => no endSession() for it; drop the clock so the
        // next session is measured from its own start, not this one's.
        sessionStartedNs = 0L
        // F12: this attempt starts unclassified, exactly as connect() does.
        lastFailureHttpCode = 0
        // Cancel the previous WebSocket before opening a new one, otherwise its
        // listener stays active and can fire out-of-order callbacks
        // (onOpen/onFailure) that set isConnected or push duplicate statuses.
        webSocket?.cancel()
        connector(url, code)
    }

    /** A session that had opened is over — refill the budget only if it was stable. */
    private fun endSession() {
        val startedNs = sessionStartedNs
        if (startedNs == 0L) return
        sessionStartedNs = 0L
        reconnectPolicy.onSessionEnded((System.nanoTime() - startedNs) / 1_000_000L)
    }

    /**
     * Termux revival watchdog: after repeated relay connection failures
     * (Termux stack likely dead), fire the RUN_COMMAND intent to restart
     * the whole stack. The cooldown bounds how often it fires and
     * [WATCHDOG_MAX_FIRES] bounds how many times in total — without the cap a
     * relay that is merely unreachable (wrong URL, rotated code, server down for
     * the weekend) made an opt-in device run the script every cooldown forever.
     */
    private fun maybeFireTermuxRevival() {
        val p = prefs ?: return
        val enabled = p.getBoolean(KEY_REVIVAL_ENABLED, false)
        val failures = p.getInt(KEY_FAILURES, 0)
        val fires = p.getInt(KEY_FIRES, 0)
        val lastFire = p.getLong(KEY_LAST_FIRE, 0L)
        val now = System.currentTimeMillis()
        val authFailure = lastFailureHttpCode == 401 || lastFailureHttpCode == 403
        val decision = revivalPolicy.onFailure(enabled, failures, lastFire, now, fires, authFailure)
        when (decision) {
            is RevivalPolicy.Decision.Wait -> {
                p.edit().putInt(KEY_FAILURES, decision.failures).apply()
                if (decision.failures > failures) {
                    Log.i(TAG, "Revival watchdog: ${decision.failures}/$WATCHDOG_FAILURES_TO_TRIGGER failures")
                }
                // The counter only advances when a retry cycle exhausts, and
                // exhaustion is terminal — so on an unattended device nothing
                // ever started the next cycle and the threshold was unreachable:
                // the switch was on, the stack was dead, and the watchdog sat at
                // 1/3 until someone tapped Connect. While the watchdog is armed,
                // schedule the next cycle itself. Bounded: once the fire budget
                // is spent the decision is Exhausted and the loop stops. Not for
                // a 401/403 (a restart cannot fix a credential, and the counter
                // does not move on one) or with the switch off (unchanged
                // give-up behaviour).
                if (enabled && !authFailure) {
                    notifyStatus(
                        false,
                        "Relay unreachable — revival watchdog ${decision.failures}/" +
                            "$WATCHDOG_FAILURES_TO_TRIGGER, retrying in " +
                            "${REVIVAL_RECONNECT_DELAY_MS / 1000}s…",
                    )
                    reconnectDelayed()
                }
            }
            is RevivalPolicy.Decision.Fire -> {
                p.edit()
                    .putInt(KEY_FAILURES, 0)
                    .putInt(KEY_FIRES, fires + 1)
                    .putLong(KEY_LAST_FIRE, now)
                    .apply()
                fireTermuxRestart(fires + 1)
            }
            is RevivalPolicy.Decision.Exhausted -> {
                if (!exhaustedNotified) {
                    exhaustedNotified = true
                    Log.w(
                        TAG,
                        "Revival watchdog spent its $WATCHDOG_MAX_FIRES-fire budget; " +
                            "it will not fire again until a connection succeeds",
                    )
                    notifyStatus(
                        false,
                        "Revival stopped after $WATCHDOG_MAX_FIRES attempts — check the server URL " +
                            "and pairing code, then tap Connect",
                    )
                }
            }
        }
    }

    /**
     * Build the RUN_COMMAND intent. Kept separate from firing it so the action,
     * the target component and the extras can be asserted in a unit test —
     * `fireTermuxRestart` itself needs a Context, prefs and a real startService.
     */
    internal fun termuxRevivalIntent(
        scriptPath: String = DEFAULT_REVIVAL_SCRIPT,
    ): android.content.Intent =
        android.content.Intent(TERMUX_RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            putExtra(EXTRA_RUN_COMMAND_PATH, scriptPath)
            putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
            putExtra(EXTRA_RUN_COMMAND_SESSION_ACTION, 0)
        }

    private fun fireTermuxRestart(fireNumber: Int) {
        val ctx = appContext ?: return
        try {
            ctx.startService(termuxRevivalIntent())
            Log.i(TAG, "Termux revival: RUN_COMMAND fired (attempt $fireNumber/$WATCHDOG_MAX_FIRES)")
            notifyStatus(
                false,
                "Stack unreachable — firing Termux revival ($fireNumber/$WATCHDOG_MAX_FIRES)…",
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Termux revival BLOCKED: RUN_COMMAND permission missing", e)
            notifyStatus(false, "Revival blocked — allow Termux RUN_COMMAND (Termux > Allow external apps)")
        } catch (e: Exception) {
            Log.e(TAG, "Termux revival intent failed", e)
            notifyStatus(false, "Revival failed: ${e.message?.take(80) ?: "unknown error"}")
        }
        // The watchdog fired but the app itself must keep trying to reconnect:
        // without this the relay stays dark even after a successful revival
        // (review point: "fires but never reconnects"). Also after a blocked or
        // failed intent — the outage may have been transient, and the fire was
        // already counted against the budget, so the loop still ends.
        reconnectDelayed()
    }

    internal fun reconnectDelayed() {
        try {
            val url = prefs?.getString(KEY_SERVER_URL, null)
            // Read through PairingManager, not a cached copy: a code rotated while
            // the watchdog was waiting must be the one presented (AUTH-1).
            val code = pairingCode
            if (url.isNullOrBlank() || code.isNullOrBlank()) {
                Log.w(TAG, "Delayed reconnect skipped: no saved server/code")
                return
            }
            synchronized(this) {
                // A second watchdog fire supersedes the reconnect already waiting.
                revivalReconnectJob?.cancel()
                // Both callers (a watchdog fire, and a watchdog still counting up to
                // its threshold) come from the exhausted-budget path, which has
                // just set shouldReconnect = false. The watchdog is deliberately scheduling
                // another attempt, so re-arm the flag the guard below reads;
                // otherwise F11's own check would cancel the reconnect the revival
                // just asked for. disconnect() and connect() clear it again, which
                // is what makes the guard mean "the user still wants this".
                shouldReconnect = true
                // revivalScope, not scope: see its doc. Launching on the
                // per-connection scope made this coroutine cancel itself the instant
                // it called connect().
                revivalReconnectJob = revivalScope.launch {
                    kotlinx.coroutines.delay(REVIVAL_RECONNECT_DELAY_MS)
                    // Guard AND dial in one critical section (review F25).
                    // disconnect() and connect() are both @Synchronized, so releasing
                    // the monitor between checking shouldReconnect and dialling left a
                    // window in which a user disconnect found no job left to cancel and
                    // a flag nothing would re-read — and the reconnect went ahead
                    // anyway. The monitor is reentrant, which is also what lets the
                    // dial call connect(), and so cancel this very job, without
                    // deadlocking (F9).
                    // F32: the guard has to cover this coroutine, not just the flag.
                    val self = coroutineContext[kotlinx.coroutines.Job]
                    synchronized(this@RelayClient) {
                        if (!revivalReconnectMayDial(self)) {
                            Log.i(TAG, "Delayed reconnect skipped: no longer wanted when it woke")
                        } else {
                            // Clear our own handle before dialling: connect() cancels
                            // revivalReconnectJob, and the reconnect must not be
                            // cancelled by the connect() it makes (F9).
                            revivalReconnectJob = null
                            revivalConnector(url, code)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // F35: `$e` interpolates toString(), which is `class: message`. Same
            // rule as logMessageFailure — the class name is enough to diagnose.
            Log.w(TAG, "Delayed reconnect not scheduled: ${e.javaClass.simpleName}")
        }
    }

    /**
     * TRANS-1: the scheme decision lives in [RelayUrl] so it can be tested as a
     * table. An explicit scheme always wins; a bare host keeps the plaintext
     * `ws://` it has always dialled, because only the operator knows whether the
     * relay is serving a certificate.
     *
     * Internal (test seam): `RelayClientBuildWsUrlTest` pins the delegation.
     */
    internal fun buildWsUrl(serverUrl: String): String {
        val wsUrl = RelayUrl.build(serverUrl)
        if (BuildConfig.DEBUG) Log.d(TAG, "Built WebSocket URL: $wsUrl")
        return wsUrl
    }

    private suspend fun handleMessage(ws: WebSocket, text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val requestId = json.get("request_id")?.asString ?: ""
            val method = json.get("method")?.asString?.uppercase() ?: "GET"
            val path = json.get("path")?.asString ?: ""
            val params = json.getAsJsonObject("params") ?: JsonObject()
            val body = json.getAsJsonObject("body") ?: JsonObject()

            if (BuildConfig.DEBUG) Log.d(TAG, "Received command: $method $path (id=$requestId)")

            if (requestId.isBlank()) {
                throw IllegalArgumentException("Command is missing request_id")
            }
            if (method == "GET" && path == "/mic_file") {
                streamMicrophoneRecording(
                    ws,
                    requestId,
                    params.get("name")?.asString,
                )
                return
            }

            // The relay connection is authenticated at connect time (Bearer token on the WS handshake),
            // so commands arriving here are already authenticated.
            val response = CommandDispatcher.dispatch(method, path, params, body, authenticated = true)

            val responseJson = JsonObject().apply {
                addProperty("request_id", requestId)
                add("result", gson.toJsonTree(response.first))
                addProperty("status", response.second)
            }
            ws.send(responseJson.toString())
        } catch (e: Exception) {
            logMessageFailure(e)
            try {
                val json = JsonParser.parseString(text).asJsonObject
                val requestId = json.get("request_id")?.asString ?: ""
                val errorResponse = JsonObject().apply {
                    addProperty("request_id", requestId)
                    // F26: the shared contract, not `e.message`. This catch is the
                    // one 500 `dispatch` does not own — it wraps the JSON parse, the
                    // request-id check and the /mic_file stream — and it answered
                    // with the exception's message while BridgeRouter answered the
                    // identical failure with errorBody(). A message can quote a
                    // recipient, a file path or a host:port; a class name says
                    // enough to diagnose and leaks nothing (L1).
                    add("result", gson.toJsonTree(CommandDispatcher.errorBody(e)))
                    addProperty("status", 500)
                }
                ws.send(errorResponse.toString())
            } catch (_: Exception) {}
        }
    }

    private suspend fun streamMicrophoneRecording(
        ws: WebSocket,
        requestId: String,
        requestedName: String?,
    ) {
        val file = MicrophoneRecordingFiles.resolve(
            BridgeApplication.instance,
            requestedName,
        )
        if (file == null) {
            sendCommandResult(
                ws,
                requestId,
                mapOf("error" to "Recording not found"),
                status = 404,
            )
            return
        }

        val startMessage = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("status", 200)
            add("stream", JsonObject().apply {
                addProperty("event", "start")
                addProperty("filename", file.name)
                addProperty("mimeType", "audio/wav")
                addProperty("size", file.length())
            })
        }
        if (!ws.send(startMessage.toString())) return

        val digest = MessageDigest.getInstance("SHA-256")
        var bytesSent = 0L
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    while (ws.queueSize() > 1024L * 1024L) {
                        delay(10L)
                    }
                    digest.update(buffer, 0, read)
                    if (!ws.send(buildStreamFrame(requestId, buffer, read))) {
                        throw IllegalStateException("WebSocket rejected microphone stream data")
                    }
                    bytesSent += read
                }
            }

            val endMessage = JsonObject().apply {
                addProperty("request_id", requestId)
                addProperty("status", 200)
                add("stream", JsonObject().apply {
                    addProperty("event", "end")
                    addProperty("bytes", bytesSent)
                    addProperty(
                        "sha256",
                        digest.digest().joinToString("") { byte ->
                            "%02x".format(byte.toInt() and 0xff)
                        },
                    )
                })
            }
            ws.send(endMessage.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val errorMessage = JsonObject().apply {
                addProperty("request_id", requestId)
                addProperty("status", 500)
                add("stream", JsonObject().apply {
                    addProperty("event", "error")
                    addProperty("message", "Microphone stream failed (${error.javaClass.simpleName})")
                })
            }
            ws.send(errorMessage.toString())
        }
    }

    /** Internal (test seam): pure binary frame builder, unit-tested directly. */
    internal fun buildStreamFrame(
        requestId: String,
        payload: ByteArray,
        payloadLength: Int,
    ): okio.ByteString {
        val requestIdBytes = requestId.toByteArray(Charsets.UTF_8)
        require(requestIdBytes.size in 1..128) { "request_id is too long" }
        require(payloadLength in 0..payload.size) { "Invalid payload length" }

        val frame = ByteArray(2 + requestIdBytes.size + payloadLength)
        frame[0] = ((requestIdBytes.size ushr 8) and 0xff).toByte()
        frame[1] = (requestIdBytes.size and 0xff).toByte()
        requestIdBytes.copyInto(frame, destinationOffset = 2)
        payload.copyInto(
            frame,
            destinationOffset = 2 + requestIdBytes.size,
            endIndex = payloadLength,
        )
        return frame.toByteString()
    }

    private fun sendCommandResult(
        ws: WebSocket,
        requestId: String,
        result: Any,
        status: Int,
    ) {
        val response = JsonObject().apply {
            addProperty("request_id", requestId)
            add("result", gson.toJsonTree(result))
            addProperty("status", status)
        }
        ws.send(response.toString())
    }

    /**
     * How a message-handling failure is logged (review F35).
     *
     * The class name, and no throwable. `Log.e(tag, msg, tr)` appends
     * `Log.getStackTraceString(tr)`, whose first line is `class: message`, so passing
     * the throwable would put the message back into logcat however the text is
     * worded — and the message is the problem, not the interpolation: this catch
     * wraps the JSON parse, the `request_id` check and the `/mic_file` stream, and a
     * `JsonSyntaxException` from a malformed command can quote the command, which for
     * `/send_sms` contains the recipient. AGENTS.md keeps phone numbers, recipients
     * and location out of logs as well as out of responses, and logcat is not
     * private: a bug report captures it, and READ_LOGS is readable by any app on an
     * older device.
     *
     * The cost is the stack trace. The class name is exactly what `errorBody` already
     * hands the caller (F26), it is enough to locate the throw, and a recipient in
     * logcat is not a price worth paying for line numbers.
     *
     * Internal (test seam): `RelayClientLogRedactionTest` reads the line back through
     * Robolectric's `ShadowLog`, including the absence of an attached throwable.
     */
    internal fun logMessageFailure(cause: Throwable) {
        Log.e(
            TAG,
            "Error handling message: ${CommandDispatcher.errorBody(cause).getValue("error")}",
        )
    }

    private fun notifyStatus(connected: Boolean, message: String) {
        val callback = onStatusChanged ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(connected, message)
            }
        } catch (_: Exception) {}
    }
}
