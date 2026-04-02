package com.zeroclaw.zero.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ErrorDatabaseTest {

    private lateinit var prefs: AppPrefs
    private lateinit var fakeTransport: FakeTransport
    private lateinit var client: T0gglesClient
    private lateinit var errorDb: ErrorDatabase
    private lateinit var testScope: TestScope

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
        prefs.t0gglesBoardId = "test_board_id"
        prefs.t0gglesApiKey = "test_key"
        prefs.t0gglesUrl = "https://t0ggles.com/mcp"

        fakeTransport = FakeTransport()
        client = T0gglesClient(prefs, fakeTransport)

        testScope = TestScope(UnconfinedTestDispatcher())
        errorDb = ErrorDatabase(client, prefs, testScope)
    }

    // ── Local logging ─────────────────────────────────────────────────────────

    @Test
    fun `recordError adds entry to local log`() {
        enqueueCreateNoteSuccess()
        errorDb.recordError("tap", mapOf("x" to 100, "y" to 200), "Accessibility service not running")

        assertEquals(1, errorDb.size())
        val results = errorDb.searchLocal("")
        assertEquals(1, results.size)
        assertEquals("tap", results[0].toolName)
        assertEquals("Accessibility service not running", results[0].error)
    }

    @Test
    fun `recordError stores params in entry`() {
        enqueueCreateNoteSuccess()
        val params = mapOf("x" to 100, "y" to 200, "text" to "hello")
        errorDb.recordError("type_text", params, "some error")

        val entry = errorDb.searchLocal("").first()
        assertEquals(100, entry.params["x"])
        assertEquals("hello", entry.params["text"])
    }

    // ── T0ggles logging ───────────────────────────────────────────────────────

    @Test
    fun `recordError fires create-note call to T0ggles`() {
        enqueueCreateNoteSuccess()
        errorDb.recordError("tap", mapOf("x" to 50), "Node not found")

        // UnconfinedTestDispatcher runs coroutine immediately
        assertEquals(1, fakeTransport.requests.size)

        val sentJson = fakeTransport.requests[0].json
        val parsed = JsonParser.parseString(sentJson).asJsonObject
        val params = parsed.getAsJsonObject("params")
        assertEquals("tools/call", parsed.get("method").asString)
        assertEquals("create-note", params.getAsJsonObject("arguments").get("name")?.asString
            ?: params.get("name").asString)
    }

    @Test
    fun `T0ggles note title contains ERROR prefix and tool name`() {
        enqueueCreateNoteSuccess()
        errorDb.recordError("swipe", emptyMap(), "Invalid direction")

        val sentJson = fakeTransport.requests[0].json
        val parsed = JsonParser.parseString(sentJson).asJsonObject
        val args = parsed.getAsJsonObject("params").getAsJsonObject("arguments")
        val title = args.get("title").asString

        assertTrue("Title should have [ERROR] prefix", title.startsWith("[ERROR]"))
        assertTrue("Title should contain tool name", title.contains("swipe"))
    }

    @Test
    fun `T0ggles note content contains error details`() {
        enqueueCreateNoteSuccess()
        errorDb.recordError("click_node", mapOf("nodeId" to "btn1"), "Element not clickable")

        val sentJson = fakeTransport.requests[0].json
        val parsed = JsonParser.parseString(sentJson).asJsonObject
        val args = parsed.getAsJsonObject("params").getAsJsonObject("arguments")
        val content = args.get("content").asString

        assertTrue("Content should contain tool name", content.contains("click_node"))
        assertTrue("Content should contain error", content.contains("Element not clickable"))
        assertTrue("Content should contain params", content.contains("nodeId"))
    }

    // ── searchLocal ───────────────────────────────────────────────────────────

    @Test
    fun `searchLocal finds entries by tool name`() {
        enqueueCreateNoteSuccess()
        enqueueCreateNoteSuccess()
        enqueueCreateNoteSuccess()
        errorDb.recordError("tap", emptyMap(), "No accessibility")
        errorDb.recordError("swipe", emptyMap(), "Out of bounds")
        errorDb.recordError("tap", emptyMap(), "Node not found")

        val results = errorDb.searchLocal("tap")
        assertEquals(2, results.size)
        assertTrue(results.all { it.toolName == "tap" })
    }

    @Test
    fun `searchLocal finds entries by error message`() {
        enqueueCreateNoteSuccess()
        enqueueCreateNoteSuccess()
        errorDb.recordError("tap", emptyMap(), "Accessibility service not running")
        errorDb.recordError("swipe", emptyMap(), "Accessibility service not running")

        val results = errorDb.searchLocal("accessibility")
        assertEquals(2, results.size)
    }

    @Test
    fun `searchLocal with empty query returns all entries`() {
        enqueueCreateNoteSuccess()
        enqueueCreateNoteSuccess()
        errorDb.recordError("tap", emptyMap(), "error1")
        errorDb.recordError("swipe", emptyMap(), "error2")

        val results = errorDb.searchLocal("")
        assertEquals(2, results.size)
    }

    @Test
    fun `searchLocal is case-insensitive`() {
        enqueueCreateNoteSuccess()
        errorDb.recordError("tap", emptyMap(), "Accessibility Service NOT Running")

        assertEquals(1, errorDb.searchLocal("accessibility").size)
        assertEquals(1, errorDb.searchLocal("ACCESSIBILITY").size)
        assertEquals(1, errorDb.searchLocal("not running").size)
    }

    // ── Ring buffer ───────────────────────────────────────────────────────────

    @Test
    fun `ring buffer caps at 100 entries`() {
        // Record 105 errors (skip T0ggles logging to avoid needing 105 queued responses)
        repeat(105) { i ->
            errorDb.recordError("tool_$i", emptyMap(), "error $i", logToT0ggles = false)
        }

        assertEquals(100, errorDb.size())
        // Oldest entries (0-4) should be gone, newest (5-104) should remain
        val results = errorDb.searchLocal("")
        assertEquals("tool_5", results.first().toolName)
        assertEquals("tool_104", results.last().toolName)
    }

    // ── Recursion guard ───────────────────────────────────────────────────────

    @Test
    fun `wf_ tool errors are NOT logged to T0ggles`() {
        errorDb.recordError("wf_list_tasks", emptyMap(), "RPC error", logToT0ggles = false)

        // No T0ggles request should have been made
        assertEquals(0, fakeTransport.requests.size)
        // But it should still be in the local log
        assertEquals(1, errorDb.size())
    }

    // ── Network failure resilience ────────────────────────────────────────────

    @Test
    fun `T0ggles network failure does not prevent local logging`() {
        // Don't enqueue any response — transport will throw
        errorDb.recordError("tap", emptyMap(), "Node not found")

        // Error should still be in local log despite T0ggles failure
        assertEquals(1, errorDb.size())
        val entry = errorDb.searchLocal("").first()
        assertEquals("tap", entry.toolName)
        assertEquals("Node not found", entry.error)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun enqueueCreateNoteSuccess() {
        fakeTransport.enqueue("""
            {"jsonrpc":"2.0","id":1,"result":{
                "content":[{"type":"text","text":"Note created"}],
                "isError":false
            }}
        """)
    }
}
