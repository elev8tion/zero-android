package com.zeroclaw.zero.tools

import com.google.gson.JsonObject
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.data.RemoteToolDef
import com.zeroclaw.zero.data.T0gglesClient

/**
 * Generic [Tool] that proxies a single T0ggles MCP tool.
 *
 * One instance is created per remote tool discovered via tools/list.
 * Tool names are prefixed with `wf_` and converted from kebab-case to snake_case.
 * The `boardId` parameter is auto-injected from prefs when the tool expects it.
 */
class T0gglesProxyTool(
    private val client: T0gglesClient,
    private val remoteName: String,
    remoteDesc: String,
    remoteSchema: JsonObject,
    private val prefs: AppPrefs
) : Tool {

    override val definition = ToolDefinition(
        name = toLocalName(remoteName),
        description = remoteDesc,
        category = "workflow",
        params = parseSchemaToParams(remoteSchema)
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val args = params.toMutableMap()

        // Auto-inject boardId from prefs if the tool expects it and caller didn't provide
        if ("boardId" in definition.params && args["boardId"] == null) {
            args["boardId"] = prefs.t0gglesBoardId
        }

        return client.callTool(remoteName, args)
    }

    companion object {
        /**
         * Convert a remote tool name (kebab-case) to a local name with wf_ prefix.
         * e.g. "list-tasks" → "wf_list_tasks"
         */
        fun toLocalName(remoteName: String): String =
            "wf_${remoteName.replace("-", "_")}"

        /**
         * Convert a JSON Schema `inputSchema` object into a map of [ToolParam].
         *
         * Expects the standard MCP format:
         * ```json
         * {
         *   "type": "object",
         *   "properties": {
         *     "boardId": { "type": "string", "description": "The board ID" }
         *   },
         *   "required": ["boardId"]
         * }
         * ```
         */
        fun parseSchemaToParams(schema: JsonObject): Map<String, ToolParam> {
            val properties = schema.getAsJsonObject("properties") ?: return emptyMap()
            val requiredArray = schema.getAsJsonArray("required")
            val requiredSet = requiredArray
                ?.map { it.asString }
                ?.toSet()
                ?: emptySet()

            val result = mutableMapOf<String, ToolParam>()
            for ((key, value) in properties.entrySet()) {
                val prop = value.asJsonObject
                val type = prop.get("type")?.asString ?: "string"
                val desc = prop.get("description")?.asString ?: ""
                result[key] = ToolParam(
                    type = type,
                    description = desc,
                    required = key in requiredSet
                )
            }
            return result
        }

        /**
         * Create a [T0gglesProxyTool] from a [RemoteToolDef].
         */
        fun fromRemoteDef(
            client: T0gglesClient,
            def: RemoteToolDef,
            prefs: AppPrefs
        ): T0gglesProxyTool = T0gglesProxyTool(
            client = client,
            remoteName = def.name,
            remoteDesc = def.description,
            remoteSchema = def.inputSchema,
            prefs = prefs
        )
    }
}
