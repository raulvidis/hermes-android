package com.hermesandroid.bridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.overlay.StatusOverlay
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import java.net.NetworkInterface

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_SCREEN_RECORD = 1001
    }

    private lateinit var tvA11yStatus: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvRelayAddr: TextView
    private lateinit var tvAuthCode: TextView
    private lateinit var indicatorA11y: View
    private lateinit var indicatorServer: View
    private lateinit var indicatorRelay: View
    private lateinit var indicatorAuth: View
    private lateinit var switchAccessibility: Switch
    private lateinit var switchOverlay: Switch
    private lateinit var switchScreenRecord: Switch
    private lateinit var tvPairingCode: TextView
    private lateinit var btnRegenerate: Button
    private lateinit var etServerUrl: EditText
    private lateinit var tvRelayStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvAddress: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvA11yStatus = findViewById(R.id.tvA11yStatus)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvRelayAddr = findViewById(R.id.tvRelayAddr)
        tvAuthCode = findViewById(R.id.tvAuthCode)
        indicatorA11y = findViewById(R.id.indicatorA11y)
        indicatorServer = findViewById(R.id.indicatorServer)
        indicatorRelay = findViewById(R.id.indicatorRelay)
        indicatorAuth = findViewById(R.id.indicatorAuth)
        switchAccessibility = findViewById(R.id.switchAccessibility)
        switchOverlay = findViewById(R.id.switchOverlay)
        switchScreenRecord = findViewById(R.id.switchScreenRecord)
        tvPairingCode = findViewById(R.id.tvPairingCode)
        btnRegenerate = findViewById(R.id.btnRegenerate)
        etServerUrl = findViewById(R.id.etServerUrl)
        tvRelayStatus = findViewById(R.id.tvRelayStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvAddress = findViewById(R.id.tvAddress)

        setupPairingCode()
        setupPermissions()
        setupRelayConnection()

        updateConnectionInfo()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updatePermissionSwitches()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_RECORD) {
            if (resultCode == RESULT_OK && data != null) {
                val service = BridgeAccessibilityService.instance
                if (service == null) {
                    Toast.makeText(this, "Enable Accessibility Service before screen recording", Toast.LENGTH_LONG).show()
                } else {
                    ScreenRecorder.setProjectionPermission(resultCode, data)
                    Toast.makeText(this, "Screen recording permission granted", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show()
            }
            updatePermissionSwitches()
        }
    }

    private fun setupPairingCode() {
        tvPairingCode.text = PairingManager.getCode()

        btnRegenerate.setOnClickListener {
            PairingManager.regenerateCode()
            tvPairingCode.text = PairingManager.getCode()
            updateStatus()
            Toast.makeText(this, "New pairing code generated", Toast.LENGTH_SHORT).show()
        }

        tvPairingCode.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Hermes pairing code", PairingManager.getCode()))
            Toast.makeText(this, "Pairing code copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPermissions() {
        switchAccessibility.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && BridgeAccessibilityService.instance == null) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } else {
                    StatusOverlay.show(this)
                }
            } else {
                StatusOverlay.hide(this)
            }
        }

        switchScreenRecord.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !ScreenRecorder.hasPermission()) {
                val service = BridgeAccessibilityService.instance
                if (service == null) {
                    Toast.makeText(this, "Enable Accessibility Service before screen recording", Toast.LENGTH_LONG).show()
                    updatePermissionSwitches()
                } else {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_RECORD)
                }
            }
        }
    }

    private fun updatePermissionSwitches() {
        switchAccessibility.setOnCheckedChangeListener(null)
        switchOverlay.setOnCheckedChangeListener(null)
        switchScreenRecord.setOnCheckedChangeListener(null)

        switchAccessibility.isChecked = BridgeAccessibilityService.instance != null
        switchOverlay.isChecked = Settings.canDrawOverlays(this)
        switchScreenRecord.isChecked = ScreenRecorder.hasPermission()

        setupPermissions()
    }

    private fun setupRelayConnection() {
        val savedUrl = RelayClient.serverUrl
        if (!savedUrl.isNullOrBlank()) {
            etServerUrl.setText(savedUrl)
        }

        RelayClient.onStatusChanged = { connected, message ->
            tvRelayStatus.text = message
            tvRelayStatus.setTextColor(
                if (connected) 0xFF4CAF50.toInt() else 0xFF888888.toInt()
            )
            btnDisconnect.visibility = if (connected || RelayClient.isConnected) View.VISIBLE else View.GONE
            btnConnect.text = if (RelayClient.isConnected) "CONNECTED" else "CONNECT"
            btnConnect.background = getDrawable(
                if (RelayClient.isConnected) R.drawable.bg_input_dark else R.drawable.bg_button_orange
            )
            btnConnect.setTextColor(
                if (RelayClient.isConnected) 0xFF4CAF50.toInt() else 0xFF1A1A1A.toInt()
            )
            updateStatus()
        }

        btnConnect.setOnClickListener {
            if (RelayClient.isConnected) return@setOnClickListener
            val url = etServerUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val code = PairingManager.getCode()
            RelayClient.connect(url, code)
        }

        btnDisconnect.setOnClickListener {
            RelayClient.disconnect()
            btnDisconnect.visibility = View.GONE
            btnConnect.text = "CONNECT"
            btnConnect.background = getDrawable(R.drawable.bg_button_orange)
            btnConnect.setTextColor(0xFF1A1A1A.toInt())
            updateStatus()
        }

        updateRelayButton()
    }

    private fun updateRelayButton() {
        if (RelayClient.isConnected) {
            btnDisconnect.visibility = View.VISIBLE
            btnConnect.text = "CONNECTED"
            btnConnect.background = getDrawable(R.drawable.bg_input_dark)
            btnConnect.setTextColor(0xFF4CAF50.toInt())
            tvRelayStatus.text = "Connected to ${RelayClient.serverUrl}"
            tvRelayStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            btnDisconnect.visibility = View.GONE
            btnConnect.text = "CONNECT"
            btnConnect.background = getDrawable(R.drawable.bg_button_orange)
            btnConnect.setTextColor(0xFF1A1A1A.toInt())
        }
    }

    private fun updateConnectionInfo() {
        val ip = getLocalIpAddress()
        tvAddress.text = "http://$ip:8765 (USB/LAN)"
    }

    private fun updateStatus() {
        val serviceRunning = BridgeAccessibilityService.instance != null
        val relayConnected = RelayClient.isConnected

        tvA11yStatus.text = if (serviceRunning) "active" else "inactive"
        tvA11yStatus.setTextColor(if (serviceRunning) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
        indicatorA11y.setBackgroundResource(
            if (serviceRunning) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_grey
        )

        tvServerStatus.text = "8765"
        tvServerStatus.setTextColor(0xFF4CAF50.toInt())

        if (relayConnected) {
            tvRelayAddr.text = RelayClient.serverUrl
            tvRelayAddr.setTextColor(0xFF4CAF50.toInt())
            indicatorRelay.setBackgroundResource(R.drawable.bg_status_dot_green)
        } else if (!RelayClient.serverUrl.isNullOrBlank()) {
            tvRelayAddr.text = "disconnected"
            tvRelayAddr.setTextColor(0xFF888888.toInt())
            indicatorRelay.setBackgroundResource(R.drawable.bg_status_dot_red)
        } else {
            tvRelayAddr.text = "—"
            tvRelayAddr.setTextColor(0xFF555555.toInt())
            indicatorRelay.setBackgroundResource(R.drawable.bg_status_dot_grey)
        }

        tvAuthCode.text = PairingManager.getCode()
        tvAuthCode.setTextColor(0xFF4CAF50.toInt())
    }

    private fun getLocalIpAddress(): String {
        return NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "localhost"
    }
}
