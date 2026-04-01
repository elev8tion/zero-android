package com.zeroclaw.zero.tools

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings

class GetBrightnessTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "get_brightness",
        description = "Get current screen brightness level and auto-brightness status",
        category = "system",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        return try {
            val raw = Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, 128)
            val pct = (raw * 100) / 255
            val autoMode = Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val isAuto = autoMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            ToolResult(true, """{"brightness":$raw,"brightness_percent":$pct,"auto_brightness":$isAuto}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Failed to get brightness: ${e.message}")
        }
    }
}

class SetBrightnessTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "set_brightness",
        description = "Set screen brightness. Pass percentage 0-100, or set auto=true for auto-brightness. " +
            "Requires WRITE_SETTINGS permission.",
        category = "system",
        params = mapOf(
            "brightness" to ToolParam("int", "Brightness percentage 0-100", required = false),
            "auto" to ToolParam("boolean", "Set true to enable auto-brightness", required = false)
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!Settings.System.canWrite(context)) {
            return ToolResult(false, error = "WRITE_SETTINGS not granted. Open Zero app → tap Grant Permissions")
        }
        return try {
            val autoOn = params["auto"] as? Boolean
                ?: (params.getString("auto")?.equals("true", true))
            if (autoOn == true) {
                Settings.System.putInt(context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                return ToolResult(true, """{"success":true,"auto":true}""")
            }
            val pct = params.getInt("brightness")
                ?: return ToolResult(false, error = "Missing param: brightness (0-100) or auto (true)")
            val raw = (pct.coerceIn(0, 100) * 255) / 100
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, raw)
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            ToolResult(true, """{"success":true,"brightness_percent":$pct,"brightness_raw":$raw}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Failed: ${e.message}")
        }
    }
}

class GetVolumeTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "get_volume",
        description = "Get current volume levels for all audio streams: media, ring, notification, alarm",
        category = "system",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun streamInfo(stream: Int): String {
            val cur = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            val pct = if (max > 0) (cur * 100) / max else 0
            return """{"current":$cur,"max":$max,"percent":$pct}"""
        }
        return ToolResult(true, buildString {
            append("""{"media":"""); append(streamInfo(AudioManager.STREAM_MUSIC))
            append(""","ring":"""); append(streamInfo(AudioManager.STREAM_RING))
            append(""","notification":"""); append(streamInfo(AudioManager.STREAM_NOTIFICATION))
            append(""","alarm":"""); append(streamInfo(AudioManager.STREAM_ALARM))
            append("}")
        })
    }
}

class SetVolumeTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "set_volume",
        description = "Set volume for a specific audio stream by percentage",
        category = "system",
        params = mapOf(
            "stream" to ToolParam("string", "Stream: \"media\", \"ring\", \"notification\", or \"alarm\""),
            "percentage" to ToolParam("int", "Volume percentage 0-100")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val streamName = params.getString("stream")
            ?: return ToolResult(false, error = "Missing param: stream")
        val pct = params.getInt("percentage")
            ?: return ToolResult(false, error = "Missing param: percentage")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamName.lowercase()) {
            "media"        -> AudioManager.STREAM_MUSIC
            "ring"         -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm"        -> AudioManager.STREAM_ALARM
            else -> return ToolResult(false, error = "Invalid stream: $streamName")
        }
        val max = am.getStreamMaxVolume(stream)
        val level = (pct.coerceIn(0, 100) * max) / 100
        am.setStreamVolume(stream, level, 0)
        return ToolResult(true, """{"success":true,"stream":"$streamName","level":$level,"max":$max}""")
    }
}

class GetWifiStateTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "get_wifi_status",
        description = "Check if WiFi is enabled, show connected network name and IP address",
        category = "system",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val ssid = info?.ssid?.trim('"') ?: "none"
            val enabled = wm.isWifiEnabled
            val ip = intToIp(info?.ipAddress ?: 0)
            ToolResult(true, """{"enabled":$enabled,"connected_network":"$ssid","ip_address":"$ip"}""")
        } catch (e: Exception) {
            ToolResult(false, error = "WiFi query failed: ${e.message}")
        }
    }

    private fun intToIp(ip: Int) =
        "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
}

class GetScreenTimeoutTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "get_screen_timeout",
        description = "Get the screen-off timeout in seconds",
        category = "system",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        return try {
            val ms = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            ToolResult(true, """{"timeout_ms":$ms,"timeout_seconds":${ms / 1000}}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Failed to get screen timeout: ${e.message}")
        }
    }
}

class SetScreenTimeoutTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "set_screen_timeout",
        description = "Set how long the screen stays on after inactivity. Requires WRITE_SETTINGS permission.",
        category = "system",
        params = mapOf(
            "timeout_seconds" to ToolParam("int", "Timeout in seconds (e.g. 30, 60, 120, 300, 600)")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!Settings.System.canWrite(context)) {
            return ToolResult(false, error = "WRITE_SETTINGS not granted. Open Zero app → tap Grant Permissions")
        }
        val secs = params.getInt("timeout_seconds")
            ?: params.getInt("seconds")
            ?: return ToolResult(false, error = "Missing param: timeout_seconds")
        return try {
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT, secs * 1000)
            ToolResult(true, """{"success":true,"timeout_seconds":$secs}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Failed: ${e.message}")
        }
    }
}
