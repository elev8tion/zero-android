package com.zeroclaw.zero.tools

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

private const val TAG = "DeviceTools"

class GetAutoRotateTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "get_auto_rotate",
        description = "Check if auto-rotation is enabled on the device",
        category = "device",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        return try {
            val value = Settings.System.getInt(context.contentResolver, "accelerometer_rotation", 0)
            ToolResult(true, """{"auto_rotate":${value == 1}}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Failed: ${e.message}")
        }
    }
}

class SetAutoRotateTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "set_auto_rotate",
        description = "Enable or disable auto-rotation of the device screen. Requires WRITE_SETTINGS permission.",
        category = "device",
        params = mapOf(
            "enabled" to ToolParam("boolean", "true to enable, false to disable auto-rotation")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!Settings.System.canWrite(context)) {
            return ToolResult(false, error = "WRITE_SETTINGS not granted. Open Zero app → tap Grant Permissions")
        }
        val enabled = params["enabled"] as? Boolean
            ?: (params.getString("enabled")?.equals("true", true))
            ?: return ToolResult(false, error = "Missing param: enabled")
        return try {
            Settings.System.putInt(context.contentResolver,
                "accelerometer_rotation", if (enabled) 1 else 0)
            ToolResult(true, """{"success":true}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Failed: ${e.message}")
        }
    }
}

class GetBluetoothStatusTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "get_bluetooth_status",
        description = "Check if Bluetooth is enabled and list paired devices",
        category = "device",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(false, error = "BLUETOOTH_CONNECT not granted. Open Zero app → tap Grant Permissions")
        }
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                return ToolResult(true, """{"supported":false,"enabled":false,"paired_devices":[]}""")
            }
            val enabled = adapter.isEnabled
            val paired = if (enabled) {
                adapter.bondedDevices?.joinToString(",") {
                    """{"name":"${it.name}","address":"${it.address}"}"""
                } ?: ""
            } else ""
            ToolResult(true, """{"supported":true,"enabled":$enabled,"paired_devices":[$paired]}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Bluetooth query failed: ${e.message}")
        }
    }
}

class SetFlashlightTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "set_flashlight",
        description = "Turn the device's flashlight (camera torch) on or off",
        category = "device",
        params = mapOf(
            "enabled" to ToolParam("boolean", "true to turn on, false to turn off")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val enabled = params["enabled"] as? Boolean
            ?: (params.getString("enabled")?.equals("true", true))
            ?: return ToolResult(false, error = "Missing param: enabled")
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: return ToolResult(false, error = "No camera found")
            cm.setTorchMode(cameraId, enabled)
            ToolResult(true, """{"success":true}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Flashlight failed: ${e.message}")
        }
    }
}

class SetRingerModeTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "set_ringer_mode",
        description = "Set the device's ringer mode",
        category = "device",
        params = mapOf(
            "mode" to ToolParam("string", "Mode: \"normal\", \"vibrate\", or \"silent\"")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val mode = params.getString("mode") ?: return ToolResult(false, error = "Missing param: mode")
        val ringerMode = when (mode.lowercase()) {
            "normal"  -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent"  -> AudioManager.RINGER_MODE_SILENT
            else      -> return ToolResult(false, error = "Invalid mode: $mode (use normal/vibrate/silent)")
        }
        // Silent mode requires DND access on most devices
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                return ToolResult(false, error = "DND access not granted. Open Zero app → tap Grant Permissions")
            }
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = ringerMode
            ToolResult(true, """{"success":true,"mode":"$mode"}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Set ringer mode failed: ${e.message}")
        }
    }
}

class GetDndStatusTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "get_dnd_status",
        description = "Get the current Do Not Disturb (interruption filter) mode",
        category = "device",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val filter = nm.currentInterruptionFilter
            val mode = when (filter) {
                NotificationManager.INTERRUPTION_FILTER_ALL            -> "off"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY       -> "priority_only"
                NotificationManager.INTERRUPTION_FILTER_ALARMS         -> "alarms_only"
                NotificationManager.INTERRUPTION_FILTER_NONE           -> "total_silence"
                else -> "unknown"
            }
            ToolResult(true, """{"mode":"$mode","filter":$filter}""")
        } catch (e: Exception) {
            ToolResult(false, error = "DND query failed: ${e.message}")
        }
    }
}

class SetDndModeTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "set_dnd_mode",
        description = "Set the Do Not Disturb mode. Requires ACCESS_NOTIFICATION_POLICY permission.",
        category = "device",
        params = mapOf(
            "mode" to ToolParam("string", "Mode: \"off\", \"priority_only\", \"alarms_only\", or \"total_silence\"")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            return ToolResult(false, error = "DND access not granted. Open Zero app → tap Grant Permissions")
        }
        val mode = params.getString("mode") ?: return ToolResult(false, error = "Missing param: mode")
        val filter = when (mode.lowercase()) {
            "off"            -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority_only"  -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms_only"    -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "total_silence"  -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return ToolResult(false, error = "Invalid mode: $mode")
        }
        return try {
            nm.setInterruptionFilter(filter)
            ToolResult(true, """{"success":true,"mode":"$mode"}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Set DND failed: ${e.message}")
        }
    }
}

class GetLocationTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "get_location",
        description = "Get the device's last known location (latitude, longitude, address). " +
            "Requires ACCESS_FINE_LOCATION permission.",
        category = "device",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(false, error = "ACCESS_FINE_LOCATION not granted. Open Zero app → tap Grant Permissions")
        }
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )
            var location: android.location.Location? = null
            for (provider in providers) {
                try {
                    @Suppress("MissingPermission")
                    location = lm.getLastKnownLocation(provider)
                    if (location != null) break
                } catch (_: Exception) {}
            }
            if (location == null) {
                return ToolResult(false, error = "No last known location — GPS may be off or no fix yet")
            }
            val lat = location.latitude
            val lon = location.longitude
            val address = try {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()?.let { addr ->
                    buildString {
                        if (addr.thoroughfare != null) append("${addr.thoroughfare}, ")
                        if (addr.locality != null) append("${addr.locality}, ")
                        if (addr.adminArea != null) append("${addr.adminArea} ")
                        if (addr.countryName != null) append(addr.countryName)
                    }.trim().trimEnd(',')
                } ?: "unknown"
            } catch (_: Exception) { "geocoding_unavailable" }
            ToolResult(true, """{"lat":$lat,"lon":$lon,"address":"$address"}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Location failed: ${e.message}")
        }
    }
}
