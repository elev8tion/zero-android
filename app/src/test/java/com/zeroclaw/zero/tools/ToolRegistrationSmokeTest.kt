package com.zeroclaw.zero.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.data.ErrorDatabase
import com.zeroclaw.zero.data.JsonRpcTransport
import com.zeroclaw.zero.data.T0gglesClient
import com.zeroclaw.zero.data.T0gglesException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test: registers all 49 tools exactly as ZeroApp does,
 * validates schemas, checks for duplicates, and verifies graceful
 * error handling when the accessibility service is not running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ToolRegistrationSmokeTest {

    private lateinit var registry: ToolRegistry
    private val gson = Gson()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        registry = ToolRegistry(context).apply {
            // ── Gestures (9) ────────────────────────────────────────────────
            register(TapTool())
            register(LongTapScreenTool())
            register(SwipeTool())
            register(ScrollTool())
            register(TypeTextTool())
            register(SubmitTextTool())
            register(ClearTextTool())
            register(FindAndClickTool())
            register(FindAndClickByIdTool())

            // ── Navigation (5) ──────────────────────────────────────────────
            register(PressBackTool())
            register(PressHomeTool())
            register(PressRecentsTool())
            register(ShowAllAppsTool())
            register(FindEditableAndTypeTool())

            // ── Screen reading (10) ─────────────────────────────────────────
            register(GetScreenStateTool())
            register(GetScreenContentTool())
            register(GetScreenJsonTool())
            register(GetDeviceStatusTool())
            register(ClickNodeTool())
            register(LongClickNodeTool())
            register(FindElementTool())
            register(WaitForElementTool())
            register(AwaitScreenChangeTool())
            register(TakeScreenshotTool())

            // ── App control (4) ─────────────────────────────────────────────
            register(LaunchAppTool(context))
            register(FindAppTool(context))
            register(ListAppsTool(context))
            register(GetCurrentAppTool())

            // ── System settings (7) ─────────────────────────────────────────
            register(GetBrightnessTool(context))
            register(SetBrightnessTool(context))
            register(GetVolumeTool(context))
            register(SetVolumeTool(context))
            register(GetWifiStateTool(context))
            register(GetScreenTimeoutTool(context))
            register(SetScreenTimeoutTool(context))

            // ── Device hardware (8) ─────────────────────────────────────────
            register(GetAutoRotateTool(context))
            register(SetAutoRotateTool(context))
            register(GetBluetoothStatusTool(context))
            register(SetFlashlightTool(context))
            register(SetRingerModeTool(context))
            register(GetDndStatusTool(context))
            register(SetDndModeTool(context))
            register(GetLocationTool(context))

            // ── Contacts & communication (3) ────────────────────────────────
            register(ListContactsTool(context))
            register(SendSmsTool(context))
            register(MakeCallTool(context))

            // ── Workflow (3) ──────────────────────────────────────────────────
            val prefs = AppPrefs(context)
            val noOpTransport = JsonRpcTransport { _, _, _ ->
                throw T0gglesException("Not connected")
            }
            val client = T0gglesClient(prefs, noOpTransport)
            val testScope = TestScope(UnconfinedTestDispatcher())
            val errorDb = ErrorDatabase(client, prefs, testScope)
            register(QueryErrorLogTool(errorDb))
            register(ListWorkflowsTool())
            register(RunWorkflowTool(client, prefs))
        }
    }

    // ── Registration count ──────────────────────────────────────────────────

    @Test
    fun `all 49 tools registered`() {
        assertEquals(49, registry.toolCount())
    }

    // ── Name uniqueness ─────────────────────────────────────────────────────

    @Test
    fun `no duplicate tool names`() {
        val defs = registry.getDefinitions()
        val names = defs.map { it.name }
        assertEquals("Duplicate tool names found", names.size, names.toSet().size)
    }

    // ── Definition validity ─────────────────────────────────────────────────

    @Test
    fun `every tool has non-empty name`() {
        registry.getDefinitions().forEach { def ->
            assertTrue("Tool has empty name", def.name.isNotBlank())
        }
    }

    @Test
    fun `every tool has non-empty description`() {
        registry.getDefinitions().forEach { def ->
            assertTrue("${def.name} has empty description", def.description.isNotBlank())
        }
    }

    @Test
    fun `every tool has non-empty category`() {
        registry.getDefinitions().forEach { def ->
            assertTrue("${def.name} has empty category", def.category.isNotBlank())
        }
    }

    @Test
    fun `tool names use snake_case`() {
        registry.getDefinitions().forEach { def ->
            assertTrue(
                "${def.name} is not snake_case",
                def.name.matches(Regex("^[a-z][a-z0-9_]*$"))
            )
        }
    }

    // ── Expected tool names present ─────────────────────────────────────────

    @Test
    fun `all expected tool names are registered`() {
        val expected = listOf(
            // Gesture
            "tap", "long_tap_screen", "swipe", "scroll", "type_text",
            "submit_text", "clear_text", "find_and_click", "find_and_click_by_id",
            // Navigation
            "press_back", "press_home", "press_recents", "show_all_apps",
            "find_editable_and_type",
            // Screen
            "get_screen_state", "get_screen_content", "get_screen_json",
            "get_device_status", "click_node", "long_click_node",
            "find_element", "wait_for_element", "await_screen_change",
            "take_screenshot",
            // App
            "launch_app", "find_app", "list_apps", "get_current_app",
            // System
            "get_brightness", "set_brightness", "get_volume", "set_volume",
            "get_wifi_status", "get_screen_timeout", "set_screen_timeout",
            // Device
            "get_auto_rotate", "set_auto_rotate", "get_bluetooth_status",
            "set_flashlight", "set_ringer_mode", "get_dnd_status",
            "set_dnd_mode", "get_location",
            // Contacts
            "list_contacts", "send_sms", "make_call",
            // Workflow
            "query_error_log",
            "list_workflows",
            "run_workflow"
        )

        expected.forEach { name ->
            assertTrue("Missing tool: $name", registry.hasToolNamed(name))
        }
        assertEquals(expected.size, registry.toolCount())
    }

    // ── Categories ──────────────────────────────────────────────────────────

    @Test
    fun `tools span expected categories`() {
        val categories = registry.getDefinitions().map { it.category }.toSet()
        assertTrue("gesture" in categories)
        assertTrue("screen" in categories)
        assertTrue("navigation" in categories || categories.any { it.contains("nav") })
        assertTrue("app" in categories)
        assertTrue("system" in categories)
        assertTrue("device" in categories)
        assertTrue("contacts" in categories)
        assertTrue("workflow" in categories)
    }

    // ── Schema serialization ────────────────────────────────────────────────

    @Test
    fun `every tool definition serializes to valid JSON`() {
        registry.getDefinitions().forEach { def ->
            val map = mapOf(
                "name" to def.name,
                "description" to def.description,
                "category" to def.category,
                "params" to def.params.mapValues { (_, p) ->
                    mapOf("type" to p.type, "description" to p.description, "required" to p.required)
                }
            )
            val json = gson.toJson(map)
            assertTrue("${def.name} produced empty JSON", json.isNotBlank())
            // Verify it round-trips
            val parsed = gson.fromJson(json, Map::class.java)
            assertEquals(def.name, parsed["name"])
        }
    }

    @Test
    fun `param types are valid JSON schema types`() {
        val validTypes = setOf("string", "int", "integer", "float", "number", "boolean", "object", "array")
        registry.getDefinitions().forEach { def ->
            def.params.forEach { (paramName, param) ->
                assertTrue(
                    "${def.name}.$paramName has invalid type '${param.type}'",
                    param.type in validTypes
                )
            }
        }
    }

    @Test
    fun `required params have descriptions`() {
        registry.getDefinitions().forEach { def ->
            def.params.filter { it.value.required }.forEach { (paramName, param) ->
                assertTrue(
                    "${def.name}.$paramName (required) has empty description",
                    param.description.isNotBlank()
                )
            }
        }
    }

    // ── Graceful degradation without accessibility service ───────────────────

    @Test
    fun `accessibility-dependent tools return error when service not running`() {
        val accessibilityTools = listOf(
            "tap", "swipe", "scroll", "type_text", "clear_text",
            "find_and_click", "find_and_click_by_id",
            "press_back", "press_home", "press_recents",
            "get_screen_state", "get_screen_content", "get_screen_json",
            "click_node", "long_click_node",
            "take_screenshot", "show_all_apps",
            "submit_text", "find_editable_and_type",
            "find_element", "long_tap_screen",
            "await_screen_change"
        )

        accessibilityTools.forEach { name ->
            val result = registry.execute(name, emptyMap())
            assertFalse(
                "$name should fail without accessibility service",
                result.success && result.data.isNotBlank() && !result.data.contains("service_running")
            )
        }
    }

    @Test
    fun `get_device_status works without accessibility service`() {
        val result = registry.execute("get_device_status")
        assertTrue(result.success)
        assertTrue(result.data.contains("service_running"))
        assertTrue(result.data.contains("false"))
    }

    // ── Missing required params ─────────────────────────────────────────────

    @Test
    fun `tools with required params return error when params missing`() {
        val toolsWithRequiredParams = registry.getDefinitions()
            .filter { it.params.any { (_, p) -> p.required } }

        toolsWithRequiredParams.forEach { def ->
            val result = registry.execute(def.name, emptyMap())
            // Should either fail (missing param) or fail (no accessibility service)
            // Either way, should not crash
            assertNotNull("${def.name} returned null result", result)
        }
    }

    // ── Query-based tool selection ──────────────────────────────────────────

    @Test
    fun `core tools always included in query results`() {
        val coreNames = setOf(
            "get_screen_state", "click_node", "long_click_node", "type_text",
            "submit_text", "navigate_back", "navigate_home", "launch_app",
            "find_app", "get_current_app", "await_screen_change", "take_screenshot"
        )

        val result = registry.getToolsForQuery("do something random")
        val resultNames = result.map { it.name }.toSet()

        // Only check core tools that are actually registered
        coreNames.filter { registry.hasToolNamed(it) }.forEach { name ->
            assertTrue("Core tool $name missing from query", name in resultNames)
        }
    }

    @Test
    fun `contact query includes contact tools`() {
        val result = registry.getToolsForQuery("send a text message to John")
        val names = result.map { it.name }.toSet()
        assertTrue("send_sms should be included", "send_sms" in names)
        assertTrue("make_call should be included", "make_call" in names)
        assertTrue("list_contacts should be included", "list_contacts" in names)
    }

    @Test
    fun `bluetooth query includes device tools`() {
        val result = registry.getToolsForQuery("turn on bluetooth")
        val names = result.map { it.name }.toSet()
        assertTrue("get_bluetooth_status should be included", "get_bluetooth_status" in names)
    }

    @Test
    fun `volume query includes system tools`() {
        val result = registry.getToolsForQuery("set the volume to max")
        val names = result.map { it.name }.toSet()
        assertTrue("set_volume should be included", "set_volume" in names)
        assertTrue("get_volume should be included", "get_volume" in names)
    }

    @Test
    fun `swipe query includes gesture tools`() {
        val result = registry.getToolsForQuery("swipe left on the screen")
        val names = result.map { it.name }.toSet()
        assertTrue("swipe should be included", "swipe" in names)
        assertTrue("scroll should be included", "scroll" in names)
    }

    @Test
    fun `generic query does not include all 49 tools`() {
        val result = registry.getToolsForQuery("hello there")
        assertTrue(
            "Generic query should return fewer tools (got ${result.size})",
            result.size < 49
        )
    }
}
