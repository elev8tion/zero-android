package com.zeroclaw.zero.tools

import com.zeroclaw.zero.accessibility.ZeroAccessibilityService

class PressBackTool : Tool {
    override val definition = ToolDefinition(
        name = "press_back",
        description = "Press the system Back button",
        category = "navigation",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val ok = ZeroAccessibilityService.pressBack()
        return if (ok) ToolResult(true, "Back pressed") else ToolResult(false, error = "Failed to press Back")
    }
}

class PressHomeTool : Tool {
    override val definition = ToolDefinition(
        name = "press_home",
        description = "Press the system Home button",
        category = "navigation",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val ok = ZeroAccessibilityService.pressHome()
        return if (ok) ToolResult(true, "Home pressed") else ToolResult(false, error = "Failed to press Home")
    }
}

class PressRecentsTool : Tool {
    override val definition = ToolDefinition(
        name = "press_recents",
        description = "Open the recent apps switcher",
        category = "navigation",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val ok = ZeroAccessibilityService.pressRecents()
        return if (ok) ToolResult(true, "Recents opened") else ToolResult(false, error = "Failed to open Recents")
    }
}

class FindEditableAndTypeTool : Tool {
    override val definition = ToolDefinition(
        name = "find_editable_and_type",
        description = "Find the first editable field on screen, focus it, and type text",
        category = "navigation",
        params = mapOf(
            "text" to ToolParam("string", "Text to type into the field")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val text = params.getString("text") ?: return ToolResult(false, error = "Missing param: text")
        val ok = ZeroAccessibilityService.findEditableAndType(text)
        return if (ok) ToolResult(true, "Typed into editable field: ${text.length} chars")
        else ToolResult(false, error = "No editable field found")
    }
}
