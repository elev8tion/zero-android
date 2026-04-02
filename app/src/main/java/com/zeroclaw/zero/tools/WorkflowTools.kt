package com.zeroclaw.zero.tools

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.data.T0gglesClient
import com.zeroclaw.zero.data.WorkflowTemplates

/**
 * Lists all available workflow templates.
 * Pure local — no network required.
 */
class ListWorkflowsTool : Tool {

    private val gson = Gson()

    override val definition = ToolDefinition(
        name = "list_workflows",
        description = "List available workflow templates that can be run to bulk-create task pipelines.",
        category = "workflow",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val summaries = WorkflowTemplates.list().map { t ->
            mapOf(
                "id" to t.id,
                "displayName" to t.displayName,
                "description" to t.description,
                "stepCount" to t.steps.size
            )
        }
        return ToolResult(true, data = gson.toJson(summaries))
    }
}

/**
 * Executes a workflow template: bulk-creates tasks in T0ggles
 * with sequential dependencies forming a pipeline.
 */
class RunWorkflowTool(
    private val client: T0gglesClient,
    private val prefs: AppPrefs
) : Tool {

    private val gson = Gson()

    override val definition = ToolDefinition(
        name = "run_workflow",
        description = "Execute a workflow template: bulk-creates tasks with sequential dependencies. " +
            "Use list_workflows to see available templates.",
        category = "workflow",
        params = mapOf(
            "template" to ToolParam(
                "string",
                "Template ID (e.g. deploy_and_test, bug_triage, new_tool)",
                required = true
            ),
            "prefix" to ToolParam(
                "string",
                "Optional prefix for task titles (e.g. 'DEMO-20: ')",
                required = false
            )
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val templateId = params.getString("template")
            ?: return ToolResult(false, error = "Missing required param: template")
        val prefix = params.getString("prefix") ?: ""

        val template = WorkflowTemplates.get(templateId)
            ?: return ToolResult(
                false,
                error = "Unknown template: $templateId. Use list_workflows to see available options."
            )

        // 1. Bulk-create all tasks
        val taskDefs = template.steps.map { step ->
            val task = mutableMapOf<String, Any>(
                "title" to "$prefix${step.title}",
                "description" to step.description,
                "priority" to step.priority
            )
            if (step.tags.isNotEmpty()) {
                task["tags"] = step.tags
            }
            task
        }

        val bulkResult = client.callTool("bulk-create-tasks", mapOf(
            "boardId" to prefs.t0gglesBoardId,
            "projectId" to prefs.t0gglesProjectId,
            "tasks" to taskDefs
        ))
        if (!bulkResult.success) return bulkResult

        // 2. Parse created task IDs from the response
        val taskIds = parseTaskIds(bulkResult.data)
        if (taskIds.size < 2) {
            return ToolResult(
                true,
                data = "Created ${taskIds.size} task(s) from '${template.displayName}' " +
                    "(not enough tasks for dependency chaining)"
            )
        }

        // 3. Create sequential dependencies: task1 → task2 → task3 → ...
        var depErrors = 0
        for (i in 0 until taskIds.size - 1) {
            val depResult = client.callTool("create-dependency", mapOf(
                "boardId" to prefs.t0gglesBoardId,
                "predecessorTaskId" to taskIds[i],
                "successorTaskId" to taskIds[i + 1]
            ))
            if (!depResult.success) depErrors++
        }

        val summary = buildString {
            append("Created ${taskIds.size} tasks from '${template.displayName}' with sequential dependencies")
            if (depErrors > 0) {
                append(" ($depErrors dependency creation(s) failed)")
            }
        }
        return ToolResult(true, data = summary)
    }

    companion object {
        /**
         * Extracts task IDs from the bulk-create-tasks response.
         * The response is JSON text from T0ggles — typically an array of objects
         * each containing an "id" field, or a wrapper with a "tasks" array.
         */
        fun parseTaskIds(responseData: String): List<String> {
            return try {
                val element = JsonParser.parseString(responseData)
                when {
                    // Direct array: [{"id":"abc"}, {"id":"def"}]
                    element.isJsonArray -> {
                        element.asJsonArray.mapNotNull { item ->
                            item.asJsonObject?.get("id")?.asString
                        }
                    }
                    // Wrapper object: {"tasks":[{"id":"abc"}, ...]}
                    element.isJsonObject -> {
                        val obj = element.asJsonObject
                        val tasksArr = obj.getAsJsonArray("tasks")
                            ?: obj.getAsJsonArray("created")
                            ?: return emptyList()
                        tasksArr.mapNotNull { item ->
                            item.asJsonObject?.get("id")?.asString
                        }
                    }
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
