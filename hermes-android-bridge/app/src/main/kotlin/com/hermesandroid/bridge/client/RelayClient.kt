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
import com.hermesandroid.bridge.media.NoiseVideoFiles
import com.hermesandroid.bridge.server.CommandDispatcher
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    const val ROBOT_EVENT_TALK_REQUESTED = "robot.talk_requested"
    const val ROBOT_EVENT_SESSION_STOP = "robot.session_stop"
    const val ROBOT_EVENT_BACKEND_LOCAL = "robot.backend_local"
    const val ROBOT_EVENT_BACKEND_OPENAI = "robot.backend_openai"
    const val ROBOT_EVENT_BACKEND_GPT_LIVE = "robot.backend_gpt_live"
    const val ROBOT_EVENT_BACKEND_HERMES_LOCAL = "robot.backend_hermes_local"
    const val ROBOT_EVENT_BACKEND_HERMES_STANDARD = "robot.backend_hermes_standard"
    private const val ROBOT_EVENT_REALTIME_TRANSCRIPT = "robot.realtime_transcript"

    private const val TAG = "RelayClient"
    private const val PREFS_NAME = "hermes_bridge_prefs"
    private const val KEY_SERVER_URL = "relay_server_url"
    private const val KEY_PAIRING_CODE = "relay_pairing_code"
    private const val MAX_BACKOFF_MS = 30_000L
    private const val MAX_RETRIES = 5

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(20))
        .build()

    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null
    private var prefs: SharedPreferences? = null
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
            Log.i(TAG, "Auto-connecting to $url")
            connect(url, code)
        }
    }

    /**
     * Send a strictly allow-listed, content-free event from the robot UI to the Mac.
     * Speech and screen text never travel in this event channel.
     */
    fun sendRobotEvent(event: String): Boolean {
        if (event !in ROBOT_EVENTS) {
            return false
        }
        if (!isConnected) return false
        val message = JsonObject().apply {
            addProperty("event", event)
            addProperty("protocol", 1)
        }
        return webSocket?.send(message.toString()) == true
    }

    /** Send one bounded transcript event from the local Realtime WebView.
     *  Raw audio and OpenAI credentials never enter this relay message. */
    fun sendRealtimeTranscript(role: String, text: String, itemId: String): Boolean {
        val cleaned = text.trim()
        if (role !in setOf("user", "assistant")) return false
        if (cleaned.isBlank() || cleaned.length > 4_000 || '\u0000' in cleaned) return false
        if (!itemId.matches(Regex("[A-Za-z0-9_.:-]{1,160}"))) return false
        if (!isConnected) return false
        val message = JsonObject().apply {
            addProperty("event", ROBOT_EVENT_REALTIME_TRANSCRIPT)
            addProperty("protocol", 1)
            addProperty("role", role)
            addProperty("text", cleaned)
            addProperty("itemId", itemId)
        }
        return webSocket?.send(message.toString()) == true
    }

    /** Exchange an SDP offer through the trusted Mac relay.
     *  The relay authenticates with OpenAI; the standard API key never reaches Android. */
    fun exchangeRealtimeSdp(
        offer: String,
        modelTier: String,
        callback: (Result<String>) -> Unit,
    ) {
        val configuredUrl = serverUrl
        val token = pairingCode
        if (configuredUrl.isNullOrBlank() || token.isNullOrBlank()) {
            callback(Result.failure(IllegalStateException("Relay is not configured")))
            return
        }
        if (!isConnected) {
            callback(Result.failure(IllegalStateException("Relay is not connected")))
            return
        }
        if (!offer.startsWith("v=0") || offer.length > 64 * 1024 || '\u0000' in offer) {
            callback(Result.failure(IllegalArgumentException("Invalid SDP offer")))
            return
        }
        if (modelTier !in REALTIME_MODEL_TIERS) {
            callback(Result.failure(IllegalArgumentException("Invalid GPT Live model tier")))
            return
        }
        val request = Request.Builder()
            .url("${buildHttpBaseUrl(configuredUrl)}/robot/realtime/session")
            .header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-store")
            .header(REALTIME_MODEL_TIER_HEADER, modelTier)
            .post(offer.toRequestBody("application/sdp".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                postResult(
                    callback,
                    Result.failure(IllegalStateException("Der GPT-Live-Relay ist nicht erreichbar.")),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val message = when (response.code) {
                            400 -> "Die gewählte GPT-Live-Stufe wurde abgelehnt."
                            401, 403 -> "Die GPT-Live-Anmeldung auf dem Mac ist fehlgeschlagen."
                            429 -> "GPT Live ist gerade ausgelastet oder das Kontingent ist erreicht."
                            503 -> "GPT Live ist auf dem Mac nicht eingerichtet."
                            else -> "OpenAI konnte die GPT-Live-Sitzung nicht starten."
                        }
                        postResult(
                            callback,
                            Result.failure(IllegalStateException(message)),
                        )
                        return
                    }
                    val answer = response.body?.string().orEmpty().trim()
                    if (!answer.startsWith("v=0") || answer.length > 64 * 1024) {
                        postResult(
                            callback,
                            Result.failure(
                                IllegalStateException("GPT Live hat eine ungültige Verbindung geliefert."),
                            ),
                        )
                        return
                    }
                    postResult(callback, Result.success(answer))
                }
            }
        })
    }

    private val ROBOT_EVENTS = setOf(
        ROBOT_EVENT_TALK_REQUESTED,
        ROBOT_EVENT_SESSION_STOP,
        ROBOT_EVENT_BACKEND_LOCAL,
        ROBOT_EVENT_BACKEND_OPENAI,
        ROBOT_EVENT_BACKEND_GPT_LIVE,
        ROBOT_EVENT_BACKEND_HERMES_LOCAL,
        ROBOT_EVENT_BACKEND_HERMES_STANDARD,
    )

    private const val REALTIME_MODEL_TIER_HEADER = "X-Cradata-Realtime-Tier"
    private val REALTIME_MODEL_TIERS = setOf("mini", "standard", "top")

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
                if (myGeneration != generation) return
                val httpCode = response?.code ?: 0
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
            return
        }

        reconnectPending = true

        val backoff = reconnectPolicy.nextBackoffMs()
        val attempt = reconnectPolicy.attempts

        reconnectJob = activeScope.launch {
            Log.i(TAG, "Reconnecting in ${backoff}ms... (attempt $attempt/${reconnectPolicy.limit})")
            notifyStatus(false, "Reconnecting in ${backoff / 1000}s... (attempt $attempt/${reconnectPolicy.limit})")
            delay(backoff)
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

    private fun buildHttpBaseUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        val useTls = trimmed.startsWith("https://") || trimmed.startsWith("wss://")
        var base = trimmed
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("ws://").removePrefix("wss://")
        if (!base.contains(":")) {
            base = "$base:8766"
        }
        return "${if (useTls) "https" else "http"}://$base"
    }

    private fun <T> postResult(callback: (Result<T>) -> Unit, result: Result<T>) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            callback(result)
        }
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
            if (method == "GET" && path in setOf("/mic_file", "/noise_video_file")) {
                streamRecording(
                    ws,
                    requestId,
                    path,
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

    private suspend fun streamRecording(
        ws: WebSocket,
        requestId: String,
        path: String,
        requestedName: String?,
    ) {
        val isVideo = path == "/noise_video_file"
        val file = if (isVideo) {
            NoiseVideoFiles.resolve(BridgeApplication.instance, requestedName)
        } else {
            MicrophoneRecordingFiles.resolve(BridgeApplication.instance, requestedName)
        }
        if (file == null) {
            sendCommandResult(
                ws,
                requestId,
                mapOf("error" to if (isVideo) "Noise video not found" else "Recording not found"),
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
                addProperty("mimeType", if (isVideo) "video/mp4" else "audio/wav")
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
                        throw IllegalStateException("WebSocket rejected recording stream data")
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
                    addProperty("message", "Recording stream failed (${error.javaClass.simpleName})")
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
