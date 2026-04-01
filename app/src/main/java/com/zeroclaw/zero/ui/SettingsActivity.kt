package com.zeroclaw.zero.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zeroclaw.zero.R
import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.mcp.MCP_PORT
import com.zeroclaw.zero.mcp.McpServerService

class SettingsActivity : AppCompatActivity() {

    private lateinit var inputProxyUrl: EditText
    private lateinit var btnSave: TextView
    private lateinit var mcpToggle: TextView
    private lateinit var mcpAddressLabel: TextView

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("zero_prefs", MODE_PRIVATE)

        inputProxyUrl   = findViewById(R.id.inputProxyUrl)
        btnSave         = findViewById(R.id.btnSave)
        mcpToggle       = findViewById(R.id.btnMcpToggle)
        mcpAddressLabel = findViewById(R.id.mcpAddressLabel)

        inputProxyUrl.setText(ZeroApp.instance.prefs.proxyUrl)

        btnSave.setOnClickListener { save() }
        mcpToggle.setOnClickListener { toggleMcpServer() }

        updateMcpUi()
    }

    override fun onResume() {
        super.onResume()
        updateMcpUi()
    }

    private fun save() {
        val url = inputProxyUrl.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "Proxy URL required", Toast.LENGTH_SHORT).show()
            return
        }
        ZeroApp.instance.prefs.proxyUrl = url
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun toggleMcpServer() {
        val running = prefs.getBoolean("mcp_running", false)
        if (running) {
            stopService(Intent(this, McpServerService::class.java))
            prefs.edit().putBoolean("mcp_running", false).apply()
        } else {
            val intent = Intent(this, McpServerService::class.java)
                .putExtra("port", MCP_PORT)
            startForegroundService(intent)
            prefs.edit().putBoolean("mcp_running", true).apply()
        }
        updateMcpUi()
    }

    private fun updateMcpUi() {
        val running = prefs.getBoolean("mcp_running", false)
        mcpToggle.text = if (running) "Stop MCP Server" else "Start MCP Server"
        if (running) {
            val ip = getLanIp()
            mcpAddressLabel.text = "MCP endpoint: http://$ip:$MCP_PORT/mcp"
        } else {
            mcpAddressLabel.text = "MCP server stopped"
        }
    }

    private fun getLanIp(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val raw = wm.connectionInfo?.ipAddress ?: 0
            "${raw and 0xff}.${raw shr 8 and 0xff}.${raw shr 16 and 0xff}.${raw shr 24 and 0xff}"
        } catch (_: Exception) { "127.0.0.1" }
    }
}
