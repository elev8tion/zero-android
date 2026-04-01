package com.zeroclaw.zero.mcp

import android.util.Log
import com.zeroclaw.zero.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

private const val TAG = "McpServer"

class McpServer(
    private val toolRegistry: ToolRegistry,
    val port: Int = 3282
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        serverSocket = ServerSocket(port)
        isRunning = true
        Log.i(TAG, "MCP server listening on port $port")
        scope.launch {
            while (isActive) {
                try {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                } catch (e: SocketException) {
                    if (isActive) Log.e(TAG, "Socket error: ${e.message}")
                    break
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "MCP server stopped")
    }

    // ── HTTP / JSON-RPC handling ──────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        try {
            socket.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                val writer = PrintWriter(it.getOutputStream(), true)

                // Read HTTP request line + headers
                val requestLine = reader.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { l -> line = l } != null && !line.isNullOrEmpty()) {
                    val idx = line!!.indexOf(':')
                    if (idx > 0) headers[line!!.substring(0, idx).trim().lowercase()] =
                        line!!.substring(idx + 1).trim()
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: return

                // CORS preflight
                if (method == "OPTIONS") {
                    sendHttpResponse(writer, 204, "", "")
                    return
                }

                // Health check
                if (method == "GET" && path == "/health") {
                    sendHttpResponse(writer, 200, "application/json",
                        "{\"status\":\"ok\",\"tools\":${toolRegistry.toolCount()}}")
                    return
                }

                // MCP endpoint — POST /mcp
                if (method == "POST" && (path == "/mcp" || path == "/")) {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = CharArray(contentLength)
                    reader.read(body, 0, contentLength)
                    val responseJson = handleMcpRequest(String(body))
                    sendHttpResponse(writer, 200, "application/json", responseJson)
                    return
                }

                sendHttpResponse(writer, 404, "application/json", "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handling error: ${e.message}")
        }
    }

    private fun sendHttpResponse(writer: PrintWriter, status: Int, contentType: String, body: String) {
        val statusText = when (status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"
            404 -> "Not Found"; 500 -> "Internal Server Error"
            else -> "Unknown"
        }
        writer.print("HTTP/1.1 $status $statusText\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        if (contentType.isNotEmpty()) {
            writer.print("Content-Type: $contentType\r\n")
            writer.print("Content-Length: ${body.toByteArray().size}\r\n")
        }
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        if (body.isNotEmpty()) writer.print(body)
        writer.flush()
    }

    // ── MCP JSON-RPC dispatch ─────────────────────────────────────────────────

    private fun handleMcpRequest(body: String): String {
        return try {
            val req = JSONObject(body)
            val id = req.opt("id")
            val method = req.optString("method")
            Log.d(TAG, "MCP method=$method id=$id")
            when (method) {
                "initialize"   -> handleInitialize(id)
                "tools/list"   -> handleToolsList(id)
                "tools/call"   -> handleToolsCall(id, req.optJSONObject("params"))
                else           -> errorResponse(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MCP parse error: ${e.message}")
            errorResponse(null, -32700, "Parse error: ${e.message}")
        }
    }

    private fun handleInitialize(id: Any?): String {
        val result = JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject())
            })
            put("serverInfo", JSONObject().apply {
                put("name", "zero-android")
                put("version", "1.0.0")
            })
        }
        return successResponse(id, result)
    }

    private fun handleToolsList(id: Any?): String {
        val toolsArray = JSONArray()
        for (def in toolRegistry.getDefinitions()) {
            val toolObj = JSONObject().apply {
                put("name", def.name)
                put("description", def.description)
                val inputSchema = JSONObject().apply {
                    put("type", "object")
                    val props = JSONObject()
                    val required = JSONArray()
                    for ((pName, pDef) in def.params) {
                        props.put(pName, JSONObject().apply {
                            put("type", pDef.type)
                            put("description", pDef.description)
                        })
                        if (pDef.required) required.put(pName)
                    }
                    put("properties", props)
                    if (required.length() > 0) put("required", required)
                }
                put("inputSchema", inputSchema)
            }
            toolsArray.put(toolObj)
        }
        return successResponse(id, JSONObject().put("tools", toolsArray))
    }

    private fun handleToolsCall(id: Any?, params: JSONObject?): String {
        if (params == null) return errorResponse(id, -32602, "Missing params")
        val name = params.optString("name")
        if (name.isEmpty()) return errorResponse(id, -32602, "Missing tool name")

        val argsObj = params.optJSONObject("arguments") ?: JSONObject()
        val argsMap = mutableMapOf<String, Any?>()
        for (key in argsObj.keys()) argsMap[key] = argsObj.opt(key)

        val toolResult = toolRegistry.execute(name, argsMap)
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", if (toolResult.success) toolResult.data
                            else "Error: ${toolResult.error}")
            })
        }
        val result = JSONObject().apply {
            put("content", content)
            put("isError", !toolResult.success)
        }
        return successResponse(id, result)
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private fun successResponse(id: Any?, result: JSONObject): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("result", result)
        }.toString()

    private fun errorResponse(id: Any?, code: Int, message: String): String =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }.toString()
}
