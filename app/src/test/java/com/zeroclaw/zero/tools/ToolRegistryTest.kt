package com.zeroclaw.zero.tools

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolRegistryTest {

    private lateinit var registry: ToolRegistry
    private val context: Context = mockk(relaxed = true)

    @Before
    fun setup() {
        registry = ToolRegistry(context)
    }

    // ── Registration ────────────────────────────────────────────────────────────

    @Test
    fun `register tool makes it available`() {
        registry.register(stubTool("test_tool", "test"))
        assertTrue(registry.hasToolNamed("test_tool"))
        assertEquals(1, registry.toolCount())
    }

    @Test
    fun `register returns registry for chaining`() {
        val result = registry.register(stubTool("a", "cat"))
        assertSame(registry, result)
    }

    @Test
    fun `register multiple tools across categories`() {
        registry.register(stubTool("tool_a", "gesture"))
        registry.register(stubTool("tool_b", "screen"))
        registry.register(stubTool("tool_c", "device"))
        assertEquals(3, registry.toolCount())
    }

    @Test
    fun `duplicate name overwrites previous tool`() {
        registry.register(stubTool("dup", "cat1"))
        registry.register(stubTool("dup", "cat2"))
        assertEquals(1, registry.toolCount())
        assertEquals("cat2", registry.getDefinition("dup")?.category)
    }

    @Test
    fun `unregister removes tool`() {
        registry.register(stubTool("tool_a", "cat"))
        registry.unregister("tool_a")
        assertFalse(registry.hasToolNamed("tool_a"))
        assertEquals(0, registry.toolCount())
    }

    @Test
    fun `unregister nonexistent tool is no-op`() {
        registry.unregister("ghost")
        assertEquals(0, registry.toolCount())
    }

    // ── Lookup ──────────────────────────────────────────────────────────────────

    @Test
    fun `hasToolNamed returns false for unknown tool`() {
        assertFalse(registry.hasToolNamed("unknown_tool"))
    }

    @Test
    fun `getDefinition returns null for unknown tool`() {
        assertNull(registry.getDefinition("unknown_tool"))
    }

    @Test
    fun `getDefinition returns correct definition`() {
        val tool = stubTool("my_tool", "system",
            params = mapOf("level" to ToolParam("integer", "brightness level", true)))
        registry.register(tool)
        val def = registry.getDefinition("my_tool")
        assertNotNull(def)
        assertEquals("my_tool", def!!.name)
        assertEquals("system", def.category)
        assertEquals(1, def.params.size)
        assertTrue(def.params.containsKey("level"))
    }

    @Test
    fun `getDefinitions returns sorted by category then name`() {
        registry.register(stubTool("z_tool", "b_cat"))
        registry.register(stubTool("a_tool", "b_cat"))
        registry.register(stubTool("m_tool", "a_cat"))
        val defs = registry.getDefinitions()
        assertEquals("m_tool", defs[0].name)  // a_cat first
        assertEquals("a_tool", defs[1].name)  // b_cat, a before z
        assertEquals("z_tool", defs[2].name)
    }

    // ── Execution ───────────────────────────────────────────────────────────────

    @Test
    fun `execute unknown tool returns error result`() {
        val result = registry.execute("nonexistent")
        assertFalse(result.success)
        assertEquals("Unknown tool: nonexistent", result.error)
    }

    @Test
    fun `execute known tool returns its result`() {
        registry.register(stubTool("echo", "test", result = ToolResult(true, data = "hello")))
        val result = registry.execute("echo")
        assertTrue(result.success)
        assertEquals("hello", result.data)
    }

    @Test
    fun `execute passes params to tool`() {
        var capturedParams: Map<String, Any?> = emptyMap()
        val tool = object : Tool {
            override val definition = ToolDefinition("capture", "captures params", "test")
            override fun execute(params: Map<String, Any?>): ToolResult {
                capturedParams = params
                return ToolResult(true, data = "ok")
            }
        }
        registry.register(tool)
        registry.execute("capture", mapOf("key" to "value", "num" to 42))
        assertEquals("value", capturedParams["key"])
        assertEquals(42, capturedParams["num"])
    }

    @Test
    fun `execute catches tool exception and returns error`() {
        val throwingTool = object : Tool {
            override val definition = ToolDefinition("bomb", "throws", "test")
            override fun execute(params: Map<String, Any?>): ToolResult {
                throw RuntimeException("boom")
            }
        }
        registry.register(throwingTool)
        val result = registry.execute("bomb")
        assertFalse(result.success)
        assertTrue(result.error!!.contains("boom"))
    }

    @Test
    fun `execute with empty params is fine`() {
        registry.register(stubTool("no_args", "test", result = ToolResult(true, data = "ok")))
        val result = registry.execute("no_args", emptyMap())
        assertTrue(result.success)
    }

    // ── getToolsForQuery (keyword filtering) ────────────────────────────────────

    @Test
    fun `getToolsForQuery always includes core tools`() {
        val coreNames = listOf(
            "get_screen_state", "click_node", "long_click_node", "type_text",
            "submit_text", "navigate_back", "navigate_home", "launch_app",
            "find_app", "get_current_app", "await_screen_change", "take_screenshot"
        )
        coreNames.forEach { registry.register(stubTool(it, "core")) }
        registry.register(stubTool("send_sms", "contacts"))

        val result = registry.getToolsForQuery("hello world")
        val names = result.map { it.name }.toSet()
        coreNames.forEach { assertTrue("Missing core tool: $it", it in names) }
        assertFalse("send_sms should not be included", "send_sms" in names)
    }

    @Test
    fun `getToolsForQuery includes contact tools for sms keyword`() {
        registry.register(stubTool("get_screen_state", "core"))
        registry.register(stubTool("send_sms", "contacts"))
        registry.register(stubTool("make_call", "contacts"))

        val result = registry.getToolsForQuery("send a text message")
        val names = result.map { it.name }.toSet()
        assertTrue("send_sms" in names)
        assertTrue("make_call" in names)
    }

    @Test
    fun `getToolsForQuery includes device tools for bluetooth keyword`() {
        registry.register(stubTool("get_screen_state", "core"))
        registry.register(stubTool("get_bluetooth_status", "device"))

        val result = registry.getToolsForQuery("turn on bluetooth")
        val names = result.map { it.name }.toSet()
        assertTrue("get_bluetooth_status" in names)
    }

    @Test
    fun `getToolsForQuery includes system tools for volume keyword`() {
        registry.register(stubTool("get_screen_state", "core"))
        registry.register(stubTool("set_volume", "system"))

        val result = registry.getToolsForQuery("set the volume to 50")
        val names = result.map { it.name }.toSet()
        assertTrue("set_volume" in names)
    }

    @Test
    fun `getToolsForQuery includes gesture tools for swipe keyword`() {
        registry.register(stubTool("get_screen_state", "core"))
        registry.register(stubTool("swipe", "gesture"))

        val result = registry.getToolsForQuery("swipe up on the screen")
        val names = result.map { it.name }.toSet()
        assertTrue("swipe" in names)
    }

    @Test
    fun `getToolsForQuery is case insensitive`() {
        registry.register(stubTool("send_sms", "contacts"))
        val result = registry.getToolsForQuery("SEND A TEXT MESSAGE")
        val names = result.map { it.name }.toSet()
        assertTrue("send_sms" in names)
    }

    @Test
    fun `getToolsForQuery with no registered tools returns empty`() {
        val result = registry.getToolsForQuery("hello")
        assertTrue(result.isEmpty())
    }

    // ── Tool name uniqueness ────────────────────────────────────────────────────

    @Test
    fun `all tool names are unique after bulk registration`() {
        val names = listOf("tool_a", "tool_b", "tool_c", "tool_d")
        names.forEach { registry.register(stubTool(it, "test")) }
        val defs = registry.getDefinitions()
        val defNames = defs.map { it.name }
        assertEquals(defNames.size, defNames.toSet().size)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun stubTool(
        name: String,
        category: String,
        params: Map<String, ToolParam> = emptyMap(),
        result: ToolResult = ToolResult(true, data = "stub result")
    ): Tool = object : Tool {
        override val definition = ToolDefinition(name, "Test tool: $name", category, params)
        override fun execute(params: Map<String, Any?>): ToolResult = result
    }
}
