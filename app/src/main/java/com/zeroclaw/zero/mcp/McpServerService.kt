package com.zeroclaw.zero.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import com.zeroclaw.zero.R
import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.ui.MainActivity

private const val TAG = "McpServerService"
private const val CHANNEL_ID = "zero_mcp_channel"
private const val NOTIF_ID = 1001
const val MCP_PORT = 3282

class McpServerService : Service() {

    private var mcpServer: McpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", MCP_PORT) ?: MCP_PORT
        val ip = getLanIp()
        startForeground(NOTIF_ID, buildNotification(ip, port))
        startMcpServer(port)
        Log.i(TAG, "MCP service started on $ip:$port")
        return START_STICKY
    }

    override fun onDestroy() {
        mcpServer?.stop()
        mcpServer = null
        Log.i(TAG, "MCP service destroyed")
        super.onDestroy()
    }

    private fun startMcpServer(port: Int) {
        if (mcpServer?.isRunning == true) return
        val registry = (application as ZeroApp).toolRegistry
        mcpServer = McpServer(registry, port).also { it.start() }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Zero MCP Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the MCP tool server running"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(ip: String, port: Int): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Zero MCP Server")
            .setContentText("http://$ip:$port/mcp")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private fun getLanIp(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wm.connectionInfo?.ipAddress ?: 0
            if (raw == 0) return "127.0.0.1"
            "${raw and 0xff}.${raw shr 8 and 0xff}.${raw shr 16 and 0xff}.${raw shr 24 and 0xff}"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
