package com.zeroclaw.zero.tools

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for app tools that use real Context/PackageManager.
 * These do NOT require the accessibility service.
 */
@RunWith(AndroidJUnit4::class)
class AppToolsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        registry = ToolRegistry(context).apply {
            register(LaunchAppTool(context))
            register(FindAppTool(context))
            register(ListAppsTool(context))
            register(GetCurrentAppTool())
        }
    }

    // ── launch_app ────────────────────────────────────────────────────────────

    @Test
    fun launchAppWithSettingsSucceeds() {
        val result = registry.execute("launch_app", mapOf("package_name" to "com.android.settings"))
        assertTrue("launch_app with Settings should succeed: ${result.error}", result.success)
    }

    @Test
    fun launchAppWithNonexistentPackageReturnsError() {
        val result = registry.execute("launch_app", mapOf("package_name" to "com.fake.nonexistent.app"))
        assertFalse("launch_app with fake package should fail", result.success)
        assertNotNull(result.error)
    }

    @Test
    fun launchAppWithMissingParamReturnsError() {
        val result = registry.execute("launch_app", emptyMap())
        assertFalse("launch_app without package_name should fail", result.success)
    }

    // ── find_app ──────────────────────────────────────────────────────────────

    @Test
    fun findAppWithSettingsQueryFindsResults() {
        val result = registry.execute("find_app", mapOf("query" to "settings"))
        assertTrue("find_app for 'settings' should succeed: ${result.error}", result.success)
        assertTrue("Should find at least one result", result.data.isNotBlank())
    }

    @Test
    fun findAppWithNonexistentQueryReturnsError() {
        val result = registry.execute("find_app", mapOf("query" to "zzzznonexistent999"))
        assertFalse("find_app with gibberish should fail", result.success)
    }

    @Test
    fun findAppWithMissingParamReturnsError() {
        val result = registry.execute("find_app", emptyMap())
        assertFalse("find_app without query should fail", result.success)
    }

    // ── list_apps ─────────────────────────────────────────────────────────────

    @Test
    fun listAppsReturnsNonEmptyList() {
        val result = registry.execute("list_apps", emptyMap())
        assertTrue("list_apps should succeed: ${result.error}", result.success)
        assertTrue("list_apps should return data", result.data.isNotBlank())
    }

    @Test
    fun listAppsContainsPackageNames() {
        val result = registry.execute("list_apps", emptyMap())
        // Every Android device/emulator has at least Settings
        assertTrue(
            "Should contain at least one package name with a dot",
            result.data.contains(".")
        )
    }

    // ── get_current_app ───────────────────────────────────────────────────────

    @Test
    fun getCurrentAppWithoutAccessibilityReturnsError() {
        val result = registry.execute("get_current_app", emptyMap())
        assertFalse(
            "get_current_app should fail without accessibility service",
            result.success
        )
        assertNotNull(result.error)
    }
}
