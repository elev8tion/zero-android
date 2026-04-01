package com.zeroclaw.zero.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for ScreenContent/ScreenElement data classes.
 * Validates JSON serialization and summary() logic on real device runtime.
 */
@RunWith(AndroidJUnit4::class)
class ScreenContentTest {

    private fun element(
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        className: String? = "android.widget.TextView",
        bounds: String = "0,0,100,50",
        isClickable: Boolean = false,
        isScrollable: Boolean = false,
        isEditable: Boolean = false,
        isEnabled: Boolean = true,
        depth: Int = 0
    ) = ScreenElement(
        text, contentDescription, resourceId, className, bounds,
        isClickable, isScrollable, isEditable, isEnabled, depth
    )

    // ── toJson() ──────────────────────────────────────────────────────────────

    @Test
    fun emptyScreenContentProducesValidJson() {
        val sc = ScreenContent("com.test.app", emptyList(), 1000L)
        val json = JSONObject(sc.toJson())

        assertEquals("com.test.app", json.getString("packageName"))
        assertEquals(1000L, json.getLong("timestamp"))
        assertEquals(0, json.getJSONArray("elements").length())
    }

    @Test
    fun screenContentWithElementsRoundTrips() {
        val elements = listOf(
            element(text = "OK", isClickable = true, bounds = "10,20,30,40"),
            element(contentDescription = "Close", className = "android.widget.ImageButton")
        )
        val sc = ScreenContent("com.example", elements, 2000L)
        val json = JSONObject(sc.toJson())

        assertEquals(2, json.getJSONArray("elements").length())

        val first = json.getJSONArray("elements").getJSONObject(0)
        assertEquals("OK", first.getString("text"))
        assertTrue(first.getBoolean("isClickable"))
        assertEquals("10,20,30,40", first.getString("bounds"))

        val second = json.getJSONArray("elements").getJSONObject(1)
        assertTrue(second.isNull("text"))
        assertEquals("Close", second.getString("contentDescription"))
        assertEquals("android.widget.ImageButton", second.getString("className"))
    }

    @Test
    fun toJsonIncludesAllElementFields() {
        val el = element(
            text = "Search",
            contentDescription = "Search field",
            resourceId = "com.app:id/search",
            className = "android.widget.EditText",
            bounds = "0,100,1080,200",
            isClickable = true,
            isScrollable = false,
            isEditable = true,
            isEnabled = true,
            depth = 3
        )
        val sc = ScreenContent("pkg", listOf(el), 0L)
        val obj = JSONObject(sc.toJson()).getJSONArray("elements").getJSONObject(0)

        assertEquals("Search", obj.getString("text"))
        assertEquals("Search field", obj.getString("contentDescription"))
        assertEquals("com.app:id/search", obj.getString("resourceId"))
        assertEquals("android.widget.EditText", obj.getString("className"))
        assertEquals("0,100,1080,200", obj.getString("bounds"))
        assertTrue(obj.getBoolean("isClickable"))
        assertFalse(obj.getBoolean("isScrollable"))
        assertTrue(obj.getBoolean("isEditable"))
        assertTrue(obj.getBoolean("isEnabled"))
        assertEquals(3, obj.getInt("depth"))
    }

    @Test
    fun toJsonHandlesNullFields() {
        val el = element(text = null, contentDescription = null, resourceId = null, className = null)
        val sc = ScreenContent("pkg", listOf(el), 0L)
        val obj = JSONObject(sc.toJson()).getJSONArray("elements").getJSONObject(0)

        assertTrue(obj.isNull("text"))
        assertTrue(obj.isNull("contentDescription"))
        assertTrue(obj.isNull("resourceId"))
        assertTrue(obj.isNull("className"))
    }

    // ── summary() ─────────────────────────────────────────────────────────────

    @Test
    fun summaryOnEmptyContentReturnsNoVisibleElements() {
        val sc = ScreenContent("pkg", emptyList(), 0L)
        assertEquals("(no visible elements)", sc.summary())
    }

    @Test
    fun summaryOnElementsWithNoTextReturnsNoVisibleElements() {
        // Elements with no text AND no contentDescription are filtered out
        val sc = ScreenContent("pkg", listOf(
            element(text = null, contentDescription = null, isClickable = true)
        ), 0L)
        assertEquals("(no visible elements)", sc.summary())
    }

    @Test
    fun summarySortsClickableEditableFirst() {
        val elements = listOf(
            element(text = "Static", depth = 0),
            element(text = "Button", isClickable = true, depth = 1),
            element(text = "Input", isEditable = true, depth = 2)
        )
        val sc = ScreenContent("pkg", elements, 0L)
        val lines = sc.summary().lines()

        // Clickable/editable come first, then static
        assertTrue(lines[0].startsWith("Button|") || lines[0].startsWith("Input|"))
        assertTrue(lines.last().startsWith("Static|"))
    }

    @Test
    fun summaryCapsAt50Elements() {
        val elements = (1..100).map {
            element(text = "Item $it", depth = it % 10)
        }
        val sc = ScreenContent("pkg", elements, 0L)
        val lines = sc.summary().lines()
        assertEquals(50, lines.size)
    }

    @Test
    fun summaryFormatsAsLabelClassBounds() {
        val sc = ScreenContent("pkg", listOf(
            element(text = "Settings", className = "android.widget.TextView", bounds = "0,0,500,100")
        ), 0L)
        assertEquals("Settings|TextView|0,0,500,100", sc.summary())
    }

    @Test
    fun summaryUsesContentDescriptionWhenTextIsNull() {
        val sc = ScreenContent("pkg", listOf(
            element(contentDescription = "Navigate up", className = "android.widget.ImageButton", bounds = "0,0,48,48")
        ), 0L)
        assertEquals("Navigate up|ImageButton|0,0,48,48", sc.summary())
    }

    @Test
    fun summaryStripsNewlinesFromText() {
        val sc = ScreenContent("pkg", listOf(
            element(text = "Line1\nLine2", bounds = "0,0,100,50")
        ), 0L)
        assertTrue(sc.summary().contains("Line1 Line2"))
        assertFalse(sc.summary().contains("\nLine2"))
    }
}
