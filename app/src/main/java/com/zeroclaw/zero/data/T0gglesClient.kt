package com.zeroclaw.zero.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zeroclaw.zero.tools.ToolResult
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "T0gglesClient"

/**
 * Sends a JSON-RPC request string, returns the raw JSON response string.
 * Default implementation uses OkHttp; tests can inject a fake.
 */
fun interface JsonRpcTransport {
    fun send(jsonBody: String, url: String, apiKey: String): String
}

/**
 * MCP client that talks to T0ggles over HTTP JSON-RPC.
 *
 * Performs the initialize handshake, discovers tools via tools/list,
 * and proxies tool calls via tools/call.
 */
class T0gglesClient(
    private val prefs: AppPrefs,
    private val transport: JsonRpcTransport = OkHttpTransport()
) {

    private val gson = Gson()
    private val requestId = AtomicInteger(1)

    /**
     * Send the MCP initialize handshake. Must be called before discoverTools().
     */
    fun initialize() {
        val body = mapOf(
            "jsonrpc" to "2.0",
            "id" to requestId.getAndIncrement(),
            "method" to "initialize",
            "params" to mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf(
                    "name" to "zero-android",
                    "version" to "1.0.0"
                )
            )
        )

        val response = sendRequest(body)
        Log.i(TAG, "Initialize handshake OK: ${response.get("result")?.asJsonObject?.get("serverInfo")}")
    }

    /**
     * Call tools/list to discover all available remote tools.
     * Returns a list of [RemoteToolDef] with name, description, and raw inputSchema.
     */
    fun discoverTools(): List<RemoteToolDef> {
        val body = mapOf(
            "jsonrpc" to "2.0",
            "id" to requestId.getAndIncrement(),
            "method" to "tools/list",
            "params" to emptyMap<String, Any>()
        )

        val response = sendRequest(body)
        val result = response.getAsJsonObject("result")
            ?: throw T0gglesException("tools/list returned no result")

        val toolsArray = result.getAsJsonArray("tools")
            ?: throw T0gglesException("tools/list returned no tools array")

        return toolsArray.map { element ->
            val obj = element.asJsonObject
            RemoteToolDef(
                name = obj.get("name").asString,
                description = obj.get("description")?.asString ?: "",
                inputSchema = obj.getAsJsonObject("inputSchema") ?: JsonObject()
            )
        }.also {
            Log.i(TAG, "Discovered ${it.size} remote tools")
        }
    }

    /**
     * Call tools/call to execute a remote tool by its original name.
     */
    fun callTool(name: String, arguments: Map<String, Any?>): ToolResult {
        val body = mapOf(
            "jsonrpc" to "2.0",
            "id" to requestId.getAndIncrement(),
            "method" to "tools/call",
            "params" to mapOf(
                "name" to name,
                "arguments" to arguments
            )
        )

        return try {
            val response = sendRequest(body)

            // Check for JSON-RPC level error
            if (response.has("error")) {
                val error = response.getAsJsonObject("error")
                val msg = error.get("message")?.asString ?: "Unknown RPC error"
                return ToolResult(false, error = "T0ggles RPC error: $msg")
            }

            val result = response.getAsJsonObject("result")
                ?: return ToolResult(false, error = "T0ggles returned no result for $name")

            val isError = result.get("isError")?.asBoolean ?: false
            val content = result.getAsJsonArray("content")
            val text = content?.firstOrNull()?.asJsonObject?.get("text")?.asString ?: ""

            if (isError) {
                ToolResult(false, error = text.ifEmpty { "T0ggles tool error" })
            } else {
                ToolResult(true, data = text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "callTool($name) failed: ${e.message}")
            ToolResult(false, error = "T0ggles call failed: ${e.message}")
        }
    }

    private fun sendRequest(body: Map<String, Any?>): JsonObject {
        val json = gson.toJson(body)
        val responseStr = transport.send(json, prefs.t0gglesUrl, prefs.t0gglesApiKey)
        return JsonParser.parseString(responseStr).asJsonObject
    }
}

/** Default transport using OkHttp. */
class OkHttpTransport : JsonRpcTransport {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .build()

    override fun send(jsonBody: String, url: String, apiKey: String): String {
        val mediaType = "application/json".toMediaType()
        val requestBody = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Connection", "close")
            .post(requestBody)
            .build()

        val response = http.newCall(request).execute()
        return response.use { resp ->
            val body = resp.body?.string()
                ?: throw T0gglesException("Empty response from T0ggles (HTTP ${resp.code})")

            if (!resp.isSuccessful) {
                throw T0gglesException("HTTP ${resp.code}: $body")
            }

            body
        }
    }
}

data class RemoteToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

class T0gglesException(message: String) : Exception(message)
