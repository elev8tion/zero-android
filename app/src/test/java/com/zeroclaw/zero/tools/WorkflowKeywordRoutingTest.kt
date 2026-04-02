package com.zeroclaw.zero.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.data.T0gglesClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that workflow keyword routing in ToolRegistry correctly
 * includes/excludes T0ggles proxy tools based on query content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WorkflowKeywordRoutingTest {

    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPrefs(context)
        val client = T0gglesClient(prefs)

        registry = ToolRegistry(context).apply {
            // Register a few core tools so getToolsForQuery has a baseline
            register(GetScreenStateTool())
            register(ClickNodeTool())
            register(LaunchAppTool(context))

            // Register fake workflow proxy tools (simulate what discovery would create)
            val listTasksSchema = JsonParser.parseString("""
                {
                    "type": "object",
                    "properties": {
                        "boardId": { "type": "string", "description": "Board ID" }
                    },
                    "required": ["boardId"]
                }
            """).asJsonObject

            val createNoteSchema = JsonParser.parseString("""
                {
                    "type": "object",
                    "properties": {
                        "boardId": { "type": "string", "description": "Board ID" },
                        "title":   { "type": "string", "description": "Note title" }
                    },
                    "required": ["boardId", "title"]
                }
            """).asJsonObject

            val updateTaskSchema = JsonParser.parseString("""
                {
                    "type": "object",
                    "properties": {
                        "boardId": { "type": "string", "description": "Board ID" },
                        "taskId":  { "type": "string", "description": "Task ID" },
                        "status":  { "type": "string", "description": "New status" }
                    },
                    "required": ["boardId", "taskId"]
                }
            """).asJsonObject

            register(T0gglesProxyTool(client, "list-tasks", "List tasks", listTasksSchema, prefs))
            register(T0gglesProxyTool(client, "create-note", "Create a note", createNoteSchema, prefs))
            register(T0gglesProxyTool(client, "update-task", "Update a task", updateTaskSchema, prefs))
        }
    }

    // ── Workflow keywords trigger inclusion ───────────────────────────────────

    @Test
    fun `task query includes workflow tools`() {
        val result = registry.getToolsForQuery("what tasks are on the board")
        val names = result.map { it.name }.toSet()

        assertTrue("wf_list_tasks should be included", "wf_list_tasks" in names)
        assertTrue("wf_create_note should be included", "wf_create_note" in names)
        assertTrue("wf_update_task should be included", "wf_update_task" in names)
    }

    @Test
    fun `board query includes workflow tools`() {
        val result = registry.getToolsForQuery("show me the board")
        val names = result.map { it.name }.toSet()
        assertTrue("wf_list_tasks should be included", "wf_list_tasks" in names)
    }

    @Test
    fun `error query includes workflow tools`() {
        val result = registry.getToolsForQuery("log an error for bluetooth timeout")
        val names = result.map { it.name }.toSet()
        assertTrue("wf_create_note should be included", "wf_create_note" in names)
    }

    @Test
    fun `standup query includes workflow tools`() {
        val result = registry.getToolsForQuery("give me the standup report")
        val names = result.map { it.name }.toSet()
        assertTrue("wf_list_tasks should be included", "wf_list_tasks" in names)
    }

    @Test
    fun `overdue query includes workflow tools`() {
        val result = registry.getToolsForQuery("what tasks are overdue")
        val names = result.map { it.name }.toSet()
        assertTrue("wf_list_tasks should be included", "wf_list_tasks" in names)
    }

    @Test
    fun `t0ggles query includes workflow tools`() {
        val result = registry.getToolsForQuery("check t0ggles for updates")
        val names = result.map { it.name }.toSet()
        assertTrue("wf_list_tasks should be included", "wf_list_tasks" in names)
    }

    // ── Non-workflow queries exclude workflow tools ───────────────────────────

    @Test
    fun `hello query does not include workflow tools`() {
        val result = registry.getToolsForQuery("hello")
        val names = result.map { it.name }.toSet()

        assertFalse("wf_list_tasks should NOT be included", "wf_list_tasks" in names)
        assertFalse("wf_create_note should NOT be included", "wf_create_note" in names)
        assertFalse("wf_update_task should NOT be included", "wf_update_task" in names)
    }

    @Test
    fun `tap query does not include workflow tools`() {
        val result = registry.getToolsForQuery("tap on the button")
        val names = result.map { it.name }.toSet()
        assertFalse("wf_list_tasks should NOT be included", "wf_list_tasks" in names)
    }

    // ── Workflow tool properties ──────────────────────────────────────────────

    @Test
    fun `all workflow tools have wf_ prefix`() {
        val workflowTools = registry.getDefinitions().filter { it.category == "workflow" }
        workflowTools.forEach { def ->
            assertTrue("${def.name} should start with wf_", def.name.startsWith("wf_"))
        }
    }

    @Test
    fun `all workflow tools have workflow category`() {
        val wfTools = registry.getDefinitions().filter { it.name.startsWith("wf_") }
        wfTools.forEach { def ->
            assertEquals("${def.name} should be in workflow category", "workflow", def.category)
        }
    }

    @Test
    fun `workflow tool names are valid snake_case`() {
        val workflowTools = registry.getDefinitions().filter { it.category == "workflow" }
        workflowTools.forEach { def ->
            assertTrue(
                "${def.name} is not snake_case",
                def.name.matches(Regex("^[a-z][a-z0-9_]*$"))
            )
        }
    }
}
