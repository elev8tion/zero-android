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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var inputProxyUrl: EditText
    private lateinit var btnSave: TextView
    private lateinit var mcpToggle: TextView
    private lateinit var mcpAddressLabel: TextView

    private lateinit var inputT0gglesApiKey: EditText
    private lateinit var inputT0gglesBoardId: EditText
    private lateinit var inputT0gglesUrl: EditText
    private lateinit var btnSaveT0ggles: TextView
    private lateinit var btnRefreshTools: TextView
    private lateinit var t0gglesStatusLabel: TextView

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("zero_prefs", MODE_PRIVATE)

        inputProxyUrl   = findViewById(R.id.inputProxyUrl)
        btnSave         = findViewById(R.id.btnSave)
        mcpToggle       = findViewById(R.id.btnMcpToggle)
        mcpAddressLabel = findViewById(R.id.mcpAddressLabel)

        inputT0gglesApiKey  = findViewById(R.id.inputT0gglesApiKey)
        inputT0gglesBoardId = findViewById(R.id.inputT0gglesBoardId)
        inputT0gglesUrl     = findViewById(R.id.inputT0gglesUrl)
        btnSaveT0ggles      = findViewById(R.id.btnSaveT0ggles)
        btnRefreshTools     = findViewById(R.id.btnRefreshTools)
        t0gglesStatusLabel  = findViewById(R.id.t0gglesStatusLabel)

        inputProxyUrl.setText(ZeroApp.instance.prefs.proxyUrl)

        val appPrefs = ZeroApp.instance.prefs
        inputT0gglesApiKey.setText(appPrefs.t0gglesApiKey)
        inputT0gglesBoardId.setText(appPrefs.t0gglesBoardId)
        inputT0gglesUrl.setText(appPrefs.t0gglesUrl)

        btnSave.setOnClickListener { save() }
        mcpToggle.setOnClickListener { toggleMcpServer() }
        btnSaveT0ggles.setOnClickListener { saveT0ggles() }
        btnRefreshTools.setOnClickListener { refreshT0gglesTools() }

        updateMcpUi()
        updateT0gglesUi()
    }

    override fun onResume() {
        super.onResume()
        updateMcpUi()
        updateT0gglesUi()
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

    private fun saveT0ggles() {
        val apiKey  = inputT0gglesApiKey.text.toString().trim()
        val boardId = inputT0gglesBoardId.text.toString().trim()
        val url     = inputT0gglesUrl.text.toString().trim()

        if (apiKey.isBlank() || url.isBlank()) {
            Toast.makeText(this, "API key and URL required", Toast.LENGTH_SHORT).show()
            return
        }

        val appPrefs = ZeroApp.instance.prefs
        appPrefs.t0gglesApiKey  = apiKey
        appPrefs.t0gglesBoardId = boardId
        appPrefs.t0gglesUrl     = url
        Toast.makeText(this, "T0ggles settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun refreshT0gglesTools() {
        t0gglesStatusLabel.text = "Discovering tools..."
        btnRefreshTools.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Unregister existing workflow tools
                val registry = ZeroApp.instance.toolRegistry
                registry.getDefinitions()
                    .filter { it.category == "workflow" }
                    .forEach { registry.unregister(it.name) }

                // Re-discover
                ZeroApp.instance.registerT0gglesTools()

                val count = registry.getDefinitions().count { it.category == "workflow" }
                withContext(Dispatchers.Main) {
                    t0gglesStatusLabel.text = "Loaded $count workflow tools"
                    btnRefreshTools.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    t0gglesStatusLabel.text = "Failed: ${e.message}"
                    btnRefreshTools.isEnabled = true
                }
            }
        }
    }

    private fun updateT0gglesUi() {
        val count = ZeroApp.instance.toolRegistry.getDefinitions()
            .count { it.category == "workflow" }
        t0gglesStatusLabel.text = if (count > 0) {
            "$count workflow tools loaded"
        } else {
            "Workflow tools not loaded"
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
