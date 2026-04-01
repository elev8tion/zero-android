package com.zeroclaw.zero.tools

import com.zeroclaw.zero.accessibility.ZeroAccessibilityService

class TapTool : Tool {
    override val definition = ToolDefinition(
        name = "tap",
        description = "Tap at absolute screen coordinates (pixels)",
        category = "gesture",
        params = mapOf(
            "x" to ToolParam("float", "X coordinate in pixels"),
            "y" to ToolParam("float", "Y coordinate in pixels")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val x = params.getFloat("x") ?: return ToolResult(false, error = "Missing param: x")
        val y = params.getFloat("y") ?: return ToolResult(false, error = "Missing param: y")
        val ok = ZeroAccessibilityService.tap(x, y)
        return if (ok) ToolResult(true, "Tapped ($x, $y)")
        else ToolResult(false, error = "Tap gesture failed")
    }
}

class SwipeTool : Tool {
    override val definition = ToolDefinition(
        name = "swipe",
        description = "Swipe from one screen coordinate to another",
        category = "gesture",
        params = mapOf(
            "x1" to ToolParam("float", "Start X in pixels"),
            "y1" to ToolParam("float", "Start Y in pixels"),
            "x2" to ToolParam("float", "End X in pixels"),
            "y2" to ToolParam("float", "End Y in pixels"),
            "duration_ms" to ToolParam("int", "Swipe duration in milliseconds (default 300)", required = false)
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val x1 = params.getFloat("x1") ?: return ToolResult(false, error = "Missing param: x1")
        val y1 = params.getFloat("y1") ?: return ToolResult(false, error = "Missing param: y1")
        val x2 = params.getFloat("x2") ?: return ToolResult(false, error = "Missing param: x2")
        val y2 = params.getFloat("y2") ?: return ToolResult(false, error = "Missing param: y2")
        val dur = params.getInt("duration_ms")?.toLong() ?: 300L
        val ok = ZeroAccessibilityService.swipe(x1, y1, x2, y2, dur)
        return if (ok) ToolResult(true, "Swiped ($x1,$y1) → ($x2,$y2) in ${dur}ms")
        else ToolResult(false, error = "Swipe gesture failed")
    }
}

class ScrollTool : Tool {
    override val definition = ToolDefinition(
        name = "scroll",
        description = "Scroll the screen in a direction (up, down, left, right)",
        category = "gesture",
        params = mapOf(
            "direction" to ToolParam("string", "Direction: up | down | left | right")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val dir = params.getString("direction") ?: return ToolResult(false, error = "Missing param: direction")
        val ok = ZeroAccessibilityService.scroll(dir)
        return if (ok) ToolResult(true, "Scrolled $dir")
        else ToolResult(false, error = "Scroll failed for direction: $dir")
    }
}

class TypeTextTool : Tool {
    override val definition = ToolDefinition(
        name = "type_text",
        description = "Type text into the focused or first editable field",
        category = "gesture",
        params = mapOf(
            "text" to ToolParam("string", "Text to type")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val text = params.getString("text") ?: return ToolResult(false, error = "Missing param: text")
        val ok = ZeroAccessibilityService.typeText(text)
        return if (ok) ToolResult(true, "Typed text (${text.length} chars)")
        else ToolResult(false, error = "No editable field found or action failed")
    }
}

class ClearTextTool : Tool {
    override val definition = ToolDefinition(
        name = "clear_text",
        description = "Clear text from the focused or first editable field",
        category = "gesture",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val ok = ZeroAccessibilityService.clearText()
        return if (ok) ToolResult(true, "Text cleared")
        else ToolResult(false, error = "No editable field found or action failed")
    }
}

class FindAndClickTool : Tool {
    override val definition = ToolDefinition(
        name = "find_and_click",
        description = "Find a UI element by visible text or content description and click it",
        category = "gesture",
        params = mapOf(
            "text" to ToolParam("string", "Text to search for (case-insensitive, partial match)")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val text = params.getString("text") ?: return ToolResult(false, error = "Missing param: text")
        val ok = ZeroAccessibilityService.findAndClick(text)
        return if (ok) ToolResult(true, "Clicked element with text: $text")
        else ToolResult(false, error = "Element not found: $text")
    }
}

class FindAndClickByIdTool : Tool {
    override val definition = ToolDefinition(
        name = "find_and_click_by_id",
        description = "Find a UI element by its resource ID and click it",
        category = "gesture",
        params = mapOf(
            "resource_id" to ToolParam("string", "Resource ID or partial ID (e.g. 'btn_submit' or 'com.app:id/btn_submit')")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        if (!ZeroAccessibilityService.isRunning)
            return ToolResult(false, error = "Accessibility service not running")
        val id = params.getString("resource_id") ?: return ToolResult(false, error = "Missing param: resource_id")
        val ok = ZeroAccessibilityService.findAndClickByResourceId(id)
        return if (ok) ToolResult(true, "Clicked element with id: $id")
        else ToolResult(false, error = "Element not found with id: $id")
    }
}
