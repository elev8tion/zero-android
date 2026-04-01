package com.zeroclaw.zero.mcp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zeroclaw.zero.tools.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented test for real TCP MCP server on device.
 * Starts a server on a dynamic port and sends real HTTP/JSON-RPC requests.
 */
@RunWith(AndroidJUnit4::class)
class McpServerInstrumentedTest {

    private lateinit var registry: ToolRegistry
    private lateinit var server: McpServer
    private var port = 0

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        registry = ToolRegistry(context).apply {
            register(GetDeviceStatusTool())
            register(LaunchAppTool(context))
            register(FindAppTool(context))
            register(ListAppsTool(context))
            register(GetCurrentAppTool())
            register(GetBrightnessTool(context))
            register(GetVolumeTool(context))
        }
        // Use port 0 to let OS assign a free port, avoiding conflicts
        // But McpServer takes a fixed port, so use a high ephemeral port
        port = (49152..65000).random()
        server = McpServer(registry, port)
    }

    @After
    fun teardown() {
        if (server.isRunning) server.stop()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    fun startServerSetsIsRunningTrue() {
        server.start()
        assertTrue("Server should be running after start()", server.isRunning)
    }

    @Test
    fun stopServerSetsIsRunningFalse() {
        server.start()
        server.stop()
        assertFalse("Server should not be running after stop()", server.isRunning)
    }

    @Test
    fun stopServerReleasesPort() {
        server.start()
        server.stop()
        Thread.sleep(100) // Brief wait for socket cleanup

        // Should be able to start a new server on the same port
        val server2 = McpServer(registry, port)
        try {
            server2.start()
            assertTrue("New server should start on released port", server2.isRunning)
        } finally {
            server2.stop()
        }
    }

    // ── Health endpoint ───────────────────────────────────────────────────────

    @Test
    fun healthEndpointReturns200WithToolCount() {
        server.start()
        Thread.sleep(200)

        val response = sendHttpRequest("GET", "/health", null)
        assertTrue("Should get 200 OK", response.statusLine.contains("200"))

        val json = JSONObject(response.body)
        assertEquals("ok", json.getString("status"))
        assertEquals(registry.toolCount(), json.getInt("tools"))
    }

    // ── JSON-RPC: initialize ──────────────────────────────────────────────────

    @Test
    fun initializeReturnsProtocolVersion() {
        server.start()
        Thread.sleep(200)

        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
        }.toString()

        val response = sendHttpRequest("POST", "/mcp", body)
        val json = JSONObject(response.body)

        assertEquals("2.0", json.getString("jsonrpc"))
        assertEquals(1, json.getInt("id"))

        val result = json.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        assertEquals("zero-android", result.getJSONObject("serverInfo").getString("name"))
    }

    // ── JSON-RPC: tools/list ──────────────────────────────────────────────────

    @Test
    fun toolsListReturnsAllRegisteredTools() {
        server.start()
        Thread.sleep(200)

        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/list")
        }.toString()

        val response = sendHttpRequest("POST", "/mcp", body)
        val json = JSONObject(response.body)
        val tools = json.getJSONObject("result").getJSONArray("tools")

        assertEquals(
            "Should list all registered tools",
            registry.toolCount(),
            tools.length()
        )

        // Every tool should have name, description, inputSchema
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            assertTrue("Tool should have name", tool.has("name"))
            assertTrue("Tool should have description", tool.has("description"))
            assertTrue("Tool should have inputSchema", tool.has("inputSchema"))
        }
    }

    // ── JSON-RPC: tools/call ──────────────────────────────────────────────────

    @Test
    fun toolsCallGetDeviceStatusReturnsResult() {
        server.start()
        Thread.sleep(200)

        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "get_device_status")
                put("arguments", JSONObject())
            })
        }.toString()

        val response = sendHttpRequest("POST", "/mcp", body)
        val json = JSONObject(response.body)
        val result = json.getJSONObject("result")

        assertFalse("isError should be false", result.getBoolean("isError"))

        val content = result.getJSONArray("content")
        assertTrue("Should have at least one content item", content.length() > 0)
        val text = content.getJSONObject(0).getString("text")
        assertTrue("Result should contain service_running", text.contains("service_running"))
    }

    @Test
    fun toolsCallUnknownToolReturnsIsErrorTrue() {
        server.start()
        Thread.sleep(200)

        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 4)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "unknown_tool_xyz")
                put("arguments", JSONObject())
            })
        }.toString()

        val response = sendHttpRequest("POST", "/mcp", body)
        val json = JSONObject(response.body)
        val result = json.getJSONObject("result")

        assertTrue("isError should be true for unknown tool", result.getBoolean("isError"))
    }

    // ── Unknown JSON-RPC method ───────────────────────────────────────────────

    @Test
    fun unknownMethodReturnsError32601() {
        server.start()
        Thread.sleep(200)

        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 5)
            put("method", "nonexistent/method")
        }.toString()

        val response = sendHttpRequest("POST", "/mcp", body)
        val json = JSONObject(response.body)

        assertTrue("Should have error field", json.has("error"))
        assertEquals(-32601, json.getJSONObject("error").getInt("code"))
    }

    // ── Concurrent connections ────────────────────────────────────────────────

    @Test
    fun concurrentConnectionsDoNotCrash() {
        server.start()
        Thread.sleep(200)

        val threadCount = 5
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)

        repeat(threadCount) {
            Thread {
                try {
                    val response = sendHttpRequest("GET", "/health", null)
                    if (response.statusLine.contains("200")) {
                        successCount.incrementAndGet()
                    }
                } catch (_: Exception) {
                    // Connection failure is acceptable — crash is not
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue("Threads should complete within 10s", latch.await(10, TimeUnit.SECONDS))
        assertTrue(
            "At least some concurrent connections should succeed (got ${successCount.get()}/$threadCount)",
            successCount.get() > 0
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private data class HttpResponse(val statusLine: String, val body: String)

    private fun sendHttpRequest(method: String, path: String, body: String?): HttpResponse {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5000
        socket.use {
            val writer = PrintWriter(it.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))

            // Send HTTP request
            writer.print("$method $path HTTP/1.1\r\n")
            writer.print("Host: 127.0.0.1:$port\r\n")
            if (body != null) {
                val bodyBytes = body.toByteArray()
                writer.print("Content-Type: application/json\r\n")
                writer.print("Content-Length: ${bodyBytes.size}\r\n")
            }
            writer.print("Connection: close\r\n")
            writer.print("\r\n")
            if (body != null) writer.print(body)
            writer.flush()

            // Read response
            val statusLine = reader.readLine() ?: ""
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { l -> line = l } != null && !line.isNullOrEmpty()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) headers[line!!.substring(0, idx).trim().lowercase()] =
                    line!!.substring(idx + 1).trim()
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val responseBody = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                reader.read(buf, 0, contentLength)
                String(buf)
            } else {
                reader.readText()
            }

            return HttpResponse(statusLine, responseBody)
        }
    }
}
