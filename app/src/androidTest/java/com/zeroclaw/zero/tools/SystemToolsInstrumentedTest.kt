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
 * Instrumented test for system settings tools.
 * Uses real ContentResolver/AudioManager on device.
 */
@RunWith(AndroidJUnit4::class)
class SystemToolsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        registry = ToolRegistry(context).apply {
            register(GetBrightnessTool(context))
            register(GetVolumeTool(context))
            register(SetVolumeTool(context))
            register(GetWifiStateTool(context))
            register(GetScreenTimeoutTool(context))
        }
    }

    // ── get_brightness ────────────────────────────────────────────────────────

    @Test
    fun getBrightnessReturnsJsonWithPercent() {
        val result = registry.execute("get_brightness", emptyMap())
        assertTrue("get_brightness should succeed: ${result.error}", result.success)

        val json = JSONObject(result.data)
        val percent = json.getInt("brightness_percent")
        assertTrue("brightness_percent should be 0-100, got $percent", percent in 0..100)
    }

    @Test
    fun getBrightnessReturnsRawBrightnessValue() {
        val result = registry.execute("get_brightness", emptyMap())
        val json = JSONObject(result.data)
        assertTrue("Should have brightness field", json.has("brightness"))
        val raw = json.getInt("brightness")
        assertTrue("Raw brightness should be 0-255, got $raw", raw in 0..255)
    }

    @Test
    fun getBrightnessReturnsAutoBrightnessField() {
        val result = registry.execute("get_brightness", emptyMap())
        val json = JSONObject(result.data)
        assertTrue("Should have auto_brightness field", json.has("auto_brightness"))
        // Just verify it's a boolean — don't assert a specific value
        json.getBoolean("auto_brightness")
    }

    // ── get_volume ────────────────────────────────────────────────────────────

    @Test
    fun getVolumeReturnsAllStreams() {
        val result = registry.execute("get_volume", emptyMap())
        assertTrue("get_volume should succeed: ${result.error}", result.success)

        val json = JSONObject(result.data)
        for (stream in listOf("media", "ring", "notification", "alarm")) {
            assertTrue("Should have $stream field", json.has(stream))
            val streamObj = json.getJSONObject(stream)
            assertTrue("$stream should have current", streamObj.has("current"))
            assertTrue("$stream should have max", streamObj.has("max"))
            assertTrue("$stream should have percent", streamObj.has("percent"))

            val pct = streamObj.getInt("percent")
            assertTrue("$stream percent should be 0-100, got $pct", pct in 0..100)
        }
    }

    // ── set_volume ────────────────────────────────────────────────────────────

    @Test
    fun setVolumeWithValidStreamSucceeds() {
        val result = registry.execute("set_volume", mapOf(
            "stream" to "media",
            "percentage" to 50
        ))
        assertTrue("set_volume should succeed: ${result.error}", result.success)
    }

    @Test
    fun setVolumeWithInvalidStreamReturnsError() {
        val result = registry.execute("set_volume", mapOf(
            "stream" to "invalid_stream",
            "percentage" to 50
        ))
        assertFalse("set_volume with invalid stream should fail", result.success)
    }

    @Test
    fun setVolumeMissingParamsReturnsError() {
        val result = registry.execute("set_volume", emptyMap())
        assertFalse("set_volume without params should fail", result.success)
    }

    // ── get_wifi_status ───────────────────────────────────────────────────────

    @Test
    fun getWifiStatusReturnsJsonWithEnabledField() {
        val result = registry.execute("get_wifi_status", emptyMap())
        assertTrue("get_wifi_status should succeed: ${result.error}", result.success)

        val json = JSONObject(result.data)
        assertTrue("Should have enabled field", json.has("enabled"))
        // Just verify it parses as boolean
        json.getBoolean("enabled")
    }

    // ── get_screen_timeout ────────────────────────────────────────────────────

    @Test
    fun getScreenTimeoutReturnsJsonWithTimeoutMs() {
        val result = registry.execute("get_screen_timeout", emptyMap())
        assertTrue("get_screen_timeout should succeed: ${result.error}", result.success)

        val json = JSONObject(result.data)
        assertTrue("Should have timeout_ms field", json.has("timeout_ms"))
        val ms = json.getLong("timeout_ms")
        assertTrue("timeout_ms should be positive, got $ms", ms > 0)
    }

    @Test
    fun getScreenTimeoutReturnsSeconds() {
        val result = registry.execute("get_screen_timeout", emptyMap())
        val json = JSONObject(result.data)
        assertTrue("Should have timeout_seconds field", json.has("timeout_seconds"))
        val seconds = json.getLong("timeout_seconds")
        assertTrue("timeout_seconds should be positive, got $seconds", seconds > 0)
    }
}
