package com.zeroclaw.zero.tools

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for device hardware tools.
 * Tests real BluetoothAdapter, AudioManager, NotificationManager on device.
 */
@RunWith(AndroidJUnit4::class)
class DeviceToolsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        registry = ToolRegistry(context).apply {
            register(GetBluetoothStatusTool())
            register(SetRingerModeTool(context))
            register(GetDndStatusTool(context))
            register(GetDeviceStatusTool())
            register(GetAutoRotateTool(context))
        }
    }

    // ── get_bluetooth_status ──────────────────────────────────────────────────

    @Test
    fun getBluetoothStatusReturnsResultWithoutCrash() {
        // On Android 12+ (API 31), BLUETOOTH_CONNECT permission is required
        // at runtime. Test app won't have it, so accept either success with
        // JSON fields or a clean permission error.
        val result = registry.execute("get_bluetooth_status", emptyMap())
        if (result.success) {
            val json = JSONObject(result.data)
            assertTrue("Should have supported field", json.has("supported"))
            assertTrue("Should have enabled field", json.has("enabled"))
        } else {
            assertNotNull("Should have error message", result.error)
            assertFalse(
                "Error should not be a stack trace: ${result.error}",
                result.error!!.contains("at com.")
            )
        }
    }

    // ── set_ringer_mode ───────────────────────────────────────────────────────

    @Test
    fun setRingerModeNormalSucceeds() {
        val result = registry.execute("set_ringer_mode", mapOf("mode" to "normal"))
        assertTrue("set_ringer_mode normal should succeed: ${result.error}", result.success)
    }

    @Test
    fun setRingerModeVibrateSucceeds() {
        val result = registry.execute("set_ringer_mode", mapOf("mode" to "vibrate"))
        assertTrue("set_ringer_mode vibrate should succeed: ${result.error}", result.success)
    }

    @Test
    fun setRingerModeSilentReturnsResultWithoutCrash() {
        // Silent mode requires ACCESS_NOTIFICATION_POLICY (DND) on many devices.
        // Test app won't have this permission, so we accept either success or
        // a clean permission error — the key is no crash.
        val result = registry.execute("set_ringer_mode", mapOf("mode" to "silent"))
        if (!result.success) {
            assertNotNull("Should have error message", result.error)
            assertFalse(
                "Error should not be a stack trace: ${result.error}",
                result.error!!.contains("at com.")
            )
        }
    }

    @Test
    fun setRingerModeInvalidReturnsError() {
        val result = registry.execute("set_ringer_mode", mapOf("mode" to "invalid_mode"))
        assertFalse("set_ringer_mode with invalid mode should fail", result.success)
        assertNotNull(result.error)
    }

    @Test
    fun setRingerModeMissingParamReturnsError() {
        val result = registry.execute("set_ringer_mode", emptyMap())
        assertFalse("set_ringer_mode without params should fail", result.success)
    }

    // ── get_dnd_status ────────────────────────────────────────────────────────

    @Test
    fun getDndStatusReturnsJsonWithModeField() {
        val result = registry.execute("get_dnd_status", emptyMap())
        assertTrue("get_dnd_status should succeed: ${result.error}", result.success)

        val json = JSONObject(result.data)
        assertTrue("Should have mode field", json.has("mode"))
        val mode = json.getString("mode")
        val validModes = setOf("off", "priority_only", "alarms_only", "total_silence")
        assertTrue(
            "Mode should be one of $validModes, got '$mode'",
            mode in validModes
        )
    }

    // ── get_device_status ─────────────────────────────────────────────────────

    @Test
    fun getDeviceStatusReturnsServiceRunningFalse() {
        val result = registry.execute("get_device_status", emptyMap())
        assertTrue("get_device_status should succeed", result.success)

        val json = JSONObject(result.data)
        assertFalse(
            "service_running should be false without accessibility enabled",
            json.getBoolean("service_running")
        )
    }

    // ── get_auto_rotate ───────────────────────────────────────────────────────

    @Test
    fun getAutoRotateReturnsJsonWithBoolean() {
        val result = registry.execute("get_auto_rotate", emptyMap())
        assertTrue("get_auto_rotate should succeed: ${result.error}", result.success)

        val json = JSONObject(result.data)
        assertTrue("Should have auto_rotate field", json.has("auto_rotate"))
        // Verify it parses as boolean without error
        json.getBoolean("auto_rotate")
    }
}
