package com.zeroclaw.zero.accessibility

import org.json.JSONArray
import org.json.JSONObject

data class ScreenElement(
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val className: String?,
    val bounds: String,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean,
    val depth: Int
)

data class ScreenContent(
    val packageName: String,
    val elements: List<ScreenElement>,
    val timestamp: Long
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("packageName", packageName)
        root.put("timestamp", timestamp)
        val arr = JSONArray()
        for (el in elements) {
            val obj = JSONObject()
            obj.put("text", el.text ?: JSONObject.NULL)
            obj.put("contentDescription", el.contentDescription ?: JSONObject.NULL)
            obj.put("resourceId", el.resourceId ?: JSONObject.NULL)
            obj.put("className", el.className ?: JSONObject.NULL)
            obj.put("bounds", el.bounds)
            obj.put("isClickable", el.isClickable)
            obj.put("isScrollable", el.isScrollable)
            obj.put("isEditable", el.isEditable)
            obj.put("isEnabled", el.isEnabled)
            obj.put("depth", el.depth)
            arr.put(obj)
        }
        root.put("elements", arr)
        return root.toString()
    }

    fun summary(): String {
        val filtered = elements
            .filter { it.text != null || it.contentDescription != null }
            .sortedWith(compareByDescending<ScreenElement> { it.isClickable || it.isEditable }.thenBy { it.depth })
            .take(50)
        if (filtered.isEmpty()) return "(no visible elements)"
        return buildString {
            for (el in filtered) {
                val label = (el.text ?: el.contentDescription)!!.replace('\n', ' ').trim()
                val cls = el.className?.substringAfterLast('.') ?: "View"
                appendLine("$label|$cls|${el.bounds}")
            }
        }.trimEnd()
    }
}
