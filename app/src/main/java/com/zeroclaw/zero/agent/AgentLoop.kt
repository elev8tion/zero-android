package com.zeroclaw.zero.agent

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.tools.ToolDefinition
import com.zeroclaw.zero.tools.ToolParam
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG            = "AgentLoop"
private const val MAX_ITERATIONS = 12       // max tool-call rounds before giving up
private val     JSON_MEDIA       = "application/json".toMediaType()

/**
 * Zero's on-phone AI brain.
 *
 * Takes a user utterance → asks Claude (via session proxy) → executes any
 * tool calls Claude requests (on-device) → loops until Claude returns a final
 * text answer → delivers that answer via [Listener.onResponse].
 *
 * Conversation history accumulates across turns so Claude has context.
 * Call [reset] to start a fresh session.
 */
class AgentLoop {

    // ── Public interface ───────────────────────────────────────────────────────

    interface Listener {
        /** Claude is thinking / proxy call in-flight. */
        fun onThinking()
        /** About to execute a tool on-device. */
        fun onToolCall(name: String)
        /** Final plain-text answer — speak this to the user. */
        fun onResponse(text: String)
        /** Something went wrong — tell the user. */
        fun onError(message: String)
    }

    var listener: Listener? = null

    /** Whether the loop is currently processing a request. */
    val isRunning: Boolean get() = running

    // ── Internals ─────────────────────────────────────────────────────────────

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson   = Gson()
    private val http   = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Full conversation history — appended after each user turn + tool round. */
    private val history = mutableListOf<Map<String, Any>>()

    @Volatile private var running = false

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Process a user utterance.  Non-blocking — result arrives via [Listener].
     * If a previous call is still in-flight it is ignored (one request at a time).
     */
    fun process(userInput: String) {
        if (running) {
            Log.w(TAG, "Already running, ignoring new input")
            return
        }
        running = true
        scope.launch {
            try {
                runAgentLoop(userInput)
            } catch (e: CancellationException) {
                // normal cancellation — no-op
            } catch (e: Exception) {
                Log.e(TAG, "AgentLoop error", e)
                notify { listener?.onError(e.message ?: "Unknown error") }
            } finally {
                running = false
            }
        }
    }

    /** Cancel any in-flight work. */
    fun cancel() {
        scope.coroutineContext.cancelChildren()
        running = false
    }

    /** Clear conversation history — next [process] starts a fresh session. */
    fun reset() {
        history.clear()
    }

    // ── Core loop ─────────────────────────────────────────────────────────────

    private suspend fun runAgentLoop(userInput: String) {
        val app      = ZeroApp.instance
        val proxyUrl = app.prefs.proxyUrl.trimEnd('/')

        // Append user message to history
        history += msg("user", userInput)

        // Select only tools relevant to this query (token optimisation)
        val toolDefs = app.toolRegistry
            .getToolsForQuery(userInput)
            .map { it.toSerialMap() }

        notify { listener?.onThinking() }

        var iterations = 0
        while (iterations < MAX_ITERATIONS) {
            iterations++

            // ── Call proxy ─────────────────────────────────────────────────
            val reqBody = gson.toJson(
                mapOf(
                    "model"    to "claude-sonnet-4-6",
                    "messages" to history,
                    "tools"    to toolDefs,
                )
            ).toRequestBody(JSON_MEDIA)

            val httpResp = http.newCall(
                Request.Builder()
                    .url("$proxyUrl/api/chat")
                    .post(reqBody)
                    .build()
            ).execute()

            val bodyStr = httpResp.body?.string()
                ?: throw RuntimeException("Empty response from proxy")

            if (!httpResp.isSuccessful) {
                val err = tryParseError(bodyStr) ?: "HTTP ${httpResp.code}"
                throw RuntimeException("Proxy error: $err")
            }

            @Suppress("UNCHECKED_CAST")
            val parsed  = gson.fromJson(bodyStr, Map::class.java) as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val message = parsed["message"] as? Map<String, Any>
                ?: throw RuntimeException("Malformed proxy response (no 'message')")

            val doneReason = parsed["done_reason"] as? String ?: "stop"

            // ── Tool call? ─────────────────────────────────────────────────
            if (doneReason == "tool_calls") {
                @Suppress("UNCHECKED_CAST")
                val toolCalls = message["tool_calls"] as? List<Map<String, Any>>
                val func      = (toolCalls?.firstOrNull()?.get("function") as? Map<*, *>)
                val toolName  = func?.get("name") as? String
                    ?: throw RuntimeException("tool_call missing name")
                val argsJson  = func["arguments"] as? String ?: "{}"

                @Suppress("UNCHECKED_CAST")
                val params = gson.fromJson<Map<String, Any?>>(
                    argsJson, object : TypeToken<Map<String, Any?>>() {}.type
                ) ?: emptyMap()

                Log.d(TAG, "[$iterations] tool_call → $toolName  args=$argsJson")
                notify { listener?.onToolCall(toolName) }

                // Record assistant's tool-call turn in history
                history += mapOf(
                    "role"       to "assistant",
                    "content"    to "",
                    "tool_calls" to listOf(
                        mapOf("function" to mapOf("name" to toolName, "arguments" to argsJson))
                    )
                )

                // Execute tool on-device
                val result     = app.toolRegistry.execute(toolName, params)
                val resultText = if (result.success) result.data
                                 else "Error: ${result.error}"

                Log.d(TAG, "[$iterations] tool result (${resultText.length} chars): ${resultText.take(120)}")

                // Append tool result — proxy maps this to a Human: turn
                history += mapOf(
                    "role"    to "tool",
                    "name"    to toolName,
                    "content" to resultText,
                )

                notify { listener?.onThinking() }
                continue  // → next proxy call with updated history
            }

            // ── Final text response ────────────────────────────────────────
            val content = message["content"] as? String ?: ""
            if (content.isNotBlank()) {
                // Append assistant turn so future calls retain context
                history += msg("assistant", content)
                app.prefs.lastResponse = content
                notify { listener?.onResponse(content) }
            } else {
                notify { listener?.onError("No response from Claude") }
            }
            return
        }

        // Exceeded iteration cap
        Log.w(TAG, "Hit max iterations ($MAX_ITERATIONS)")
        notify { listener?.onError("Task took too many steps — try rephrasing") }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun notify(block: () -> Unit) =
        withContext(Dispatchers.Main) { block() }

    private fun msg(role: String, content: String): Map<String, Any> =
        mapOf("role" to role, "content" to content)

    private fun tryParseError(body: String): String? = runCatching {
        @Suppress("UNCHECKED_CAST")
        (gson.fromJson(body, Map::class.java) as Map<String, Any>)["error"] as? String
    }.getOrNull()
}

// ── ToolDefinition → plain Map (JSON-serialisable) ────────────────────────────

private fun ToolDefinition.toSerialMap(): Map<String, Any> = mapOf(
    "name"        to name,
    "description" to description,
    "params"      to params.mapValues { (_, p) -> p.toSerialMap() }
)

private fun ToolParam.toSerialMap(): Map<String, Any> = mapOf(
    "type"        to type,
    "description" to description,
    "required"    to required,
)
