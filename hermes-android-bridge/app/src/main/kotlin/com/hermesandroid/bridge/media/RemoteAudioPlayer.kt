package com.hermesandroid.bridge.media

import android.media.MediaPlayer
import android.util.Base64
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import java.io.File

/** Plays audio generated remotely (ElevenLabs on the Hermes server). */
object RemoteAudioPlayer {
    @Volatile private var player: MediaPlayer? = null

    fun play(base64Audio: String, extension: String = "mp3"): Map<String, Any?> {
        val service = BridgeAccessibilityService.instance
            ?: return mapOf("success" to false, "message" to "Accessibility service not running")
        return try {
            stop()
            val bytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val file = File(service.cacheDir, "remote_tts_${System.currentTimeMillis()}.$extension")
            file.writeBytes(bytes)
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    try { it.release() } catch (_: Exception) {}
                    file.delete()
                    if (player === it) player = null
                }
                prepare()
                start()
            }
            player = mp
            mapOf("success" to true, "message" to "Playing remote audio", "sizeBytes" to bytes.size)
        } catch (e: Exception) {
            mapOf("success" to false, "message" to "Remote audio playback failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stop(): Map<String, Any?> {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        return mapOf("success" to true, "message" to "Stopped remote audio")
    }
}
