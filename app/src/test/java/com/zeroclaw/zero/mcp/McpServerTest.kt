package com.zeroclaw.zero.mcp

import android.content.Context
import com.zeroclaw.zero.tools.*
import io.mockk.mockk
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class McpServerTest {

    private lateinit var registry: ToolRegistry
    private lateinit var server: McpServer
    private val context: Context = mockk(relaxed = true)

    @Before
    fun setup() {
        registry = ToolRegistry(context)
        // Register a few test tools
        registry.register(stubTool("test_echo", "test",
            params = mapOf("text" to ToolParam("string", "text to echo", true))))
        registry.register(stubTool("test_fail", "test"))

        server = McpServer(registry, port = 0) // OS picks a free port
    }

    @After
    fun teardown() {
        server.stop()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun sendHttp(method: String, path: String, body: String = ""): HttpResult {
        // Server uses port 0 — need to get assigned port
        // McpServer always uses the port it's given. Port 0 won't work with ServerSocket.
        // Use a known test port instead.
        val port = server.port
        val socket = Socket("127.0.0.1", port)
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        val bodyBytes = body.toByteArray()
        writer.print("$method $path HTTP/1.1\r\n")
        writer.print("Host: localhost\r\n")
        if (body.isNotEmpty()) {
            writer.print("Content-Type: application/json\r\n")
            writer.print("Content-Length: ${bodyBytes.size}\r\n")
        }
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        if (body.isNotEmpty()) writer.print(body)
        writer.flush()

        // Read response
        val statusLine = reader.readLine() ?: return HttpResult(0, "")
        val statusCode = statusLine.split(" ")[1].toIntOrNull() ?: 0

        // Read headers until blank line
        val headers = mutableMapOf<String, String>()
        var line: String?
        while (reader.readLine().also { line = it } != null && !line.isNullOrEmpty()) {
            val idx = line!!.indexOf(':')
            if (idx > 0) headers[line!!.substring(0, idx).trim().lowercase()] =
                line!!.substring(idx + 1).trim()
        }

        // Read body
        val responseBody = StringBuilder()
        while (reader.readLine().also { line = it } != null) {
            responseBody.appendLine(line)
        }

        socket.close()
        return HttpResult(statusCode, responseBody.toString().trim())
    }

    private fun sendJsonRpc(method: String, params: JSONObject? = null, id: Any = 1): JSONObject {
        val req = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val result = sendHttp("POST", "/mcp", req.toString())
        return JSONObject(result.body)
    }

    data class HttpResult(val status: Int, val body: String)

    private fun stubTool(
        name: String,
        category: String,
        params: Map<String, ToolParam> = emptyMap()
    ): Tool = object : Tool {
        override val definition = ToolDefinition(name, "Test: $name", category, params)
        override fun execute(params: Map<String, Any?>): ToolResult {
            return if (name == "test_fail") {
                ToolResult(false, error = "Intentional failure")
            } else {
                val text = params["text"]?.toString() ?: "no input"
                ToolResult(true, data = "echo: $text")
            }
        }
    }

    // ── Server lifecycle ────────────────────────────────────────────────────────

    @Test
    fun `server starts and reports isRunning`() {
        // Use a specific port for testing
        server.stop()
        server = McpServer(registry, port = 13282)
        server.start()
        Thread.sleep(200)
        assertTrue(server.isRunning)
    }

    @Test
    fun `server stop sets isRunning to false`() {
        server = McpServer(registry, port = 13283)
        server.start()
        Thread.sleep(200)
        server.stop()
        assertFalse(server.isRunning)
    }

    @Test
    fun `double start is idempotent`() {
        server = McpServer(registry, port = 13284)
        server.start()
        server.start() // should not throw
        Thread.sleep(200)
        assertTrue(server.isRunning)
    }

    // ── Health endpoint ─────────────────────────────────────────────────────────

    @Test
    fun `GET health returns 200 with tool count`() {
        server = McpServer(registry, port = 13285)
        server.start()
        Thread.sleep(200)

        val result = sendHttp("GET", "/health")
        assertEquals(200, result.status)
        val json = JSONObject(result.body)
        assertEquals("ok", json.getString("status"))
        assertEquals(2, json.getInt("tools"))
    }

    // ── CORS ────────────────────────────────────────────────────────────────────

    @Test
    fun `OPTIONS returns 204 for CORS preflight`() {
        server = McpServer(registry, port = 13286)
        server.start()
        Thread.sleep(200)

        val result = sendHttp("OPTIONS", "/mcp")
        assertEquals(204, result.status)
    }

    // ── 404 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `unknown path returns 404`() {
        server = McpServer(registry, port = 13287)
        server.start()
        Thread.sleep(200)

        val result = sendHttp("GET", "/unknown")
        assertEquals(404, result.status)
    }

    // ── JSON-RPC: initialize ────────────────────────────────────────────────────

    @Test
    fun `initialize returns protocol version and server info`() {
        server = McpServer(registry, port = 13288)
        server.start()
        Thread.sleep(200)

        val resp = sendJsonRpc("initialize")
        assertEquals("2.0", resp.getString("jsonrpc"))
        val result = resp.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        val serverInfo = result.getJSONObject("serverInfo")
        assertEquals("zero-android", serverInfo.getString("name"))
    }

    // ── JSON-RPC: tools/list ────────────────────────────────────────────────────

    @Test
    fun `tools list returns all registered tools`() {
        server = McpServer(registry, port = 13289)
        server.start()
        Thread.sleep(200)

        val resp = sendJsonRpc("tools/list")
        val tools = resp.getJSONObject("result").getJSONArray("tools")
        assertEquals(2, tools.length())

        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getString("name")
        }.toSet()
        assertTrue("test_echo" in names)
        assertTrue("test_fail" in names)
    }

    @Test
    fun `tools list includes input schema with params`() {
        server = McpServer(registry, port = 13290)
        server.start()
        Thread.sleep(200)

        val resp = sendJsonRpc("tools/list")
        val tools = resp.getJSONObject("result").getJSONArray("tools")
        val echoTool = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .first { it.getString("name") == "test_echo" }

        val schema = echoTool.getJSONObject("inputSchema")
        assertEquals("object", schema.getString("type"))
        assertTrue(schema.getJSONObject("properties").has("text"))
    }

    // ── JSON-RPC: tools/call ────────────────────────────────────────────────────

    @Test
    fun `tools call executes tool and returns result`() {
        server = McpServer(registry, port = 13291)
        server.start()
        Thread.sleep(200)

        val params = JSONObject().apply {
            put("name", "test_echo")
            put("arguments", JSONObject().put("text", "hello world"))
        }
        val resp = sendJsonRpc("tools/call", params)
        val result = resp.getJSONObject("result")
        assertFalse(result.getBoolean("isError"))
        val content = result.getJSONArray("content")
        assertEquals("echo: hello world", content.getJSONObject(0).getString("text"))
    }

    @Test
    fun `tools call with failing tool returns isError true`() {
        server = McpServer(registry, port = 13292)
        server.start()
        Thread.sleep(200)

        val params = JSONObject().apply {
            put("name", "test_fail")
        }
        val resp = sendJsonRpc("tools/call", params)
        val result = resp.getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
    }

    @Test
    fun `tools call missing name returns error`() {
        server = McpServer(registry, port = 13293)
        server.start()
        Thread.sleep(200)

        val params = JSONObject() // no "name" field
        val resp = sendJsonRpc("tools/call", params)
        assertTrue(resp.has("error"))
    }

    @Test
    fun `tools call missing params returns error`() {
        server = McpServer(registry, port = 13294)
        server.start()
        Thread.sleep(200)

        val req = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            // no "params"
        }
        val result = sendHttp("POST", "/mcp", req.toString())
        val resp = JSONObject(result.body)
        assertTrue(resp.has("error"))
    }

    @Test
    fun `tools call with unknown tool returns error in content`() {
        server = McpServer(registry, port = 13295)
        server.start()
        Thread.sleep(200)

        val params = JSONObject().apply {
            put("name", "nonexistent_tool")
        }
        val resp = sendJsonRpc("tools/call", params)
        val result = resp.getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
    }

    // ── JSON-RPC: unknown method ────────────────────────────────────────────────

    @Test
    fun `unknown method returns error response`() {
        server = McpServer(registry, port = 13296)
        server.start()
        Thread.sleep(200)

        val resp = sendJsonRpc("unknown/method")
        assertTrue(resp.has("error"))
        assertEquals(-32601, resp.getJSONObject("error").getInt("code"))
    }

    // ── Malformed request ───────────────────────────────────────────────────────

    @Test
    fun `malformed JSON body returns parse error`() {
        server = McpServer(registry, port = 13297)
        server.start()
        Thread.sleep(200)

        val result = sendHttp("POST", "/mcp", "not valid json{{{")
        val resp = JSONObject(result.body)
        assertTrue(resp.has("error"))
        assertEquals(-32700, resp.getJSONObject("error").getInt("code"))
    }
}
