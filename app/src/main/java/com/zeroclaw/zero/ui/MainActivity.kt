package com.zeroclaw.zero.ui

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zeroclaw.zero.R
import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.accessibility.ZeroAccessibilityService
import com.zeroclaw.zero.mcp.MCP_PORT
import com.zeroclaw.zero.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_VOICE_ACTIVATE = "com.zeroclaw.zero.VOICE_ACTIVATE"
        private const val REQ_OVERLAY   = 1001
        private const val REQ_PERMS     = 1002
        private const val REQ_ALL_PERMS = 1003
    }

    private lateinit var statusAccessibility: TextView
    private lateinit var statusMcp: TextView
    private lateinit var statusProxy: TextView
    private lateinit var statusPermissions: TextView
    private lateinit var btnEnableAccessibility: TextView
    private lateinit var btnGrantPermissions: TextView
    private lateinit var btnOverlay: TextView
    private lateinit var btnSettings: android.widget.ImageView

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusAccessibility    = findViewById(R.id.statusAccessibility)
        statusMcp              = findViewById(R.id.statusMcp)
        statusProxy            = findViewById(R.id.statusProxy)
        statusPermissions      = findViewById(R.id.statusPermissions)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnGrantPermissions    = findViewById(R.id.btnGrantPermissions)
        btnOverlay             = findViewById(R.id.btnOverlay)
        btnSettings            = findViewById(R.id.btnSettings)

        prefs = getSharedPreferences("zero_prefs", MODE_PRIVATE)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnGrantPermissions.setOnClickListener { requestAllPermissions() }

        btnOverlay.setOnClickListener { toggleOverlay() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY && Settings.canDrawOverlays(this)) {
            launchOverlay()
        }
    }

    // ── Permission grant flow ────────────────────────────────────────────────

    private fun requestAllPermissions() {
        // 1) Request all missing dangerous permissions in one batch
        val missing = PermissionManager.getMissingDangerousPermissions(this)
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_ALL_PERMS)
            return
        }
        // 2) All dangerous granted — chain to special permissions
        requestSpecialPermissions()
    }

    private fun requestSpecialPermissions() {
        if (!PermissionManager.canWriteSettings(this)) {
            Toast.makeText(this, "Grant 'Modify system settings' permission", Toast.LENGTH_LONG).show()
            startActivity(PermissionManager.writeSettingsIntent(this))
            return
        }
        if (!PermissionManager.hasDndAccess(this)) {
            Toast.makeText(this, "Grant 'Do Not Disturb access' permission", Toast.LENGTH_LONG).show()
            startActivity(PermissionManager.dndAccessIntent())
            return
        }
        Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
    }

    // ── Overlay toggle ────────────────────────────────────────────────────────

    private fun toggleOverlay() {
        val running = prefs.getBoolean("overlay_running", false)
        if (running) {
            stopService(Intent(this, OverlayService::class.java))
            prefs.edit().putBoolean("overlay_running", false).apply()
            btnOverlay.text = "Start Overlay"
        } else {
            // Check overlay permission
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")),
                    REQ_OVERLAY
                )
                return
            }
            // Check core runtime permissions (microphone + notifications)
            val coreMissing = PermissionManager.getMissingDangerousPermissions(this)
                .filter { it == android.Manifest.permission.RECORD_AUDIO ||
                          it == android.Manifest.permission.POST_NOTIFICATIONS }
            if (coreMissing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, coreMissing.toTypedArray(), REQ_PERMS)
                return
            }
            launchOverlay()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERMS -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    launchOverlay()
                } else {
                    Toast.makeText(this, "Microphone permission required for voice control", Toast.LENGTH_LONG).show()
                }
            }
            REQ_ALL_PERMS -> {
                updateStatus()
                // Chain to special permissions regardless — some dangerous may have been denied
                requestSpecialPermissions()
            }
        }
    }

    private fun launchOverlay() {
        startForegroundService(Intent(this, OverlayService::class.java))
        prefs.edit().putBoolean("overlay_running", true).apply()
        btnOverlay.text = "Stop Overlay"
        Toast.makeText(this, "Overlay started — tap the orb to talk", Toast.LENGTH_SHORT).show()
    }

    // ── Status panel ──────────────────────────────────────────────────────────

    private fun updateStatus() {
        // Accessibility
        if (ZeroAccessibilityService.isRunning) {
            statusAccessibility.text = "● Running"
            statusAccessibility.setTextColor(getColor(R.color.secondary))
            btnEnableAccessibility.visibility = View.GONE
        } else {
            statusAccessibility.text = "● Not enabled"
            statusAccessibility.setTextColor(getColor(R.color.error))
            btnEnableAccessibility.visibility = View.VISIBLE
        }

        // MCP server
        val mcpRunning = prefs.getBoolean("mcp_running", false)
        if (mcpRunning) {
            statusMcp.text = "● http://${getLanIp()}:$MCP_PORT/mcp"
            statusMcp.setTextColor(getColor(R.color.secondary))
        } else {
            statusMcp.text = "● Stopped"
            statusMcp.setTextColor(getColor(R.color.text_secondary))
        }

        // Session proxy
        val proxyUrl = ZeroApp.instance.prefs.proxyUrl
        statusProxy.text = proxyUrl
        statusProxy.setTextColor(
            if (proxyUrl.contains("YOUR_MAC_IP")) getColor(R.color.error)
            else getColor(R.color.text_primary)
        )

        // Permissions
        val granted = PermissionManager.grantedCount(this)
        val total = PermissionManager.TOTAL_COUNT
        if (granted == total) {
            statusPermissions.text = "● $granted/$total Granted"
            statusPermissions.setTextColor(getColor(R.color.secondary))
            btnGrantPermissions.visibility = View.GONE
        } else {
            statusPermissions.text = "● $granted/$total — Tap to grant"
            statusPermissions.setTextColor(getColor(R.color.error))
            btnGrantPermissions.visibility = View.VISIBLE
        }

        // Overlay button label
        val overlayRunning = prefs.getBoolean("overlay_running", false)
        btnOverlay.text = if (overlayRunning) "Stop Overlay" else "Start Overlay"
    }

    private fun getLanIp(): String {
        return try {
            val wm  = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val raw = wm.connectionInfo?.ipAddress ?: 0
            "${raw and 0xff}.${raw shr 8 and 0xff}.${raw shr 16 and 0xff}.${raw shr 24 and 0xff}"
        } catch (_: Exception) { "127.0.0.1" }
    }
}
