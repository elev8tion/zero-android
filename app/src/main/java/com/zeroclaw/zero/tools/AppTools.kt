package com.zeroclaw.zero.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

class LaunchAppTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "launch_app",
        description = "Launch an installed app by package name",
        category = "app",
        params = mapOf(
            "package_name" to ToolParam("string", "Package name, e.g. com.android.chrome")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val pkg = params.getString("package_name") ?: return ToolResult(false, error = "Missing param: package_name")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ToolResult(false, error = "No launch intent found for: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolResult(true, """{"success":true,"package_name":"$pkg"}""")
        } catch (e: Exception) {
            ToolResult(false, error = "Failed to launch $pkg: ${e.message}")
        }
    }
}

class FindAppTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "find_app",
        description = "Find installed apps by keyword. Searches both app name and package name. " +
            "Returns matching apps with package_name and app_name.",
        category = "app",
        params = mapOf(
            "query" to ToolParam("string", "Keyword to search for in app names or package names")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val query = params.getString("query") ?: return ToolResult(false, error = "Missing param: query")
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
        val matches = apps.filter {
            val label = it.loadLabel(context.packageManager).toString()
            val pkg = it.activityInfo.packageName
            label.contains(query, true) || pkg.contains(query, true)
        }.sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }

        if (matches.isEmpty()) return ToolResult(false, error = "No apps found matching: $query")
        val results = matches.joinToString(",") {
            val label = it.loadLabel(context.packageManager).toString().replace("\"", "'")
            val pkg = it.activityInfo.packageName
            """{"app_name":"$label","package_name":"$pkg"}"""
        }
        return ToolResult(true, "[$results]")
    }
}

class ListAppsTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "list_apps",
        description = "List all installed apps with a launcher icon (launchable apps)",
        category = "app",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val apps: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
        val sorted = apps.sortedBy {
            it.loadLabel(context.packageManager).toString().lowercase()
        }
        val sb = StringBuilder()
        for (app in sorted) {
            val label = app.loadLabel(context.packageManager)
            val pkg = app.activityInfo.packageName
            sb.appendLine("$label | $pkg")
        }
        return ToolResult(true, sb.toString().trimEnd())
    }
}

class GetCurrentAppTool : Tool {
    override val definition = ToolDefinition(
        name = "get_current_app",
        description = "Get the package name of the app currently in the foreground",
        category = "app",
        params = emptyMap()
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val pkg = com.zeroclaw.zero.accessibility.ZeroAccessibilityService
            .getScreenContent().packageName
        return if (pkg.isNotEmpty()) ToolResult(true, pkg)
        else ToolResult(false, error = "Accessibility service not running or no foreground app")
    }
}
