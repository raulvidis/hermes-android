package com.hermesandroid.bridge.robot

import android.content.Context
import android.content.Intent
import java.util.concurrent.CopyOnWriteArraySet

internal enum class RobotPhase(val wireName: String) {
    IDLE("idle"),
    LISTENING("listening"),
    THINKING("thinking"),
    SPEAKING("speaking"),
    READY("ready"),
    ERROR("error");

    companion object {
        fun fromWire(value: String): RobotPhase? =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
    }
}

internal enum class RobotBackend(val wireName: String) {
    GPT_LIVE("gpt_live"),
    HERMES_LOCAL("hermes_local"),
    HERMES_STANDARD("hermes_standard");

    companion object {
        fun fromWire(value: String): RobotBackend? {
            val normalized = value.trim().lowercase()
            return when (normalized) {
                // Compatibility with the previous two-button companion.
                "local" -> HERMES_LOCAL
                "openai" -> GPT_LIVE
                else -> entries.firstOrNull { it.wireName == normalized }
            }
        }
    }
}

internal data class RobotScreenState(
    val phase: RobotPhase = RobotPhase.IDLE,
    val caption: String = DEFAULT_CAPTION,
    val backend: RobotBackend = RobotBackend.HERMES_LOCAL,
) {
    companion object {
        const val DEFAULT_CAPTION = "Hallo! Ich bin Cradata. Tippe auf den Knopf und sprich mit mir."
    }
}

/** In-memory UI state only. Conversation transcripts are never persisted on the phone. */
internal object RobotUiController {
    private const val MAX_CAPTION_LENGTH = 600
    private val listeners = CopyOnWriteArraySet<(RobotScreenState) -> Unit>()

    @Volatile
    private var currentState = RobotScreenState()

    @Volatile
    private var visible = false

    fun state(): RobotScreenState = currentState

    fun update(
        phase: RobotPhase,
        caption: String?,
        backend: RobotBackend? = null,
    ): RobotScreenState {
        val next = RobotScreenState(
            phase = phase,
            caption = caption
                ?.trim()
                ?.take(MAX_CAPTION_LENGTH)
                ?.ifBlank { defaultCaption(phase) }
                ?: defaultCaption(phase),
            backend = backend ?: currentState.backend,
        )
        currentState = next
        listeners.forEach { listener -> listener(next) }
        return next
    }

    fun reset(): RobotScreenState = update(RobotPhase.IDLE, RobotScreenState.DEFAULT_CAPTION)

    fun addListener(listener: (RobotScreenState) -> Unit) {
        listeners.add(listener)
        listener(currentState)
    }

    fun removeListener(listener: (RobotScreenState) -> Unit) {
        listeners.remove(listener)
    }

    fun markVisible(value: Boolean) {
        visible = value
    }

    fun isVisible(): Boolean = visible

    fun show(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(context, RobotActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
    }.isSuccess

    private fun defaultCaption(phase: RobotPhase): String = when (phase) {
        RobotPhase.IDLE -> RobotScreenState.DEFAULT_CAPTION
        RobotPhase.LISTENING -> "Ich höre dir zu …"
        RobotPhase.THINKING -> "Einen Moment, ich denke nach …"
        RobotPhase.SPEAKING -> "Cradata antwortet …"
        RobotPhase.READY -> "Möchtest du mir noch etwas erzählen?"
        RobotPhase.ERROR -> "Das hat gerade nicht geklappt. Wir können es noch einmal versuchen."
    }
}
