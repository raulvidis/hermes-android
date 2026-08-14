package com.hermesandroid.bridge.robot

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hermesandroid.bridge.client.RelayClient
import org.json.JSONObject

/**
 * Hosts a tiny local WebRTC page inside Android System WebView.
 *
 * The page can use the Pixel microphone and play the remote OpenAI audio track,
 * but it cannot navigate or access files. SDP is exchanged by native code via
 * the authenticated Mac relay, so no standard API key is present on Android.
 */
internal class RobotRealtimeController(
    private val activity: Activity,
    private val webView: WebView,
    private val onState: (RobotPhase, String) -> Unit,
) {
    private val trustedOrigin = Uri.parse(TRUSTED_BASE_URL)

    @Volatile
    private var ready = false

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    private var selectedTier = RobotRealtimeTier.TOP.wireName

    @SuppressLint("SetJavaScriptEnabled")
    fun initialize() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = true
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val trusted = request.origin.scheme == trustedOrigin.scheme &&
                    request.origin.host == trustedOrigin.host
                val microphoneOnly = request.resources.toSet() ==
                    setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                activity.runOnUiThread {
                    if (trusted && microphoneOnly) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        request.deny()
                    }
                }
            }
        }
        webView.addJavascriptInterface(NativeBridge(), "AndroidRealtime")
        val html = activity.assets.open("robot_realtime.html")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        webView.loadDataWithBaseURL(
            TRUSTED_BASE_URL,
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    fun start(tier: String) {
        if (!ready) {
            onState(RobotPhase.ERROR, "GPT Live wird noch vorbereitet. Versuch es gleich noch einmal.")
            return
        }
        selectedTier = RobotRealtimeTier.entries
            .firstOrNull { it.wireName == tier }
            ?.wireName
            ?: RobotRealtimeTier.TOP.wireName
        active = true
        webView.evaluateJavascript("window.CradataRealtime.start()", null)
    }

    fun stop() {
        if (!active) return
        active = false
        webView.evaluateJavascript("window.CradataRealtime.stop()", null)
    }

    fun destroy() {
        stop()
        webView.removeJavascriptInterface("AndroidRealtime")
        webView.stopLoading()
        webView.destroy()
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun onReady() {
            ready = true
        }

        @JavascriptInterface
        fun exchangeSdp(offer: String) {
            RelayClient.exchangeRealtimeSdp(offer, selectedTier) { result ->
                activity.runOnUiThread {
                    result.fold(
                        onSuccess = { answer ->
                            webView.evaluateJavascript(
                                "window.CradataRealtime.onSdpAnswer(${JSONObject.quote(answer)})",
                                null,
                            )
                        },
                        onFailure = { error ->
                            active = false
                            webView.evaluateJavascript(
                                "window.CradataRealtime.onSdpError(" +
                                    "${JSONObject.quote(error.message.orEmpty().take(240))})",
                                null,
                            )
                        },
                    )
                }
            }
        }

        @JavascriptInterface
        fun onStatus(phase: String, caption: String) {
            val parsed = RobotPhase.fromWire(phase) ?: return
            if (parsed == RobotPhase.ERROR || parsed == RobotPhase.READY) {
                active = false
            }
            activity.runOnUiThread {
                onState(parsed, caption.take(600))
            }
        }

        @JavascriptInterface
        fun onTranscript(role: String, text: String, itemId: String) {
            if (RelayClient.sendRealtimeTranscript(role, text, itemId)) return
            active = false
            activity.runOnUiThread {
                webView.evaluateJavascript("window.CradataRealtime.stop(false)", null)
                onState(
                    RobotPhase.ERROR,
                    "Die Hermes-Gedächtnisverbindung ist unterbrochen. GPT Live wurde beendet.",
                )
            }
        }
    }

    private companion object {
        const val TRUSTED_BASE_URL = "https://cradata.invalid/"
    }
}
