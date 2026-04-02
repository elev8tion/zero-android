package com.zeroclaw.zero.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WorkflowToolsTest {

    private lateinit var prefs: AppPrefs
    private lateinit var fakeTransport: FakeTransport
    private lateinit var client: T0gglesClient
    private val gson = Gson()

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
        prefs.t0gglesBoardId = "test_board"
        prefs.t0gglesProjectId = "test_project"

        fakeTransport = FakeTransport()
        client = T0gglesClient(prefs, fakeTransport)
    }

    // ── ListWorkflowsTool ────────────────────────────────────────────────────

    @Test
    fun `list_workflows returns all templates as JSON`() {
        val tool = ListWorkflowsTool()
        val result = tool.execute(emptyMap())

        assertTrue(result.success)
        val arr = JsonParser.parseString(result.data).asJsonArray
        assertEquals(3, arr.size())
    }

    @Test
    fun `list_workflows response contains template IDs and step counts`() {
        val tool = ListWorkflowsTool()
        val result = tool.execute(emptyMap())

        val arr = JsonParser.parseString(result.data).asJsonArray
        val ids = arr.map { it.asJsonObject.get("id").asString }
        assertTrue("deploy_and_test" in ids)
        assertTrue("bug_triage" in ids)
        assertTrue("new_tool" in ids)

        val deployTemplate = arr.first { it.asJsonObject.get("id").asString == "deploy_and_test" }
        assertEquals(4, deployTemplate.asJsonObject.get("stepCount").asInt)
    }

    @Test
    fun `list_workflows has correct definition`() {
        val tool = ListWorkflowsTool()
        assertEquals("list_workflows", tool.definition.name)
        assertEquals("workflow", tool.definition.category)
        assertTrue(tool.definition.params.isEmpty())
    }

    // ── RunWorkflowTool ──────────────────────────────────────────────────────

    @Test
    fun `run_workflow with valid template calls bulk-create-tasks`() {
        // bulk-create-tasks response with 4 task IDs
        fakeTransport.enqueue(bulkCreateResponse("id1", "id2", "id3", "id4"))
        // 3 create-dependency responses (id1→id2, id2→id3, id3→id4)
        fakeTransport.enqueue(successResponse("dep created"))
        fakeTransport.enqueue(successResponse("dep created"))
        fakeTransport.enqueue(successResponse("dep created"))

        val tool = RunWorkflowTool(client, prefs)
        val result = tool.execute(mapOf("template" to "deploy_and_test"))

        assertTrue(result.success)
        assertTrue(result.data.contains("4 tasks"))
        assertTrue(result.data.contains("Deploy & Test"))

        // First request should be bulk-create-tasks
        val firstReq = fakeTransport.requests[0].json
        assertTrue(firstReq.contains("bulk-create-tasks"))
        assertTrue(firstReq.contains("test_board"))
        assertTrue(firstReq.contains("test_project"))
    }

    @Test
    fun `run_workflow creates sequential dependencies between tasks`() {
        fakeTransport.enqueue(bulkCreateResponse("a", "b", "c", "d"))
        fakeTransport.enqueue(successResponse("ok"))
        fakeTransport.enqueue(successResponse("ok"))
        fakeTransport.enqueue(successResponse("ok"))

        val tool = RunWorkflowTool(client, prefs)
        tool.execute(mapOf("template" to "deploy_and_test"))

        // Should have 4 requests: 1 bulk-create + 3 dependencies
        assertEquals(4, fakeTransport.requests.size)

        // Verify dependency requests contain correct pairs
        val dep1 = fakeTransport.requests[1].json
        assertTrue(dep1.contains("create-dependency"))
        assertTrue(dep1.contains("\"a\"") && dep1.contains("\"b\""))

        val dep2 = fakeTransport.requests[2].json
        assertTrue(dep2.contains("\"b\"") && dep2.contains("\"c\""))

        val dep3 = fakeTransport.requests[3].json
        assertTrue(dep3.contains("\"c\"") && dep3.contains("\"d\""))
    }

    @Test
    fun `run_workflow with prefix prepends to task titles`() {
        fakeTransport.enqueue(bulkCreateResponse("x1", "x2", "x3", "x4"))
        fakeTransport.enqueue(successResponse("ok"))
        fakeTransport.enqueue(successResponse("ok"))
        fakeTransport.enqueue(successResponse("ok"))

        val tool = RunWorkflowTool(client, prefs)
        tool.execute(mapOf("template" to "deploy_and_test", "prefix" to "DEMO-20: "))

        val bulkReq = fakeTransport.requests[0].json
        assertTrue(bulkReq.contains("DEMO-20: Build debug APK"))
        assertTrue(bulkReq.contains("DEMO-20: Install on device"))
    }

    @Test
    fun `run_workflow with unknown template returns error`() {
        val tool = RunWorkflowTool(client, prefs)
        val result = tool.execute(mapOf("template" to "nonexistent"))

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Unknown template"))
        assertTrue(result.error!!.contains("list_workflows"))
    }

    @Test
    fun `run_workflow with missing template param returns error`() {
        val tool = RunWorkflowTool(client, prefs)
        val result = tool.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing required param"))
    }

    @Test
    fun `run_workflow handles bulk-create failure gracefully`() {
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":1,"result":{
                "content":[{"type":"text","text":"Board not found"}],
                "isError":true
            }}
        """)

        val tool = RunWorkflowTool(client, prefs)
        val result = tool.execute(mapOf("template" to "deploy_and_test"))

        assertFalse(result.success)
        // Only 1 request (the failed bulk-create), no dependency calls
        assertEquals(1, fakeTransport.requests.size)
    }

    @Test
    fun `run_workflow has correct definition`() {
        val tool = RunWorkflowTool(client, prefs)
        assertEquals("run_workflow", tool.definition.name)
        assertEquals("workflow", tool.definition.category)
        assertTrue(tool.definition.params["template"]!!.required)
        assertFalse(tool.definition.params["prefix"]!!.required)
    }

    // ── parseTaskIds ─────────────────────────────────────────────────────────

    @Test
    fun `parseTaskIds handles direct array response`() {
        val json = """[{"id":"abc"},{"id":"def"},{"id":"ghi"}]"""
        val ids = RunWorkflowTool.parseTaskIds(json)
        assertEquals(listOf("abc", "def", "ghi"), ids)
    }

    @Test
    fun `parseTaskIds handles wrapper object with tasks array`() {
        val json = """{"tasks":[{"id":"t1"},{"id":"t2"}]}"""
        val ids = RunWorkflowTool.parseTaskIds(json)
        assertEquals(listOf("t1", "t2"), ids)
    }

    @Test
    fun `parseTaskIds handles wrapper object with created array`() {
        val json = """{"created":[{"id":"c1"},{"id":"c2"}]}"""
        val ids = RunWorkflowTool.parseTaskIds(json)
        assertEquals(listOf("c1", "c2"), ids)
    }

    @Test
    fun `parseTaskIds returns empty for malformed input`() {
        assertEquals(emptyList<String>(), RunWorkflowTool.parseTaskIds("not json"))
        assertEquals(emptyList<String>(), RunWorkflowTool.parseTaskIds(""))
        assertEquals(emptyList<String>(), RunWorkflowTool.parseTaskIds("42"))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun bulkCreateResponse(vararg ids: String): String {
        val tasks = ids.joinToString(",") { """{"id":"$it"}""" }
        val escaped = "[$tasks]".replace("\"", "\\\"")
        return """
            {"jsonrpc":"2.0","id":1,"result":{
                "content":[{"type":"text","text":"$escaped"}],
                "isError":false
            }}
        """
    }

    private fun successResponse(text: String): String {
        return """
            {"jsonrpc":"2.0","id":1,"result":{
                "content":[{"type":"text","text":"$text"}],
                "isError":false
            }}
        """
    }
}
