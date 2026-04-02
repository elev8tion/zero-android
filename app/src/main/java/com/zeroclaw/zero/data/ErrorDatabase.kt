package com.zeroclaw.zero.data

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

private const val TAG = "ErrorDatabase"
private const val MAX_ENTRIES = 100

/**
 * Persistent error memory for Zero's tool execution failures.
 *
 * Stores errors in a local ring buffer (always available, no network needed)
 * and fire-and-forget logs them to T0ggles notes for cross-session memory.
 *
 * Error logging NEVER blocks or breaks tool execution.
 */
class ErrorDatabase(
    private val client: T0gglesClient,
    private val prefs: AppPrefs,
    private val scope: CoroutineScope
) {

    data class ErrorEntry(
        val timestamp: Long,
        val toolName: String,
        val params: Map<String, Any?>,
        val error: String
    )

    private val localLog: MutableList<ErrorEntry> = Collections.synchronizedList(
        object : ArrayList<ErrorEntry>() {
            override fun add(element: ErrorEntry): Boolean {
                if (size >= MAX_ENTRIES) removeAt(0)
                return super.add(element)
            }
        }
    )

    /**
     * Record a tool error. Adds to local ring buffer immediately,
     * then fires a non-blocking coroutine to log it to T0ggles.
     *
     * @param toolName  the tool that failed
     * @param params    the parameters passed to the tool
     * @param error     the error message
     * @param logToT0ggles  whether to also log to T0ggles (false for wf_* tools to prevent recursion)
     */
    fun recordError(
        toolName: String,
        params: Map<String, Any?>,
        error: String,
        logToT0ggles: Boolean = true
    ) {
        val entry = ErrorEntry(System.currentTimeMillis(), toolName, params, error)
        localLog.add(entry)
        Log.d(TAG, "Recorded error: [$toolName] $error")

        if (!logToT0ggles) return

        scope.launch {
            try {
                client.callTool("create-note", mapOf(
                    "boardId" to prefs.t0gglesBoardId,
                    "title" to "[ERROR] $toolName: ${error.take(80)}",
                    "content" to formatNoteContent(entry)
                ))
                Log.d(TAG, "Logged error to T0ggles")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log error to T0ggles: ${e.message}")
            }
        }
    }

    /**
     * Search the local error log by keyword.
     * Matches against tool name and error message (case-insensitive).
     * Empty query returns all entries.
     */
    fun searchLocal(query: String): List<ErrorEntry> {
        if (query.isBlank()) return localLog.toList()
        val q = query.lowercase()
        return localLog.filter { entry ->
            entry.toolName.lowercase().contains(q) ||
                entry.error.lowercase().contains(q)
        }
    }

    /** Number of entries currently in the local log. */
    fun size(): Int = localLog.size

    private fun formatNoteContent(entry: ErrorEntry): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(entry.timestamp))
        val paramsStr = if (entry.params.isEmpty()) "none"
        else entry.params.entries.joinToString(", ") { "${it.key}=${it.value}" }

        return buildString {
            appendLine("## Error Details")
            appendLine("- **Tool:** ${entry.toolName}")
            appendLine("- **Error:** ${entry.error}")
            appendLine("- **Params:** $paramsStr")
            appendLine("- **Time:** $dateStr")
            appendLine()
            appendLine("## Device Info")
            appendLine("- **Model:** ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- **Android:** ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }
}
