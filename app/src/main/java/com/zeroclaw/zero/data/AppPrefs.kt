package com.zeroclaw.zero.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent app preferences.
 *
 * Stores:
 *  - proxyUrl     : the claude-session-proxy Ollama endpoint on the Mac
 *  - lastResponse : last assistant text, used by the home-screen widget
 *  - t0gglesApiKey, t0gglesBoardId, t0gglesProjectId, t0gglesUrl : T0ggles MCP workflow integration
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zero_prefs", Context.MODE_PRIVATE)

    /** Full URL of the session proxy (http://MAC_IP:11435). */
    var proxyUrl: String
        get() = prefs.getString(KEY_PROXY_URL, DEFAULT_PROXY_URL) ?: DEFAULT_PROXY_URL
        set(value) = prefs.edit().putString(KEY_PROXY_URL, value).apply()

    /** Last assistant response — shown in the widget. */
    var lastResponse: String?
        get() = prefs.getString(KEY_LAST_RESPONSE, null)
        set(value) = prefs.edit().putString(KEY_LAST_RESPONSE, value).apply()

    /** T0ggles MCP API key for workflow tool authentication. */
    var t0gglesApiKey: String
        get() = prefs.getString(KEY_T0GGLES_API_KEY, DEFAULT_T0GGLES_API_KEY) ?: DEFAULT_T0GGLES_API_KEY
        set(value) = prefs.edit().putString(KEY_T0GGLES_API_KEY, value).apply()

    /** T0ggles board ID — auto-injected into workflow tool calls. */
    var t0gglesBoardId: String
        get() = prefs.getString(KEY_T0GGLES_BOARD_ID, DEFAULT_T0GGLES_BOARD_ID) ?: DEFAULT_T0GGLES_BOARD_ID
        set(value) = prefs.edit().putString(KEY_T0GGLES_BOARD_ID, value).apply()

    /** T0ggles project ID — used by bulk-create-tasks in workflow templates. */
    var t0gglesProjectId: String
        get() = prefs.getString(KEY_T0GGLES_PROJECT_ID, DEFAULT_T0GGLES_PROJECT_ID) ?: DEFAULT_T0GGLES_PROJECT_ID
        set(value) = prefs.edit().putString(KEY_T0GGLES_PROJECT_ID, value).apply()

    /** T0ggles MCP endpoint URL. */
    var t0gglesUrl: String
        get() = prefs.getString(KEY_T0GGLES_URL, DEFAULT_T0GGLES_URL) ?: DEFAULT_T0GGLES_URL
        set(value) = prefs.edit().putString(KEY_T0GGLES_URL, value).apply()

    /** Whether periodic T0ggles task checks are enabled. */
    var scheduledTasksEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHEDULED_TASKS_ENABLED, DEFAULT_SCHEDULED_TASKS_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_SCHEDULED_TASKS_ENABLED, value).apply()

    /** Interval in hours between scheduled task checks. */
    var scheduledTaskIntervalHours: Int
        get() = prefs.getInt(KEY_SCHEDULED_TASK_INTERVAL, DEFAULT_SCHEDULED_TASK_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_SCHEDULED_TASK_INTERVAL, value).apply()

    /** Whether the scheduler should auto-execute the top suggested task. */
    var scheduledAutoExecute: Boolean
        get() = prefs.getBoolean(KEY_SCHEDULED_AUTO_EXECUTE, DEFAULT_SCHEDULED_AUTO_EXECUTE)
        set(value) = prefs.edit().putBoolean(KEY_SCHEDULED_AUTO_EXECUTE, value).apply()

    companion object {
        private const val KEY_PROXY_URL       = "proxy_url"
        private const val KEY_LAST_RESPONSE   = "last_response"
        private const val KEY_T0GGLES_API_KEY  = "t0ggles_api_key"
        private const val KEY_T0GGLES_BOARD_ID = "t0ggles_board_id"
        private const val KEY_T0GGLES_PROJECT_ID = "t0ggles_project_id"
        private const val KEY_T0GGLES_URL      = "t0ggles_url"
        private const val KEY_SCHEDULED_TASKS_ENABLED  = "scheduled_tasks_enabled"
        private const val KEY_SCHEDULED_TASK_INTERVAL   = "scheduled_task_interval_hours"
        private const val KEY_SCHEDULED_AUTO_EXECUTE    = "scheduled_auto_execute"

        const val DEFAULT_PROXY_URL           = "http://127.0.0.1:11435"
        const val DEFAULT_T0GGLES_API_KEY     = "t0mcp_7I7tsurzGMBjOWJvARFHSfyAG0hFN4Oy"
        const val DEFAULT_T0GGLES_BOARD_ID    = "3asoYa8WGR9whoNU1flF"
        const val DEFAULT_T0GGLES_PROJECT_ID  = "wrFqBDcWtAc9uZ1LmH4b"
        const val DEFAULT_T0GGLES_URL         = "https://t0ggles.com/mcp"
        const val DEFAULT_SCHEDULED_TASKS_ENABLED  = false
        const val DEFAULT_SCHEDULED_TASK_INTERVAL  = 1
        const val DEFAULT_SCHEDULED_AUTO_EXECUTE   = false
    }
}
