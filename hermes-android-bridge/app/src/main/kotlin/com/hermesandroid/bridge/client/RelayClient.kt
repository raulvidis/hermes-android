package com.hermesandroid.bridge.client

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
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
    private const val KEY_PAIRING_CODE = "relay_pairing_code"
    private const val MAX_BACKOFF_MS = 30_000L
    private const val MAX_RETRIES = 5

    // --- Termux revival watchdog ---
    // When the relay is unreachable past these thresholds, fire the
    // Termux RUN_COMMAND intent to restart the whole stack (start-zee.sh).
    const val WATCHDOG_FAILURES_TO_TRIGGER = 3
    private const val WATCHDOG_COOLDOWN_MS = 600_000L  // 10 min
    // With revival enabled, once a full retry cycle is exhausted we do NOT give
    // up: we wait this long, then start a fresh cycle. Each exhausted cycle
    // counts as one watchdog failure, so the trigger threshold is actually
    // reachable on an unattended device.
    private const val WATCHDOG_RECONNECT_INTERVAL_MS = 60_000L  // 60s
    private val revivalPolicy = RevivalPolicy(WATCHDOG_FAILURES_TO_TRIGGER, WATCHDOG_COOLDOWN_MS)
    private const val KEY_FAILURES = "watchdog_failures"
    private const val KEY_LAST_FIRE = "watchdog_last_fire_ms"
    private const val KEY_REVIVAL_ENABLED = "termux_revival_enabled"

    /** Accurate guidance shown when the Termux revival intent is rejected. Both
     *  steps are required: the app-info permission AND allow-external-apps. */
    const val REVIVAL_PERMISSION_HELP =
        "Revival blocked: grant \"Run commands in Termux environment\" to Hermes " +
            "Bridge in App info, and set allow-external-apps=true in " +
            "~/.termux/termux.properties"

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

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var shouldReconnect: Boolean = false

    var serverUrl: String?
        get() = prefs?.getString(KEY_SERVER_URL, null)
        set(value) { prefs?.edit()?.putString(KEY_SERVER_URL, value)?.apply() }

    var pairingCode: String?
        get() = prefs?.getString(KEY_PAIRING_CODE, null)
        set(value) { prefs?.edit()?.putString(KEY_PAIRING_CODE, value)?.apply() }

    /** Callback for UI updates. Called on main thread. */
    var onStatusChanged: ((connected: Boolean, message: String) -> Unit)? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        appContext = context.applicationContext
    }

    // Shares the monitor with scheduleReconnect/beginReconnectAttempt: these run
    // on the main thread while callbacks schedule reconnects on OkHttp threads,
    // and an interleaving there can strand reconnectPending set with no
    // coroutine left to clear it, killing auto-reconnect for the process.
    @Synchronized
    fun connect(serverUrl: String, pairingCode: String) {
        disconnect()

        this.serverUrl = serverUrl
        this.pairingCode = pairingCode
        shouldReconnect = true
        reconnectPolicy.reset()

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        doConnect(serverUrl, pairingCode)
    }

    @Synchronized
    fun disconnect() {
        shouldReconnect = false
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
            if (BuildConfig.DEBUG) Log.i(TAG, "Auto-connecting to $url")
            connect(url, code)
        }
    }

    private fun doConnect(serverUrl: String, pairingCode: String) {
        val myGeneration = ++generation
        val wsUrl = buildWsUrl(serverUrl)
        if (BuildConfig.DEBUG) Log.i(TAG, "Connecting to $wsUrl")
        notifyStatus(false, "Connecting to $wsUrl ...")

        // Warn (once per connect) when the token would travel over an
        // unencrypted socket to a NON-private host — LAN/dev over ws:// is fine,
        // but ws:// to a public host leaks the Bearer token in cleartext.
        if (classifyConnectionSecurity(wsUrl) == ConnectionSecurity.CLEARTEXT_PUBLIC) {
            notifyStatus(false, "Warning: unencrypted connection — use wss://")
        }

        // Token goes in the Authorization header, not the URL — query strings
        // end up verbatim in reverse-proxy access logs.
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $pairingCode")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                handleOpen(webSocket, myGeneration, serverUrl)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Drop messages from a retired socket: a late frame from a
                // superseded generation must not be dispatched as a command.
                if (myGeneration != generation) return
                scope?.launch {
                    if (myGeneration != generation) return@launch
                    handleMessage(webSocket, text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (BuildConfig.DEBUG) Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleClosed(myGeneration, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleFailure(myGeneration, t, response)
            }
        })
    }

    // The generation check and the state it guards (isConnected, session clock,
    // foreground promotion) must be one atomic step under the same monitor as
    // disconnect()/scheduleReconnect(): otherwise a late onOpen from a retired
    // socket can flip isConnected back to true AFTER disconnect() ran, leaving
    // the service "connected" with no socket and the foreground stuck on.
    @Synchronized
    private fun handleOpen(sock: WebSocket, myGeneration: Int, serverUrl: String) {
        if (myGeneration != generation) {
            sock.cancel()
            return
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "WebSocket connected to ${buildWsUrl(serverUrl)}")
        isConnected = true
        // success resets the revival watchdog
        prefs?.edit()?.putInt(KEY_FAILURES, 0)?.apply()
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

    @Synchronized
    private fun handleClosed(myGeneration: Int, code: Int, reason: String) {
        if (myGeneration != generation) return
        if (BuildConfig.DEBUG) Log.i(TAG, "WebSocket closed: $code $reason")
        isConnected = false
        endSession()
        notifyStatus(false, "Closed: code=$code $reason")
        scheduleReconnect()
    }

    @Synchronized
    private fun handleFailure(myGeneration: Int, t: Throwable, response: Response?) {
        if (myGeneration != generation) return
        val httpCode = response?.code ?: 0
        val errorDetail = "Error: ${t.javaClass.simpleName}: ${t.message} (HTTP $httpCode)"
        Log.e(TAG, "WebSocket failure: $errorDetail", t)
        isConnected = false
        endSession()
        notifyStatus(false, errorDetail)
        scheduleReconnect()
    }

    // Invoked from OkHttp callback threads; the policy and the pending flag are
    // read-modify-written together, so serialize the whole decision.
    @Synchronized
    private fun scheduleReconnect() {
        val url = serverUrl ?: return
        val code = pairingCode ?: return
        // Resolve the scope BEFORE burning budget or raising reconnectPending:
        // a late callback after disconnect() finds a null scope, and committing
        // that state with no coroutine to clear it would stall reconnects for good.
        val activeScope = scope
        when (decideReconnect(
            shouldReconnect = shouldReconnect,
            hasScope = activeScope != null,
            reconnectPending = reconnectPending,
            isExhausted = reconnectPolicy.isExhausted,
        )) {
            // Not reconnecting, or another callback already scheduled the attempt.
            ReconnectAction.IGNORE, ReconnectAction.SKIP_PENDING -> return

            // The retry budget for this cycle is spent.
            ReconnectAction.EXHAUSTED -> onCycleExhausted(activeScope!!, url, code)

            ReconnectAction.ATTEMPT -> {
                reconnectPending = true
                val backoff = reconnectPolicy.nextBackoffMs()
                val attempt = reconnectPolicy.attempts
                reconnectJob = activeScope!!.launch {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "Reconnecting in ${backoff}ms... (attempt $attempt/${reconnectPolicy.limit})")
                    }
                    notifyStatus(false, "Reconnecting in ${backoff / 1000}s... (attempt $attempt/${reconnectPolicy.limit})")
                    delay(backoff)
                    beginReconnectAttempt(url, code)
                }
            }
        }
    }

    /**
     * A full retry cycle is spent. Behaviour depends on the revival opt-in:
     *
     * - Revival OFF: give up exactly as before — stop reconnecting and show the
     *   terminal status.
     * - Revival ON: never give up on an unattended device. Count this exhausted
     *   cycle as one watchdog failure (which may fire the Termux restart once
     *   the threshold is crossed, subject to cooldown), then wait
     *   [WATCHDOG_RECONNECT_INTERVAL_MS] and start a fresh cycle. The loop is
     *   cancellable by [disconnect].
     */
    @Synchronized
    private fun onCycleExhausted(activeScope: CoroutineScope, url: String, code: String) {
        // Retire the dead socket's listener: a late callback from it would
        // otherwise overwrite status or re-enter this path. Drop the session
        // clock too so the NEXT session is measured from its own start.
        generation++
        sessionStartedNs = 0L
        webSocket?.cancel()
        webSocket = null

        val revivalEnabled = prefs?.getBoolean(KEY_REVIVAL_ENABLED, false) ?: false
        if (!revivalEnabled) {
            shouldReconnect = false
            reconnectPending = false
            notifyStatus(false, "Failed to connect after ${reconnectPolicy.limit} attempts. Tap Connect to retry.")
            // No-op while disabled (RevivalPolicy gate returns Wait), preserving
            // the previous give-up behaviour exactly.
            maybeFireTermuxRevival()
            return
        }

        // Each exhausted cycle is one watchdog failure; may fire the restart.
        maybeFireTermuxRevival()

        reconnectPending = true
        reconnectJob = activeScope.launch {
            notifyStatus(
                false,
                "Stack unreachable — retrying in ${WATCHDOG_RECONNECT_INTERVAL_MS / 1000}s…",
            )
            delay(WATCHDOG_RECONNECT_INTERVAL_MS)
            beginWatchdogReconnect(url, code)
        }
    }

    /**
     * Start a fresh retry cycle for the revival watchdog loop. Resets the retry
     * budget so a new round of backed-off attempts runs; if that round also
     * exhausts, [onCycleExhausted] counts another watchdog failure. Re-checks
     * state under the lock so a [disconnect] that raced the delay wins.
     */
    @Synchronized
    private fun beginWatchdogReconnect(url: String, code: String) {
        reconnectPending = false
        if (!shouldReconnect || isConnected || scope == null) return
        reconnectPolicy.reset()
        generation++
        sessionStartedNs = 0L
        webSocket?.cancel()
        doConnect(url, code)
    }

    /**
     * Clearing [reconnectPending] and starting the attempt must happen as one
     * step under the same monitor as [scheduleReconnect]; clearing it earlier
     * lets a callback slip through and launch a second reconnect coroutine.
     */
    @Synchronized
    private fun beginReconnectAttempt(url: String, code: String) {
        reconnectPending = false
        if (!shouldReconnect || isConnected || scope == null) return
        // Supersede the old listener BEFORE cancelling its socket: cancel()
        // drives that listener's onFailure, and if it still matched the current
        // generation it would re-enter scheduleReconnect and burn an attempt on
        // our own teardown.
        generation++
        // Retired listener => no endSession() for it; drop the clock so the
        // next session is measured from its own start, not this one's.
        sessionStartedNs = 0L
        // Cancel the previous WebSocket before opening a new one, otherwise its
        // listener stays active and can fire out-of-order callbacks
        // (onOpen/onFailure) that set isConnected or push duplicate statuses.
        webSocket?.cancel()
        doConnect(url, code)
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
     * the whole stack via start-zee.sh. Cooldown prevents loops.
     */
    private fun maybeFireTermuxRevival() {
        val p = prefs ?: return
        val enabled = p.getBoolean(KEY_REVIVAL_ENABLED, false)
        val failures = p.getInt(KEY_FAILURES, 0)
        val lastFire = p.getLong(KEY_LAST_FIRE, 0L)
        val now = System.currentTimeMillis()
        when (val d = revivalPolicy.onFailure(enabled, failures, lastFire, now)) {
            is RevivalPolicy.Decision.Wait -> {
                p.edit().putInt(KEY_FAILURES, d.failures).apply()
                if (d.failures > failures) {
                    Log.i(TAG, "Revival watchdog: ${d.failures}/$WATCHDOG_FAILURES_TO_TRIGGER failures")
                }
            }
            is RevivalPolicy.Decision.Fire -> {
                p.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LAST_FIRE, now).apply()
                fireTermuxRestart()
            }
        }
    }

    private fun fireTermuxRestart() {
        val ctx = appContext ?: return
        try {
            val intent = android.content.Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/home/.termux/boot/start-zee.sh")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                // Termux expects the session action as a String, not an int.
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            // RunCommandService is a foreground service: on API 26+ it MUST be
            // started with startForegroundService or Android throws
            // IllegalStateException from the background (Termux docs).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            Log.i(TAG, "Termux revival: RUN_COMMAND fired (start-zee.sh)")
            notifyStatus(false, "Stack unreachable — firing Termux revival…")
        } catch (e: SecurityException) {
            Log.e(TAG, "Termux revival BLOCKED: RUN_COMMAND permission missing", e)
            notifyStatus(false, REVIVAL_PERMISSION_HELP)
        } catch (e: Exception) {
            Log.e(TAG, "Termux revival intent failed", e)
            notifyStatus(false, "Revival failed: ${e.message?.take(80) ?: "unknown error"}")
        }
        // NOTE: reconnection is NOT scheduled here. fireTermuxRestart only runs
        // from onCycleExhausted's revival-enabled branch, which ALWAYS schedules
        // the slow watchdog reconnect loop right after this returns — on the
        // success path AND on either failure path. So the relay never stays dark
        // regardless of how the intent fared, and we avoid double-scheduling.
    }

    private fun buildWsUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        val useTls = trimmed.startsWith("https://") || trimmed.startsWith("wss://")
        var base = trimmed
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("ws://").removePrefix("wss://")
        if (!base.contains(":")) {
            base = "$base:8766"
        }
        val scheme = if (useTls) "wss" else "ws"
        if (BuildConfig.DEBUG) Log.d(TAG, "Built WebSocket URL: $scheme://$base/ws")
        return "$scheme://$base/ws"
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
            Log.e(TAG, "Error handling message: ${e.message}", e)
            try {
                val json = JsonParser.parseString(text).asJsonObject
                val requestId = json.get("request_id")?.asString ?: ""
                val errorResponse = JsonObject().apply {
                    addProperty("request_id", requestId)
                    add("result", gson.toJsonTree(mapOf("error" to e.message)))
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

    private fun buildStreamFrame(
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

    private fun notifyStatus(connected: Boolean, message: String) {
        val callback = onStatusChanged ?: return
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(connected, message)
            }
        } catch (_: Exception) {}
    }
}

/** Security posture of a resolved WebSocket URL, for the cleartext warning. */
enum class ConnectionSecurity {
    /** wss:// — encrypted. */
    SECURE,

    /** ws:// to a private/loopback/link-local host — LAN/dev, no warning. */
    CLEARTEXT_PRIVATE,

    /** ws:// to a public host — the Bearer token would travel in cleartext. */
    CLEARTEXT_PUBLIC,
}

/**
 * Classify a resolved ws(s):// URL. Pure (no Android) so it is unit-testable.
 *
 * Only ws:// to a NON-private host is flagged: LAN, loopback, link-local,
 * `.local`, and Tailscale (100.64/10) hosts are treated as private so we do not
 * nag dev/LAN users, while a bare public host over ws:// (token in cleartext)
 * earns a warning.
 */
fun classifyConnectionSecurity(resolvedUrl: String): ConnectionSecurity {
    val lower = resolvedUrl.trim().lowercase()
    if (lower.startsWith("wss://")) return ConnectionSecurity.SECURE
    if (!lower.startsWith("ws://")) return ConnectionSecurity.SECURE
    var host = lower.removePrefix("ws://").substringBefore("/")
    // Drop any userinfo (user:pass@host).
    host = host.substringAfterLast("@")
    host = if (host.startsWith("[")) {
        // IPv6 literal: [::1]:8766 -> ::1
        host.substringAfter("[").substringBefore("]")
    } else {
        // hostname or IPv4: strip the port.
        host.substringBefore(":")
    }
    return if (isPrivateHost(host)) {
        ConnectionSecurity.CLEARTEXT_PRIVATE
    } else {
        ConnectionSecurity.CLEARTEXT_PUBLIC
    }
}

/** True for loopback, RFC1918, link-local, CGNAT/Tailscale, localhost, .local. */
private fun isPrivateHost(host: String): Boolean {
    if (host.isBlank()) return true // nothing to warn about
    if (host == "localhost" || host.endsWith(".local")) return true
    if (host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return true

    val octets = host.split(".")
    if (octets.size != 4) return false
    val nums = octets.map { it.toIntOrNull() ?: return false }
    if (nums.any { it !in 0..255 }) return false
    val (a, b) = nums[0] to nums[1]
    return when {
        a == 10 -> true                       // 10.0.0.0/8
        a == 127 -> true                      // 127.0.0.0/8 loopback
        a == 172 && b in 16..31 -> true       // 172.16.0.0/12
        a == 192 && b == 168 -> true          // 192.168.0.0/16
        a == 169 && b == 254 -> true          // 169.254.0.0/16 link-local
        a == 100 && b in 64..127 -> true      // 100.64.0.0/10 CGNAT / Tailscale
        else -> false
    }
}
