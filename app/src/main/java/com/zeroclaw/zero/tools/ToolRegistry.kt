package com.zeroclaw.zero.tools

import android.content.Context
import android.util.Log
import com.zeroclaw.zero.data.ErrorDatabase

private const val TAG = "ToolRegistry"

class ToolRegistry(private val context: Context) {
    private val tools = mutableMapOf<String, Tool>()

    /** Set by ZeroApp after init to enable error logging. */
    var errorDatabase: ErrorDatabase? = null

    fun register(tool: Tool): ToolRegistry {
        tools[tool.definition.name] = tool
        Log.d(TAG, "Registered: ${tool.definition.name} [${tool.definition.category}]")
        return this
    }

    fun unregister(name: String) { tools.remove(name) }

    fun execute(name: String, params: Map<String, Any?> = emptyMap()): ToolResult {
        val tool = tools[name] ?: return ToolResult(false, error = "Unknown tool: $name").also {
            logError(name, params, it.error ?: "Unknown tool")
        }
        return try {
            val result = tool.execute(params)
            if (!result.success) {
                logError(name, params, result.error ?: "Unknown error")
            }
            result
        } catch (e: Throwable) {
            Log.e(TAG, "Tool '$name' threw: ${e.message}", e)
            val error = "Tool error: ${e.message}"
            logError(name, params, error)
            ToolResult(false, error = error)
        }
    }

    /**
     * Log a tool error to the ErrorDatabase.
     * Skips wf_* tools and query_error_log to prevent infinite recursion
     * (logging an error could trigger a T0ggles call, which could fail, triggering another log...).
     */
    private fun logError(name: String, params: Map<String, Any?>, error: String) {
        val db = errorDatabase ?: return
        // Skip workflow proxy tools and the error log tool itself to prevent recursion
        val skipT0ggles = name.startsWith("wf_") || name == "query_error_log"
        db.recordError(name, params, error, logToT0ggles = !skipT0ggles)
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
        val workflowKeywords  = listOf("task", "workflow", "todo", "backlog", "board",
                                       "milestone", "note", "error", "log", "create task",
                                       "update task", "next task", "standup", "report",
                                       "workload", "overdue", "blocked", "dependency",
                                       "comment", "status", "t0ggles", "toggles", "project",
                                       "template", "pipeline", "deploy", "triage")

        val wanted = core.toMutableSet()

        if (contactKeywords.any  { q.contains(it) })  addCategory("contacts", wanted)
        if (deviceKeywords.any   { q.contains(it) })  addCategory("device",   wanted)
        if (systemKeywords.any   { q.contains(it) })  addCategory("system",   wanted)
        if (gestureKeywords.any  { q.contains(it) })  addCategory("gesture",  wanted)
        if (screenKeywords.any   { q.contains(it) })  addCategory("screen",   wanted)
        if (workflowKeywords.any { q.contains(it) })  addCategory("workflow", wanted)

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
