package com.zeroclaw.zero.tools

import com.zeroclaw.zero.accessibility.ZeroAccessibilityService

class GetScreenContentTool : Tool {
    override val definition = ToolDefinition(
        name = "get_screen_content",
        description = "Get a text summary of visible UI elements on screen (top 50, clickable/editable prioritized)",
        category = "screen",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val content = ZeroAccessibilityService.getScreenContent()
        val summary = content.summary()
        return ToolResult(true, "pkg=${content.packageName}\n$summary")
    }
}

class GetScreenStateTool : Tool {
    override val definition = ToolDefinition(
        name = "get_screen_state",
        description = "Read the current UI hierarchy as structured HTML-like nodes with nid attributes. " +
            "Each node has: nid (use with click_node/long_click_node), txt, dsc, b (bounds), " +
            "and flags (clickable, editable, scrollable, focused, checked). " +
            "Preferred over get_screen_content for interactive tasks.",
        category = "screen",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        return ToolResult(true, ZeroAccessibilityService.getScreenState())
    }
}

class GetDeviceStatusTool : Tool {
    override val definition = ToolDefinition(
        name = "get_device_status",
        description = "Check whether the accessibility service is running and ready",
        category = "screen",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val running = ZeroAccessibilityService.isRunning
        return ToolResult(true, """{"service_running":$running}""")
    }
}

class GetScreenJsonTool : Tool {
    override val definition = ToolDefinition(
        name = "get_screen_json",
        description = "Get full JSON dump of all accessibility nodes currently on screen",
        category = "screen",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val content = ZeroAccessibilityService.getScreenContent()
        return ToolResult(true, content.toJson())
    }
}

class ClickNodeTool : Tool {
    override val definition = ToolDefinition(
        name = "click_node",
        description = "Click a UI element by its nid from get_screen_state output. " +
            "Preferred over tap_screen for reliability since it uses the accessibility action directly.",
        category = "screen",
        params = mapOf(
            "nid" to ToolParam("string", "Node ID from get_screen_state (e.g. \"n5\")")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val nid = params.getString("nid") ?: return ToolResult(false, error = "Missing param: nid")
        val ok = ZeroAccessibilityService.clickByNid(nid)
        return if (ok) ToolResult(true, "Clicked node $nid")
        else ToolResult(false, error = "Node $nid not found — call get_screen_state first")
    }
}

class LongClickNodeTool : Tool {
    override val definition = ToolDefinition(
        name = "long_click_node",
        description = "Long-click (press and hold) a UI element by its nid from get_screen_state",
        category = "screen",
        params = mapOf(
            "nid" to ToolParam("string", "Node ID from get_screen_state (e.g. \"n5\")")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val nid = params.getString("nid") ?: return ToolResult(false, error = "Missing param: nid")
        val ok = ZeroAccessibilityService.longClickByNid(nid)
        return if (ok) ToolResult(true, "Long-clicked node $nid")
        else ToolResult(false, error = "Node $nid not found — call get_screen_state first")
    }
}

class LongTapScreenTool : Tool {
    override val definition = ToolDefinition(
        name = "long_tap_screen",
        description = "Long-press at screen coordinates. Use for context menus or long-tap actions.",
        category = "screen",
        params = mapOf(
            "x" to ToolParam("int", "X coordinate in pixels"),
            "y" to ToolParam("int", "Y coordinate in pixels"),
            "duration_ms" to ToolParam("int", "Hold duration in ms (default 500)", required = false)
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val x = params.getInt("x")?.toFloat() ?: return ToolResult(false, error = "Missing param: x")
        val y = params.getInt("y")?.toFloat() ?: return ToolResult(false, error = "Missing param: y")
        val duration = params.getInt("duration_ms")?.toLong() ?: 500L
        val ok = ZeroAccessibilityService.longTap(x, y, duration)
        return if (ok) ToolResult(true, "Long-tapped ($x, $y) for ${duration}ms")
        else ToolResult(false, error = "Long-tap gesture failed")
    }
}

class SubmitTextTool : Tool {
    override val definition = ToolDefinition(
        name = "submit_text",
        description = "Press Enter / IME action on the currently focused input field. Use after type_text.",
        category = "screen",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val ok = ZeroAccessibilityService.submitText()
        return if (ok) ToolResult(true, "Submit action performed")
        else ToolResult(false, error = "No focused input field found")
    }
}

class ShowAllAppsTool : Tool {
    override val definition = ToolDefinition(
        name = "show_all_apps",
        description = "Open the app drawer showing all installed apps",
        category = "screen",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val ok = ZeroAccessibilityService.showAllApps()
        return if (ok) ToolResult(true, "App drawer opened")
        else ToolResult(false, error = "Failed to open app drawer")
    }
}

class AwaitScreenChangeTool : Tool {
    override val definition = ToolDefinition(
        name = "await_screen_change",
        description = "Wait for the screen content to change. Returns when new content is detected or timeout expires. " +
            "Use after async actions like sending messages, tapping buttons, loading pages.",
        category = "screen",
        params = mapOf(
            "timeout_ms" to ToolParam("int", "Max wait time in milliseconds (default 5000)", required = false)
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val timeout = params.getInt("timeout_ms")?.toLong() ?: 5000L
        val changed = ZeroAccessibilityService.awaitScreenChange(timeout)
        return ToolResult(true, """{"changed":$changed,"timeout":${!changed}}""")
    }
}

class TakeScreenshotTool : Tool {
    override val definition = ToolDefinition(
        name = "take_screenshot",
        description = "Capture a screenshot as a base64-encoded JPEG image. Requires Android 11+.",
        category = "screen",
        params = mapOf(
            "quality" to ToolParam("int", "JPEG quality 10-100 (default 80)", required = false)
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val quality = params.getInt("quality") ?: 80
        val base64 = ZeroAccessibilityService.takeScreenshot(quality)
            ?: return ToolResult(false, error = "Screenshot failed — requires Android 11+")
        return ToolResult(true, base64)
    }
}

class FindElementTool : Tool {
    override val definition = ToolDefinition(
        name = "find_element",
        description = "Check if a UI element with the given text exists on screen, return its bounds",
        category = "screen",
        params = mapOf(
            "text" to ToolParam("string", "Text to search for (case-insensitive, partial match)")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val query = params.getString("text") ?: return ToolResult(false, error = "Missing param: text")
        val content = ZeroAccessibilityService.getScreenContent()
        val match = content.elements.firstOrNull { el ->
            el.text?.contains(query, true) == true ||
            el.contentDescription?.contains(query, true) == true
        }
        return if (match != null) {
            ToolResult(true, "Found: text=${match.text ?: match.contentDescription} bounds=${match.bounds} clickable=${match.isClickable}")
        } else {
            ToolResult(false, error = "Element not found: $query")
        }
    }
}

class WaitForElementTool : Tool {
    override val definition = ToolDefinition(
        name = "wait_for_element",
        description = "Poll the screen until a UI element with the given text appears, or timeout",
        category = "screen",
        params = mapOf(
            "text" to ToolParam("string", "Text to wait for"),
            "timeout_ms" to ToolParam("int", "Max wait time in milliseconds (default 5000)", required = false),
            "interval_ms" to ToolParam("int", "Poll interval in milliseconds (default 500)", required = false)
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val query = params.getString("text") ?: return ToolResult(false, error = "Missing param: text")
        val timeout = params.getInt("timeout_ms")?.toLong() ?: 5000L
        val interval = params.getInt("interval_ms")?.toLong() ?: 500L
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val content = ZeroAccessibilityService.getScreenContent()
            val match = content.elements.firstOrNull { el ->
                el.text?.contains(query, true) == true ||
                el.contentDescription?.contains(query, true) == true
            }
            if (match != null) {
                return ToolResult(true, "Found after ${timeout - (deadline - System.currentTimeMillis())}ms: bounds=${match.bounds}")
            }
            Thread.sleep(interval)
        }
        return ToolResult(false, error = "Timed out waiting for element: $query")
    }
}
