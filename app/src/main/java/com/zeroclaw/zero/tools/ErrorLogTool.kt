package com.zeroclaw.zero.tools

import com.google.gson.Gson
import com.zeroclaw.zero.data.ErrorDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local tool that lets Claude search the in-memory error log.
 * No network required — searches the local ring buffer only.
 */
class QueryErrorLogTool(private val errorDb: ErrorDatabase) : Tool {

    private val gson = Gson()

    override val definition = ToolDefinition(
        name = "query_error_log",
        description = "Search recent tool errors by keyword. Returns matching errors with timestamps " +
            "and details. Use this before retrying a tool to check if the same error has occurred before.",
        category = "workflow",
        params = mapOf(
            "query" to ToolParam(
                "string",
                "Search keyword (tool name, error message fragment, etc.). Empty returns all recent errors.",
                required = false
            )
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val query = params.getString("query") ?: ""
        val results = errorDb.searchLocal(query)

        if (results.isEmpty()) {
            return ToolResult(true, data = "No errors found" +
                if (query.isNotBlank()) " matching \"$query\"" else "")
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val entries = results.map { entry ->
            mapOf(
                "timestamp" to dateFormat.format(Date(entry.timestamp)),
                "tool" to entry.toolName,
                "error" to entry.error,
                "params" to entry.params
            )
        }

        return ToolResult(true, data = gson.toJson(entries))
    }
}
