package com.zeroclaw.zero.tools

data class ToolResult(
    val success: Boolean,
    val data: String = "",
    val error: String? = null
)

data class ToolParam(
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val category: String,
    val params: Map<String, ToolParam> = emptyMap()
)

interface Tool {
    val definition: ToolDefinition
    fun execute(params: Map<String, Any?>): ToolResult
}

fun Map<String, Any?>.getString(key: String): String? = this[key]?.toString()
fun Map<String, Any?>.getFloat(key: String): Float? = this[key]?.toString()?.toFloatOrNull()
fun Map<String, Any?>.getInt(key: String): Int? = this[key]?.toString()?.toIntOrNull()
fun Map<String, Any?>.getBool(key: String): Boolean? = this[key]?.toString()?.toBooleanStrictOrNull()
