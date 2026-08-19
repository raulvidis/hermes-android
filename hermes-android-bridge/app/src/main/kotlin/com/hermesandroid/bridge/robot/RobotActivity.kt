package com.hermesandroid.bridge.robot

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hermesandroid.bridge.R
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.media.NoiseTriggeredVideoService
import com.hermesandroid.bridge.media.NoiseTriggerState

class RobotActivity : Activity() {
    private lateinit var face: RobotFaceView
    private lateinit var caption: TextView
    private lateinit var talkButton: Button
    private lateinit var gptLiveBackendButton: Button
    private lateinit var hermesLocalBackendButton: Button
    private lateinit var hermesStandardBackendButton: Button
    private lateinit var gptModelRow: LinearLayout
    private lateinit var gptMiniButton: Button
    private lateinit var gptStandardButton: Button
    private lateinit var gptTopButton: Button
    private lateinit var realtimeController: RobotRealtimeController
    private val voiceInputHandler = Handler(Looper.getMainLooper())
    private var selectedRealtimeTier = RobotRealtimeTier.TOP
    private var voiceInputReady = true
    private var resumeNoiseWatcherAfterDialog = false
    private var activityStarted = false
    private var voicePreparationPolls = 0

    private val voiceInputReadyPoll = object : Runnable {
        override fun run() {
            if (!activityStarted) return
            val noiseState = NoiseTriggerState.snapshot()
            if (!noiseState.active || noiseState.pausedForDialog) {
                voiceInputReady = true
                render(RobotUiController.state())
                return
            }
            voicePreparationPolls += 1
            if (voicePreparationPolls >= MAX_VOICE_PREPARATION_POLLS) {
                voiceInputReady = false
                RobotUiController.update(
                    RobotPhase.ERROR,
                    "Das Mikrofon konnte nicht vom Geräuschwächter übernommen werden.",
                )
                return
            }
            voiceInputHandler.postDelayed(this, VOICE_PREPARATION_POLL_MS)
        }
    }

    private val stateListener: (RobotScreenState) -> Unit = { state ->
        runOnUiThread { render(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_robot)

        face = findViewById(R.id.robotFace)
        caption = findViewById(R.id.tvRobotCaption)
        talkButton = findViewById(R.id.btnRobotTalk)
        gptLiveBackendButton = findViewById(R.id.btnRobotBackendGptLive)
        hermesLocalBackendButton = findViewById(R.id.btnRobotBackendHermesLocal)
        hermesStandardBackendButton = findViewById(R.id.btnRobotBackendHermesStandard)
        gptModelRow = findViewById(R.id.robotGptModelRow)
        gptMiniButton = findViewById(R.id.btnRobotGptMini)
        gptStandardButton = findViewById(R.id.btnRobotGptStandard)
        gptTopButton = findViewById(R.id.btnRobotGptTop)
        realtimeController = RobotRealtimeController(
            this,
            findViewById<WebView>(R.id.robotRealtimeWebView),
        ) { phase, text ->
            RobotUiController.update(phase, text, RobotBackend.GPT_LIVE)
        }.also { it.initialize() }

        gptLiveBackendButton.setOnClickListener {
            selectBackend(RobotBackend.GPT_LIVE, RelayClient.ROBOT_EVENT_BACKEND_GPT_LIVE)
        }
        hermesLocalBackendButton.setOnClickListener {
            selectBackend(
                RobotBackend.HERMES_LOCAL,
                RelayClient.ROBOT_EVENT_BACKEND_HERMES_LOCAL,
            )
        }
        hermesStandardBackendButton.setOnClickListener {
            selectBackend(
                RobotBackend.HERMES_STANDARD,
                RelayClient.ROBOT_EVENT_BACKEND_HERMES_STANDARD,
            )
        }
        gptMiniButton.setOnClickListener { selectRealtimeTier(RobotRealtimeTier.MINI) }
        gptStandardButton.setOnClickListener { selectRealtimeTier(RobotRealtimeTier.STANDARD) }
        gptTopButton.setOnClickListener { selectRealtimeTier(RobotRealtimeTier.TOP) }

        talkButton.setOnClickListener {
            if (!voiceInputReady) {
                RobotUiController.update(
                    RobotPhase.READY,
                    "Einen Moment – Cradata bereitet das Mikrofon vor …",
                )
                return@setOnClickListener
            }
            if (RobotUiController.state().backend == RobotBackend.GPT_LIVE) {
                if (realtimeController.active) {
                    realtimeController.stop()
                } else if (
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    realtimeController.start(selectedRealtimeTier.wireName)
                } else {
                    requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQUEST_RECORD_AUDIO,
                    )
                }
            } else {
                RobotUiController.update(RobotPhase.LISTENING, null)
                if (!RelayClient.sendRobotEvent(RelayClient.ROBOT_EVENT_TALK_REQUESTED)) {
                    RobotUiController.update(
                        RobotPhase.ERROR,
                        "Der Roboter ist gerade nicht mit seinem Gehirn verbunden.",
                    )
                }
            }
        }

        findViewById<Button>(R.id.btnRobotEnd).setOnClickListener {
            realtimeController.stop()
            RelayClient.sendRobotEvent(RelayClient.ROBOT_EVENT_SESSION_STOP)
            RobotUiController.reset()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        RobotUiController.markVisible(true)
        RobotUiController.addListener(stateListener)
        prepareVoiceInput()
    }

    override fun onStop() {
        // Never leave a live microphone running behind another activity.
        activityStarted = false
        voiceInputHandler.removeCallbacks(voiceInputReadyPoll)
        realtimeController.stop()
        if (resumeNoiseWatcherAfterDialog) {
            NoiseTriggeredVideoService.resumeAfterRobotDialog(this)
            resumeNoiseWatcherAfterDialog = false
        }
        RobotUiController.removeListener(stateListener)
        RobotUiController.markVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        realtimeController.destroy()
        super.onDestroy()
    }

    private fun render(state: RobotScreenState) {
        face.phase = state.phase
        caption.text = state.caption
        val busy = state.phase == RobotPhase.LISTENING ||
            state.phase == RobotPhase.THINKING ||
            state.phase == RobotPhase.SPEAKING
        val liveSession = state.backend == RobotBackend.GPT_LIVE && realtimeController.active
        talkButton.isEnabled = voiceInputReady && (liveSession || !busy)
        talkButton.alpha = if (talkButton.isEnabled) 1f else 0.55f
        renderBackendButton(gptLiveBackendButton, state.backend == RobotBackend.GPT_LIVE, busy)
        renderBackendButton(
            hermesLocalBackendButton,
            state.backend == RobotBackend.HERMES_LOCAL,
            busy,
        )
        gptModelRow.visibility = if (state.backend == RobotBackend.GPT_LIVE) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val modelSelectionBusy = busy || liveSession
        renderModelButton(
            gptMiniButton,
            selectedRealtimeTier == RobotRealtimeTier.MINI,
            modelSelectionBusy,
        )
        renderModelButton(
            gptStandardButton,
            selectedRealtimeTier == RobotRealtimeTier.STANDARD,
            modelSelectionBusy,
        )
        renderModelButton(
            gptTopButton,
            selectedRealtimeTier == RobotRealtimeTier.TOP,
            modelSelectionBusy,
        )
        renderBackendButton(
            hermesStandardBackendButton,
            state.backend == RobotBackend.HERMES_STANDARD,
            busy,
        )
        talkButton.text = when {
            liveSession -> "GPT Live beenden"
            state.backend == RobotBackend.GPT_LIVE -> "GPT Live starten"
            state.phase == RobotPhase.LISTENING -> "Ich höre zu …"
            state.phase == RobotPhase.THINKING -> "Ich denke …"
            state.phase == RobotPhase.SPEAKING -> "Cradata spricht …"
            else -> "Jetzt sprechen"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted && RobotUiController.state().backend == RobotBackend.GPT_LIVE) {
            realtimeController.start(selectedRealtimeTier.wireName)
        } else {
            RobotUiController.update(
                RobotPhase.ERROR,
                "Für GPT Live braucht Cradata die Mikrofonberechtigung.",
            )
        }
    }

    private fun selectBackend(backend: RobotBackend, event: String) {
        val previous = RobotUiController.state()
        if (previous.backend == RobotBackend.GPT_LIVE && backend != RobotBackend.GPT_LIVE) {
            realtimeController.stop()
        }
        val switchingCaption = when (backend) {
            RobotBackend.GPT_LIVE -> getString(R.string.robot_backend_switching_gpt_live)
            RobotBackend.HERMES_LOCAL -> getString(R.string.robot_backend_switching_hermes_local)
            RobotBackend.HERMES_STANDARD -> getString(R.string.robot_backend_switching_hermes_standard)
        }
        RobotUiController.update(RobotPhase.READY, switchingCaption, backend)
        if (!RelayClient.sendRobotEvent(event)) {
            RobotUiController.update(
                RobotPhase.ERROR,
                getString(R.string.robot_backend_unavailable),
                previous.backend,
            )
        }
    }

    private fun renderBackendButton(button: Button, selected: Boolean, busy: Boolean) {
        button.isEnabled = !busy
        button.setBackgroundResource(
            if (selected) R.drawable.bg_button_robot_primary else R.drawable.bg_button_robot_secondary,
        )
        button.setTextColor(Color.parseColor(if (selected) "#031B1C" else "#C9D9E4"))
        button.alpha = if (busy) 0.55f else if (selected) 1f else 0.84f
    }

    private fun renderModelButton(button: Button, selected: Boolean, busy: Boolean) {
        renderBackendButton(button, selected, busy)
    }

    private fun selectRealtimeTier(tier: RobotRealtimeTier) {
        if (realtimeController.active) return
        selectedRealtimeTier = tier
        val caption = when (tier) {
            RobotRealtimeTier.MINI -> "GPT Live Sparsam ist ausgewählt – schnell und günstig."
            RobotRealtimeTier.STANDARD -> "GPT Live Stark ist ausgewählt – ausgewogene Qualität."
            RobotRealtimeTier.TOP -> "GPT Live Top ist ausgewählt – höchste Sprachqualität."
        }
        RobotUiController.update(RobotPhase.READY, caption, RobotBackend.GPT_LIVE)
    }

    private fun prepareVoiceInput() {
        voiceInputHandler.removeCallbacks(voiceInputReadyPoll)
        val noiseState = NoiseTriggerState.snapshot()
        resumeNoiseWatcherAfterDialog = noiseState.active
        if (!noiseState.active || noiseState.pausedForDialog) {
            voiceInputReady = true
            render(RobotUiController.state())
            return
        }
        voiceInputReady = false
        voicePreparationPolls = 0
        RobotUiController.update(
            RobotPhase.READY,
            "Cradata pausiert kurz den Geräuschwächter für das Gespräch …",
        )
        NoiseTriggeredVideoService.pauseForRobotDialog(this)
        voiceInputHandler.post(voiceInputReadyPoll)
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 41
        const val VOICE_PREPARATION_POLL_MS = 100L
        const val MAX_VOICE_PREPARATION_POLLS = 50
    }
}
