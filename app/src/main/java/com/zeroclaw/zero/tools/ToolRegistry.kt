package com.zeroclaw.zero.tools

import android.content.Context
import android.util.Log

private const val TAG = "ToolRegistry"

class ToolRegistry(private val context: Context) {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool): ToolRegistry {
        tools[tool.definition.name] = tool
        Log.d(TAG, "Registered: ${tool.definition.name} [${tool.definition.category}]")
        return this
    }

    fun unregister(name: String) { tools.remove(name) }

    fun execute(name: String, params: Map<String, Any?> = emptyMap()): ToolResult {
        val tool = tools[name] ?: return ToolResult(false, error = "Unknown tool: $name")
        return try {
            tool.execute(params)
        } catch (e: Throwable) {
            Log.e(TAG, "Tool '$name' threw: ${e.message}", e)
            ToolResult(false, error = "Tool error: ${e.message}")
        }
    }

    fun getDefinitions(): List<ToolDefinition> =
        tools.values.map { it.definition }.sortedWith(compareBy({ it.category }, { it.name }))

    fun getDefinition(name: String): ToolDefinition? = tools[name]?.definition
    fun hasToolNamed(name: String): Boolean = tools.containsKey(name)
    fun toolCount(): Int = tools.size

    /**
     * Returns only the tool definitions relevant to [query].
     * Always includes the core set (screen + app control = ~10 tools, covers 80% of tasks).
     * Extra categories are added only when the query contains matching keywords.
     * Keeps per-request token overhead at ~10-15 defs instead of 38.
     */
    fun getToolsForQuery(query: String): List<ToolDefinition> {
        val q = query.lowercase()

        val core = setOf(
            "get_screen_state",
            "click_node",
            "long_click_node",
            "type_text",
            "submit_text",
            "navigate_back",
            "navigate_home",
            "launch_app",
            "find_app",
            "get_current_app",
            "await_screen_change",
            "take_screenshot"
        )

        val contactKeywords   = listOf("contact", "call", "sms", "text", "message", "phone number", "dial")
        val deviceKeywords    = listOf("bluetooth", "flashlight", "torch", "ringer", "silent", "vibrate",
                                       "rotate", "rotation", "location", "gps", "dnd", "do not disturb")
        val systemKeywords    = listOf("brightness", "volume", "wifi", "wi-fi", "screen timeout",
                                       "airplane", "hotspot", "battery", "notification")
        val gestureKeywords   = listOf("swipe", "scroll", "long tap", "long press", "gesture", "drag")
        val screenKeywords    = listOf("screenshot", "screen", "layout", "ui", "element", "find element",
                                       "wait for", "status", "device status")

        val wanted = core.toMutableSet()

        if (contactKeywords.any { q.contains(it) })  addCategory("contacts", wanted)
        if (deviceKeywords.any  { q.contains(it) })  addCategory("device",   wanted)
        if (systemKeywords.any  { q.contains(it) })  addCategory("system",   wanted)
        if (gestureKeywords.any { q.contains(it) })  addCategory("gesture",  wanted)
        if (screenKeywords.any  { q.contains(it) })  addCategory("screen",   wanted)

        val result = tools.values
            .filter { it.definition.name in wanted }
            .map { it.definition }
            .sortedWith(compareBy({ it.category }, { it.name }))

        Log.d(TAG, "getToolsForQuery: ${result.size}/${tools.size} tools selected for query")
        return result
    }

    private fun addCategory(category: String, target: MutableSet<String>) {
        tools.values
            .filter { it.definition.category == category }
            .forEach { target.add(it.definition.name) }
    }
}
