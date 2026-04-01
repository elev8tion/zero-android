package com.zeroclaw.zero.agent

import com.google.gson.Gson
import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.tools.ToolDefinition
import com.zeroclaw.zero.tools.ToolRegistry
import com.zeroclaw.zero.tools.ToolResult
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockWebServer: MockWebServer
    private lateinit var agentLoop: AgentLoop
    private lateinit var mockListener: AgentLoop.Listener
    private lateinit var mockApp: ZeroApp
    private lateinit var mockPrefs: AppPrefs
    private lateinit var mockToolRegistry: ToolRegistry
    private val gson = Gson()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockWebServer = MockWebServer()
        mockWebServer.start()

        mockPrefs = mockk(relaxed = true)
        every { mockPrefs.proxyUrl } returns mockWebServer.url("/").toString().trimEnd('/')

        mockToolRegistry = mockk(relaxed = true)
        every { mockToolRegistry.getToolsForQuery(any()) } returns emptyList()

        mockApp = mockk(relaxed = true)
        every { mockApp.prefs } returns mockPrefs
        every { mockApp.toolRegistry } returns mockToolRegistry

        // Set ZeroApp.instance via reflection (companion object static field)
        val field = ZeroApp::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, mockApp)

        agentLoop = AgentLoop()
        mockListener = mockk(relaxed = true)
        agentLoop.listener = mockListener
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        agentLoop.cancel()
        mockWebServer.shutdown()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun enqueueTextResponse(text: String) {
        val body = gson.toJson(mapOf(
            "message" to mapOf("content" to text),
            "done_reason" to "stop"
        ))
        mockWebServer.enqueue(MockResponse().setBody(body).setResponseCode(200))
    }

    private fun enqueueToolCallResponse(toolName: String, args: String = "{}") {
        val body = gson.toJson(mapOf(
            "message" to mapOf(
                "content" to "",
                "tool_calls" to listOf(
                    mapOf("function" to mapOf("name" to toolName, "arguments" to args))
                )
            ),
            "done_reason" to "tool_calls"
        ))
        mockWebServer.enqueue(MockResponse().setBody(body).setResponseCode(200))
    }

    private fun enqueueErrorResponse(code: Int, body: String = "") {
        mockWebServer.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun waitForCompletion(timeoutMs: Long = 3000) {
        // AgentLoop runs on IO dispatcher; wait for it to finish
        Thread.sleep(timeoutMs)
    }

    // ── Happy path ──────────────────────────────────────────────────────────────

    @Test
    fun `process with text response fires onThinking then onResponse`() {
        enqueueTextResponse("Hello! How can I help?")

        agentLoop.process("hi")
        waitForCompletion()

        verifyOrder {
            mockListener.onThinking()
            mockListener.onResponse("Hello! How can I help?")
        }
    }

    @Test
    fun `process sends user message in request body`() {
        enqueueTextResponse("ok")

        agentLoop.process("open settings")
        waitForCompletion()

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        val body = request!!.body.readUtf8()
        assertTrue("Request should contain user input", body.contains("open settings"))
    }

    @Test
    fun `process sends to correct proxy path`() {
        enqueueTextResponse("ok")

        agentLoop.process("test")
        waitForCompletion()

        val request = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/api/chat", request?.path)
    }

    // ── Concurrent call rejection ───────────────────────────────────────────────

    @Test
    fun `process while already running is ignored`() {
        // First call: delay the response so it's still "running"
        mockWebServer.enqueue(
            MockResponse().setBody(gson.toJson(mapOf(
                "message" to mapOf("content" to "first"),
                "done_reason" to "stop"
            ))).setBodyDelay(2, TimeUnit.SECONDS)
        )

        agentLoop.process("first")
        Thread.sleep(100) // let the coroutine start

        // Second call should be ignored
        agentLoop.process("second")
        waitForCompletion(3000)

        // Only one request should have been made
        assertEquals(1, mockWebServer.requestCount)
    }

    // ── Cancel ──────────────────────────────────────────────────────────────────

    @Test
    fun `cancel resets running flag`() {
        mockWebServer.enqueue(
            MockResponse().setBody(gson.toJson(mapOf(
                "message" to mapOf("content" to "delayed"),
                "done_reason" to "stop"
            ))).setBodyDelay(5, TimeUnit.SECONDS)
        )

        agentLoop.process("long task")
        Thread.sleep(100)
        agentLoop.cancel()
        Thread.sleep(200)

        // Should be able to process again after cancel
        enqueueTextResponse("after cancel")
        agentLoop.process("new task")
        waitForCompletion()

        verify { mockListener.onResponse("after cancel") }
    }

    // ── Reset ───────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears history — next call starts fresh`() {
        enqueueTextResponse("first response")
        agentLoop.process("first")
        waitForCompletion()

        agentLoop.reset()

        enqueueTextResponse("second response")
        agentLoop.process("second")
        waitForCompletion()

        // The second request should NOT contain "first" in the history
        mockWebServer.takeRequest(1, TimeUnit.SECONDS) // first request
        val secondReq = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(secondReq)
        val body = secondReq!!.body.readUtf8()
        assertFalse("History should be cleared", body.contains("first response"))
    }

    // ── Error handling ──────────────────────────────────────────────────────────

    @Test
    fun `HTTP error fires onError with status code`() {
        enqueueErrorResponse(500)

        agentLoop.process("test")
        waitForCompletion()

        verify { mockListener.onError(match { it.contains("500") || it.contains("Proxy error") }) }
    }

    @Test
    fun `HTTP 401 fires onError`() {
        enqueueErrorResponse(401, gson.toJson(mapOf("error" to "Unauthorized")))

        agentLoop.process("test")
        waitForCompletion()

        verify { mockListener.onError(match { it.contains("Unauthorized") || it.contains("401") }) }
    }

    @Test
    fun `malformed JSON response fires onError`() {
        mockWebServer.enqueue(MockResponse().setBody("not json at all {{{").setResponseCode(200))

        agentLoop.process("test")
        waitForCompletion()

        verify { mockListener.onError(any()) }
    }

    @Test
    fun `response missing message field fires onError`() {
        mockWebServer.enqueue(MockResponse()
            .setBody(gson.toJson(mapOf("something" to "else")))
            .setResponseCode(200))

        agentLoop.process("test")
        waitForCompletion()

        verify { mockListener.onError(match { it.contains("message") || it.contains("Malformed") }) }
    }

    @Test
    fun `empty content in response fires onError`() {
        val body = gson.toJson(mapOf(
            "message" to mapOf("content" to ""),
            "done_reason" to "stop"
        ))
        mockWebServer.enqueue(MockResponse().setBody(body).setResponseCode(200))

        agentLoop.process("test")
        waitForCompletion()

        verify { mockListener.onError("No response from Claude") }
    }

    // ── Tool call pipeline ──────────────────────────────────────────────────────

    @Test
    fun `tool call executes tool and sends result back`() {
        every { mockToolRegistry.execute("launch_app", any()) } returns
            ToolResult(true, data = "Launched Settings")

        enqueueToolCallResponse("launch_app", """{"package":"com.android.settings"}""")
        enqueueTextResponse("I opened Settings for you")

        agentLoop.process("open settings")
        waitForCompletion()

        verifyOrder {
            mockListener.onThinking()
            mockListener.onToolCall("launch_app")
            mockListener.onThinking()
            mockListener.onResponse("I opened Settings for you")
        }

        verify { mockToolRegistry.execute("launch_app", any()) }
    }

    @Test
    fun `tool call with error result sends error text back to proxy`() {
        every { mockToolRegistry.execute("send_sms", any()) } returns
            ToolResult(false, error = "Permission denied")

        enqueueToolCallResponse("send_sms", """{"to":"123","body":"hi"}""")
        enqueueTextResponse("I couldn't send the message — permission denied")

        agentLoop.process("text 123 hi")
        waitForCompletion()

        // Verify the second request contains the error
        mockWebServer.takeRequest(1, TimeUnit.SECONDS) // first
        val secondReq = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(secondReq)
        val body = secondReq!!.body.readUtf8()
        assertTrue("Should contain tool error", body.contains("Permission denied"))
    }

    @Test
    fun `multiple tool calls loop correctly`() {
        every { mockToolRegistry.execute(any(), any()) } returns
            ToolResult(true, data = "done")

        enqueueToolCallResponse("get_screen_state")
        enqueueToolCallResponse("click_node", """{"id":"button1"}""")
        enqueueTextResponse("Done! I clicked the button.")

        agentLoop.process("click the submit button")
        waitForCompletion()

        verify(exactly = 2) { mockToolRegistry.execute(any(), any()) }
        verify { mockListener.onResponse("Done! I clicked the button.") }
    }

    // ── Max iterations ──────────────────────────────────────────────────────────

    @Test
    fun `exceeding max iterations fires onError`() {
        every { mockToolRegistry.execute(any(), any()) } returns
            ToolResult(true, data = "ok")

        // Enqueue 13 tool call responses (MAX_ITERATIONS is 12)
        repeat(13) {
            enqueueToolCallResponse("get_screen_state")
        }

        agentLoop.process("infinite loop task")
        waitForCompletion(5000)

        verify { mockListener.onError(match { it.contains("too many steps") }) }
    }

    // ── Null listener ───────────────────────────────────────────────────────────

    @Test
    fun `process with null listener does not crash`() {
        agentLoop.listener = null
        enqueueTextResponse("response with no listener")

        agentLoop.process("test")
        waitForCompletion()

        // No crash = success
        assertEquals(1, mockWebServer.requestCount)
    }

    // ── History accumulation ────────────────────────────────────────────────────

    @Test
    fun `history accumulates across turns`() {
        enqueueTextResponse("first reply")
        agentLoop.process("first question")
        waitForCompletion()

        enqueueTextResponse("second reply")
        agentLoop.process("second question")
        waitForCompletion()

        // Second request should contain history from first turn
        mockWebServer.takeRequest(1, TimeUnit.SECONDS) // first
        val secondReq = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(secondReq)
        val body = secondReq!!.body.readUtf8()
        assertTrue("Should contain first question", body.contains("first question"))
        assertTrue("Should contain first reply", body.contains("first reply"))
        assertTrue("Should contain second question", body.contains("second question"))
    }

    @Test
    fun `last response saved to prefs`() {
        enqueueTextResponse("save this response")

        agentLoop.process("test")
        waitForCompletion()

        verify { mockPrefs.lastResponse = "save this response" }
    }

    // ── Connection errors ───────────────────────────────────────────────────────

    @Test
    fun `connection refused fires onError`() {
        mockWebServer.shutdown()
        // Point to a port that's not listening
        every { mockPrefs.proxyUrl } returns "http://127.0.0.1:19999"

        agentLoop = AgentLoop()
        agentLoop.listener = mockListener

        agentLoop.process("test")
        waitForCompletion(5000)

        verify { mockListener.onError(any()) }
    }
}
