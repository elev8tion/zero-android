package com.zeroclaw.zero.tools

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test verifying all 22 accessibility-dependent tools return clean
 * error messages when the accessibility service is not running.
 *
 * On a real device, ZeroAccessibilityService.isRunning will be false unless
 * the user has explicitly enabled it in Settings. These tests confirm no NPEs,
 * no crashes, and user-friendly error strings.
 */
@RunWith(AndroidJUnit4::class)
class GracefulDegradationTest {

    private lateinit var registry: ToolRegistry

    /** All 22 tools that check ZeroAccessibilityService.isRunning */
    private val accessibilityTools = listOf(
        // Gesture (9)
        "tap", "swipe", "scroll", "type_text", "clear_text",
        "find_and_click", "find_and_click_by_id", "long_tap_screen", "submit_text",
        // Navigation (4)
        "press_back", "press_home", "press_recents", "show_all_apps",
        "find_editable_and_type",
        // Screen (8 — excludes get_device_status which works without service)
        "get_screen_state", "get_screen_content", "get_screen_json",
        "click_node", "long_click_node",
        "find_element", "wait_for_element", "await_screen_change",
        "take_screenshot"
    )

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        registry = ToolRegistry(context).apply {
            // Gestures
            register(TapTool())
            register(LongTapScreenTool())
            register(SwipeTool())
            register(ScrollTool())
            register(TypeTextTool())
            register(SubmitTextTool())
            register(ClearTextTool())
            register(FindAndClickTool())
            register(FindAndClickByIdTool())
            // Navigation
            register(PressBackTool())
            register(PressHomeTool())
            register(PressRecentsTool())
            register(ShowAllAppsTool())
            register(FindEditableAndTypeTool())
            // Screen
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
        }
    }

    @Test
    fun allAccessibilityToolsReturnSuccessFalse() {
        for (name in accessibilityTools) {
            val result = registry.execute(name, emptyMap())
            assertFalse(
                "$name should return success=false without accessibility service",
                result.success
            )
        }
    }

    @Test
    fun allAccessibilityToolsReturnAccessibilityServiceError() {
        for (name in accessibilityTools) {
            val result = registry.execute(name, emptyMap())
            assertNotNull("$name error should not be null", result.error)
            assertTrue(
                "$name error should mention accessibility service, got: ${result.error}",
                result.error!!.contains("Accessibility service not running", ignoreCase = true)
            )
        }
    }

    @Test
    fun noAccessibilityToolThrowsException() {
        for (name in accessibilityTools) {
            try {
                registry.execute(name, emptyMap())
            } catch (e: Exception) {
                fail("$name threw ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    @Test
    fun errorMessagesAreUserFriendlyStrings() {
        for (name in accessibilityTools) {
            val result = registry.execute(name, emptyMap())
            val error = result.error ?: ""
            // Should not contain stack trace markers
            assertFalse(
                "$name error looks like a stack trace: $error",
                error.contains("at com.") || error.contains("Exception:")
            )
        }
    }

    @Test
    fun getDeviceStatusWorksWithoutAccessibilityService() {
        val result = registry.execute("get_device_status", emptyMap())
        assertTrue("get_device_status should succeed", result.success)
        assertTrue(
            "Should contain service_running",
            result.data.contains("service_running")
        )
        assertTrue(
            "service_running should be false",
            result.data.contains("false")
        )
    }
}
