package com.zeroclaw.zero.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.data.JsonRpcTransport
import com.zeroclaw.zero.data.T0gglesClient
import com.zeroclaw.zero.data.T0gglesException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for T0gglesClient (discovery + tool calls) and T0gglesProxyTool
 * (schema mapping, name conversion, boardId injection, error handling).
 *
 * Uses a fake [JsonRpcTransport] to avoid MockWebServer flakiness under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class T0gglesProxyToolTest {

    private lateinit var prefs: AppPrefs
    private lateinit var fakeTransport: FakeTransport
    private lateinit var client: T0gglesClient

    /** Records requests and returns queued responses. */
    class FakeTransport : JsonRpcTransport {
        private val responses = mutableListOf<String>()
        val requests = mutableListOf<RequestRecord>()

        data class RequestRecord(val json: String, val url: String, val apiKey: String)

        fun enqueue(response: String) { responses.add(response) }

        override fun send(jsonBody: String, url: String, apiKey: String): String {
            requests.add(RequestRecord(jsonBody, url, apiKey))
            if (responses.isEmpty()) throw T0gglesException("No response queued")
            return responses.removeAt(0)
        }
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPrefs(context)
        prefs.t0gglesUrl = "https://t0ggles.com/mcp"
        prefs.t0gglesApiKey = "test_key_123"
        prefs.t0gglesBoardId = "test_board_id"

        fakeTransport = FakeTransport()
        client = T0gglesClient(prefs, fakeTransport)
    }

    // ── Name conversion ──────────────────────────────────────────────────────

    @Test
    fun `toLocalName converts kebab-case to wf_ snake_case`() {
        assertEquals("wf_list_tasks", T0gglesProxyTool.toLocalName("list-tasks"))
        assertEquals("wf_create_note", T0gglesProxyTool.toLocalName("create-note"))
        assertEquals("wf_get_board", T0gglesProxyTool.toLocalName("get-board"))
        assertEquals("wf_health_check", T0gglesProxyTool.toLocalName("health-check"))
    }

    @Test
    fun `toLocalName handles single word names`() {
        assertEquals("wf_standup", T0gglesProxyTool.toLocalName("standup"))
    }

    // ── Schema parsing ───────────────────────────────────────────────────────

    @Test
    fun `parseSchemaToParams extracts properties and required`() {
        val schema = JsonParser.parseString("""
            {
                "type": "object",
                "properties": {
                    "boardId": { "type": "string", "description": "The board ID" },
                    "status": { "type": "string", "description": "Filter by status" },
                    "limit":  { "type": "number", "description": "Max results" }
                },
                "required": ["boardId"]
            }
        """).asJsonObject

        val params = T0gglesProxyTool.parseSchemaToParams(schema)

        assertEquals(3, params.size)
        assertTrue(params["boardId"]!!.required)
        assertFalse(params["status"]!!.required)
        assertFalse(params["limit"]!!.required)
        assertEquals("string", params["boardId"]!!.type)
        assertEquals("number", params["limit"]!!.type)
        assertEquals("The board ID", params["boardId"]!!.description)
    }

    @Test
    fun `parseSchemaToParams handles empty schema`() {
        val schema = JsonObject()
        val params = T0gglesProxyTool.parseSchemaToParams(schema)
        assertTrue(params.isEmpty())
    }

    @Test
    fun `parseSchemaToParams handles schema with no required array`() {
        val schema = JsonParser.parseString("""
            {
                "type": "object",
                "properties": {
                    "query": { "type": "string", "description": "Search query" }
                }
            }
        """).asJsonObject

        val params = T0gglesProxyTool.parseSchemaToParams(schema)
        assertEquals(1, params.size)
        assertFalse(params["query"]!!.required)
    }

    // ── Tool discovery ───────────────────────────────────────────────────────

    @Test
    fun `discoverTools parses tools from MCP response`() {
        // Queue initialize response
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":1,"result":{
                "protocolVersion":"2024-11-05",
                "capabilities":{"tools":{}},
                "serverInfo":{"name":"t0ggles","version":"1.0.0"}
            }}
        """)

        // Queue tools/list response
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":2,"result":{
                "tools":[
                    {
                        "name":"list-tasks",
                        "description":"List all tasks on a board",
                        "inputSchema":{
                            "type":"object",
                            "properties":{
                                "boardId":{"type":"string","description":"Board ID"}
                            },
                            "required":["boardId"]
                        }
                    },
                    {
                        "name":"create-note",
                        "description":"Create a note",
                        "inputSchema":{
                            "type":"object",
                            "properties":{
                                "boardId":{"type":"string","description":"Board ID"},
                                "title":{"type":"string","description":"Note title"},
                                "content":{"type":"string","description":"Note body"}
                            },
                            "required":["boardId","title"]
                        }
                    }
                ]
            }}
        """)

        client.initialize()
        val tools = client.discoverTools()

        assertEquals(2, tools.size)
        assertEquals("list-tasks", tools[0].name)
        assertEquals("create-note", tools[1].name)
        assertEquals("List all tasks on a board", tools[0].description)

        // Verify 2 requests were sent (initialize + tools/list)
        assertEquals(2, fakeTransport.requests.size)
    }

    // ── Proxy tool definition ────────────────────────────────────────────────

    @Test
    fun `proxy tool has correct definition with wf_ prefix and workflow category`() {
        val schema = JsonParser.parseString("""
            {
                "type": "object",
                "properties": {
                    "boardId": { "type": "string", "description": "Board ID" },
                    "taskId":  { "type": "string", "description": "Task ID" }
                },
                "required": ["boardId", "taskId"]
            }
        """).asJsonObject

        val proxy = T0gglesProxyTool(client, "update-task", "Update a task", schema, prefs)

        assertEquals("wf_update_task", proxy.definition.name)
        assertEquals("workflow", proxy.definition.category)
        assertEquals("Update a task", proxy.definition.description)
        assertEquals(2, proxy.definition.params.size)
        assertTrue(proxy.definition.params["boardId"]!!.required)
        assertTrue(proxy.definition.params["taskId"]!!.required)
    }

    // ── boardId auto-injection ───────────────────────────────────────────────

    @Test
    fun `proxy tool auto-injects boardId from prefs when missing`() {
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":1,"result":{
                "content":[{"type":"text","text":"tasks here"}],
                "isError":false
            }}
        """)

        val schema = JsonParser.parseString("""
            {
                "type": "object",
                "properties": {
                    "boardId": { "type": "string", "description": "Board ID" }
                },
                "required": ["boardId"]
            }
        """).asJsonObject

        val proxy = T0gglesProxyTool(client, "list-tasks", "List tasks", schema, prefs)
        val result = proxy.execute(emptyMap())  // No boardId provided

        assertTrue(result.success)

        // Verify the request JSON included the prefs boardId
        val sentJson = fakeTransport.requests.last().json
        assertTrue("boardId should be injected", sentJson.contains("test_board_id"))
    }

    @Test
    fun `proxy tool does not override caller-provided boardId`() {
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":1,"result":{
                "content":[{"type":"text","text":"ok"}],
                "isError":false
            }}
        """)

        val schema = JsonParser.parseString("""
            {
                "type": "object",
                "properties": {
                    "boardId": { "type": "string", "description": "Board ID" }
                },
                "required": ["boardId"]
            }
        """).asJsonObject

        val proxy = T0gglesProxyTool(client, "list-tasks", "List tasks", schema, prefs)
        proxy.execute(mapOf("boardId" to "custom_board"))

        val sentJson = fakeTransport.requests.last().json
        assertTrue("Should use caller's boardId", sentJson.contains("custom_board"))
        assertFalse("Should not use prefs boardId", sentJson.contains("test_board_id"))
    }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test
    fun `callTool returns error ToolResult on RPC error`() {
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Invalid params"}}
        """)

        val result = client.callTool("bad-tool", emptyMap())

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Invalid params"))
    }

    @Test
    fun `callTool returns error ToolResult on isError response`() {
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":1,"result":{
                "content":[{"type":"text","text":"Board not found"}],
                "isError":true
            }}
        """)

        val result = client.callTool("list-tasks", mapOf("boardId" to "nonexistent"))

        assertFalse(result.success)
        assertEquals("Board not found", result.error)
    }

    @Test
    fun `callTool returns error ToolResult on transport failure`() {
        // No response queued — transport will throw
        val result = client.callTool("list-tasks", emptyMap())

        assertFalse(result.success)
        assertNotNull(result.error)
    }

    // ── Tool call success ────────────────────────────────────────────────────

    @Test
    fun `callTool returns success ToolResult with data`() {
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":1,"result":{
                "content":[{"type":"text","text":"DEMO-13 done"}],
                "isError":false
            }}
        """)

        val result = client.callTool("list-tasks", mapOf("boardId" to "test"))

        assertTrue(result.success)
        assertTrue(result.data.contains("DEMO-13"))
    }

    // ── Authorization / URL ──────────────────────────────────────────────────

    @Test
    fun `requests use configured URL and API key`() {
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":1,"result":{
                "content":[{"type":"text","text":"ok"}],
                "isError":false
            }}
        """)

        client.callTool("health-check", emptyMap())

        val req = fakeTransport.requests.last()
        assertEquals("https://t0ggles.com/mcp", req.url)
        assertEquals("test_key_123", req.apiKey)
    }
}
