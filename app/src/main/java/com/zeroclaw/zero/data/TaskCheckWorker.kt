package com.zeroclaw.zero.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zeroclaw.zero.R
import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.agent.AgentLoop
import com.zeroclaw.zero.ui.MainActivity

private const val TAG = "TaskCheckWorker"
private const val CHANNEL_ID = "zero_scheduled_tasks"
private const val NOTIF_ID = 2001

/**
 * Periodic WorkManager worker that queries T0ggles for actionable tasks.
 *
 * Flow:
 * 1. Call suggest-next-action to get a prioritized recommendation
 * 2. Fall back to list-overdue-tasks on failure
 * 3. Post a notification if tasks are found
 * 4. Optionally auto-execute the top task via scheduledAgentLoop
 */
class TaskCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val gson = Gson()

    override suspend fun doWork(): Result {
        val app = try {
            ZeroApp.instance
        } catch (e: Exception) {
            Log.w(TAG, "ZeroApp not initialized, retrying later")
            return Result.retry()
        }

        val prefs = app.prefs
        val boardId = prefs.t0gglesBoardId

        Log.d(TAG, "Starting scheduled task check for board=$boardId")

        val checkResult = trySuggestNextAction(app.t0gglesClient, boardId)
            ?: tryListOverdueTasks(app.t0gglesClient, boardId)
            ?: return Result.retry()

        if (checkResult.tasksFound == 0) {
            Log.d(TAG, "No actionable tasks found")
            return Result.success()
        }

        postNotification(checkResult)

        if (prefs.scheduledAutoExecute && !app.scheduledAgentLoop.isRunning) {
            val taskDesc = checkResult.suggestion ?: checkResult.topTaskTitle
            if (taskDesc != null) {
                Log.i(TAG, "Auto-executing: $taskDesc")
                app.scheduledAgentLoop.listener = HeadlessListener()
                app.scheduledAgentLoop.process("Execute: $taskDesc")
            }
        }

        return Result.success()
    }

    private fun trySuggestNextAction(client: T0gglesClient, boardId: String): TaskCheckResult? {
        return try {
            val result = client.callTool("suggest-next-action", mapOf("boardId" to boardId))
            if (!result.success) {
                Log.w(TAG, "suggest-next-action failed: ${result.error}")
                return null
            }
            parseSuggestion(result.data)
        } catch (e: Exception) {
            Log.w(TAG, "suggest-next-action exception: ${e.message}")
            null
        }
    }

    private fun tryListOverdueTasks(client: T0gglesClient, boardId: String): TaskCheckResult? {
        return try {
            val result = client.callTool("list-overdue-tasks", mapOf("boardId" to boardId))
            if (!result.success) {
                Log.w(TAG, "list-overdue-tasks failed: ${result.error}")
                return null
            }
            parseOverdueTasks(result.data)
        } catch (e: Exception) {
            Log.w(TAG, "list-overdue-tasks exception: ${e.message}")
            null
        }
    }

    internal fun parseSuggestion(data: String): TaskCheckResult {
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson<Map<String, Any?>>(
                data, object : TypeToken<Map<String, Any?>>() {}.type
            ) ?: emptyMap()

            val suggestion = map["suggestion"] as? String
                ?: map["recommendation"] as? String
                ?: data.take(200)

            val taskId = map["taskId"] as? String
            val taskTitle = map["taskTitle"] as? String
                ?: map["title"] as? String

            TaskCheckResult(
                tasksFound = if (suggestion.isNotBlank()) 1 else 0,
                topTaskTitle = taskTitle,
                topTaskId = taskId,
                suggestion = suggestion,
                rawResponse = data
            )
        } catch (e: Exception) {
            // Not JSON — treat as plain text suggestion
            TaskCheckResult(
                tasksFound = if (data.isNotBlank()) 1 else 0,
                topTaskTitle = null,
                topTaskId = null,
                suggestion = data.take(200),
                rawResponse = data
            )
        }
    }

    internal fun parseOverdueTasks(data: String): TaskCheckResult {
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson<Map<String, Any?>>(
                data, object : TypeToken<Map<String, Any?>>() {}.type
            ) ?: emptyMap()

            @Suppress("UNCHECKED_CAST")
            val tasks = map["tasks"] as? List<Map<String, Any?>> ?: emptyList()

            val first = tasks.firstOrNull()
            TaskCheckResult(
                tasksFound = tasks.size,
                topTaskTitle = first?.get("title") as? String,
                topTaskId = first?.get("id") as? String,
                suggestion = if (tasks.isNotEmpty())
                    "${tasks.size} overdue task(s). Top: ${first?.get("title") ?: "unknown"}"
                else null,
                rawResponse = data
            )
        } catch (e: Exception) {
            // Not parseable — treat raw text as single task
            TaskCheckResult(
                tasksFound = if (data.isNotBlank()) 1 else 0,
                topTaskTitle = null,
                topTaskId = null,
                suggestion = if (data.isNotBlank()) data.take(200) else null,
                rawResponse = data
            )
        }
    }

    private fun postNotification(result: TaskCheckResult) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Scheduled Tasks",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "T0ggles task check notifications"
                }
            )
        }

        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (result.tasksFound == 1) "1 task needs attention"
                    else "${result.tasksFound} tasks need attention"

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(result.suggestion ?: result.topTaskTitle ?: "Check T0ggles")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(result.suggestion ?: result.topTaskTitle ?: "Check T0ggles"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID, notification)
        Log.i(TAG, "Posted notification: $title")
    }
}

/**
 * Headless listener for background auto-execute — logs results, no UI interaction.
 */
private class HeadlessListener : AgentLoop.Listener {
    override fun onThinking() {
        Log.d(TAG, "[auto-execute] Thinking...")
    }
    override fun onToolCall(name: String) {
        Log.d(TAG, "[auto-execute] Tool call: $name")
    }
    override fun onResponse(text: String) {
        Log.i(TAG, "[auto-execute] Done: ${text.take(120)}")
    }
    override fun onError(message: String) {
        Log.e(TAG, "[auto-execute] Error: $message")
    }
}
