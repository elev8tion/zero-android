package com.zeroclaw.zero.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zeroclaw.zero.data.AppPrefs
import com.zeroclaw.zero.data.ErrorDatabase
import com.zeroclaw.zero.data.JsonRpcTransport
import com.zeroclaw.zero.data.T0gglesClient
import com.zeroclaw.zero.data.T0gglesException
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
class ErrorLogToolTest {

    private lateinit var errorDb: ErrorDatabase
    private lateinit var tool: QueryErrorLogTool
    private val gson = Gson()

    /** Minimal transport that never gets called (we skip T0ggles logging in these tests). */
    class NoOpTransport : JsonRpcTransport {
        override fun send(jsonBody: String, url: String, apiKey: String): String {
            throw T0gglesException("Should not be called")
        }
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPrefs(context)
        val client = T0gglesClient(prefs, NoOpTransport())
        val testScope = TestScope(UnconfinedTestDispatcher())
        errorDb = ErrorDatabase(client, prefs, testScope)
        tool = QueryErrorLogTool(errorDb)
    }

    // ── Definition ────────────────────────────────────────────────────────────

    @Test
    fun `tool has correct definition`() {
        assertEquals("query_error_log", tool.definition.name)
        assertEquals("workflow", tool.definition.category)
        assertTrue(tool.definition.description.isNotBlank())
        assertTrue("query" in tool.definition.params)
        assertFalse(tool.definition.params["query"]!!.required)
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    @Test
    fun `execute with query returns matching errors as JSON`() {
        errorDb.recordError("tap", mapOf("x" to 100), "Node not found", logToT0ggles = false)
        errorDb.recordError("swipe", emptyMap(), "Out of bounds", logToT0ggles = false)
        errorDb.recordError("tap", emptyMap(), "Service stopped", logToT0ggles = false)

        val result = tool.execute(mapOf("query" to "tap"))

        assertTrue(result.success)
        val entries: List<Map<String, Any>> = gson.fromJson(
            result.data,
            object : TypeToken<List<Map<String, Any>>>() {}.type
        )
        assertEquals(2, entries.size)
        assertTrue(entries.all { it["tool"] == "tap" })
    }

    @Test
    fun `execute with empty query returns all recent errors`() {
        errorDb.recordError("tap", emptyMap(), "error1", logToT0ggles = false)
        errorDb.recordError("swipe", emptyMap(), "error2", logToT0ggles = false)

        val result = tool.execute(mapOf("query" to ""))

        assertTrue(result.success)
        val entries: List<Map<String, Any>> = gson.fromJson(
            result.data,
            object : TypeToken<List<Map<String, Any>>>() {}.type
        )
        assertEquals(2, entries.size)
    }

    @Test
    fun `execute with no query param returns all recent errors`() {
        errorDb.recordError("tap", emptyMap(), "error1", logToT0ggles = false)

        val result = tool.execute(emptyMap())

        assertTrue(result.success)
        assertTrue(result.data.contains("tap"))
    }

    @Test
    fun `execute returns empty message when no errors logged`() {
        val result = tool.execute(emptyMap())

        assertTrue(result.success)
        assertTrue(result.data.contains("No errors found"))
    }

    @Test
    fun `execute returns no-match message for unmatched query`() {
        errorDb.recordError("tap", emptyMap(), "error1", logToT0ggles = false)

        val result = tool.execute(mapOf("query" to "bluetooth"))

        assertTrue(result.success)
        assertTrue(result.data.contains("No errors found"))
        assertTrue(result.data.contains("bluetooth"))
    }
}
