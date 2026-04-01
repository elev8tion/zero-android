package com.zeroclaw.zero.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ZeroA11y"

class ZeroAccessibilityService : AccessibilityService() {

    // nid map — rebuilt on each get_screen_state, reused by click_node / long_click_node
    private val nodeMap = HashMap<String, AccessibilityNodeInfo>()
    private var nodeCounter = 0

    // screen-change signal for await_screen_change
    @Volatile private var screenChangedFlag = false

    companion object {
        @Volatile var instance: ZeroAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        // ── gesture helpers (existing) ──
        fun tap(x: Float, y: Float): Boolean = instance?.performTap(x, y) ?: false
        fun longTap(x: Float, y: Float, durationMs: Long = 500L): Boolean =
            instance?.performLongTap(x, y, durationMs) ?: false
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean =
            instance?.performSwipe(x1, y1, x2, y2, durationMs) ?: false
        fun scroll(direction: String): Boolean = instance?.performScroll(direction) ?: false
        fun typeText(text: String): Boolean = instance?.performTypeText(text) ?: false
        fun clearText(): Boolean = instance?.performTypeText("") ?: false
        fun submitText(): Boolean = instance?.performSubmitText() ?: false
        fun pressBack(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        fun pressHome(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        fun pressRecents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        fun showAllApps(): Boolean = instance?.performShowAllApps() ?: false

        // ── screen content ──
        fun getScreenContent(): ScreenContent =
            instance?.buildScreenContent() ?: ScreenContent("", emptyList(), System.currentTimeMillis())
        fun getScreenState(): String =
            instance?.buildScreenState() ?: "accessibility_service_not_running"

        // ── nid-based interaction ──
        fun clickByNid(nid: String): Boolean = instance?.performClickByNid(nid) ?: false
        fun longClickByNid(nid: String): Boolean = instance?.performLongClickByNid(nid) ?: false

        // ── legacy helpers ──
        fun findAndClick(text: String): Boolean = instance?.performFindAndClick(text) ?: false
        fun findAndClickByResourceId(resourceId: String): Boolean =
            instance?.performFindAndClickByResourceId(resourceId) ?: false
        fun findEditableAndType(text: String): Boolean =
            instance?.performFindEditableAndType(text) ?: false

        // ── utility ──
        fun awaitScreenChange(timeoutMs: Long = 5000L): Boolean =
            instance?.performAwaitScreenChange(timeoutMs) ?: false
        fun takeScreenshot(quality: Int = 80): String? =
            instance?.performTakeScreenshot(quality)
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "ZeroAccessibilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "ZeroAccessibilityService disconnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                screenChangedFlag = true
                Log.v(TAG, "Window → ${event.packageName}")
            }
        }
    }

    override fun onInterrupt() {}

    // ── Gesture helpers ──────────────────────────────────────────────────────

    private fun dispatchSync(gesture: GestureDescription): Boolean {
        var result = false
        val latch = CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { result = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription) { latch.countDown() }
        }, null)
        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.1f, y) }
        return dispatchSync(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L)).build())
    }

    private fun performLongTap(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.1f, y) }
        return dispatchSync(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs)).build())
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchSync(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs)).build())
    }

    private fun performScroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val m = dm.heightPixels * 0.25f
        return when (direction.lowercase()) {
            "up"    -> performSwipe(cx, cy + m, cx, cy - m, 400L)
            "down"  -> performSwipe(cx, cy - m, cx, cy + m, 400L)
            "left"  -> performSwipe(cx + m, cy, cx - m, cy, 400L)
            "right" -> performSwipe(cx - m, cy, cx + m, cy, 400L)
            else    -> { Log.w(TAG, "Unknown direction: $direction"); false }
        }
    }

    private fun performShowAllApps(): Boolean {
        // Go home first, then swipe up to open app drawer
        performGlobalAction(GLOBAL_ACTION_HOME)
        Thread.sleep(500)
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        return performSwipe(cx, dm.heightPixels * 0.8f, cx, dm.heightPixels * 0.2f, 600L)
    }

    // ── Text input ───────────────────────────────────────────────────────────

    private fun findFocusedOrEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i) ?: continue)
            if (found != null) return found
        }
        return null
    }

    private fun performTypeText(text: String): Boolean {
        val node = findFocusedOrEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun performSubmitText(): Boolean {
        val node = findFocusedOrEditable() ?: return false
        // Try custom IME actions first (send/done/search labels)
        for (action in node.actionList) {
            val label = action.label?.toString()?.lowercase() ?: ""
            if (label.contains("send") || label.contains("done") ||
                label.contains("submit") || label.contains("search") || label.contains("go")) {
                if (node.performAction(action.id)) return true
            }
        }
        // Fallback: click the field (triggers IME submit on many widgets)
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ── nid-based interaction ─────────────────────────────────────────────────

    private fun performClickByNid(nid: String): Boolean {
        val node = nodeMap[nid] ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun performLongClickByNid(nid: String): Boolean {
        val node = nodeMap[nid] ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    // ── Find & click (legacy) ──────────────────────────────────────────────────

    private fun performFindAndClick(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findByText(root, text)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun performFindAndClickByResourceId(resourceId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findById(root, resourceId)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun performFindEditableAndType(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findFirstEditable(root) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findByText(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        if (t?.contains(query, true) == true || d?.contains(query, true) == true) return node
        for (i in 0 until node.childCount) {
            val found = findByText(node.getChild(i) ?: continue, query)
            if (found != null) return found
        }
        return null
    }

    private fun findById(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName?.contains(id, true) == true) return node
        for (i in 0 until node.childCount) {
            val found = findById(node.getChild(i) ?: continue, id)
            if (found != null) return found
        }
        return null
    }

    // ── Screen content (legacy) ───────────────────────────────────────────────

    private fun buildScreenContent(): ScreenContent {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        val elements = mutableListOf<ScreenElement>()
        if (root != null) traverse(root, elements, 0)
        return ScreenContent(pkg, elements, System.currentTimeMillis())
    }

    private fun traverse(node: AccessibilityNodeInfo, out: MutableList<ScreenElement>, depth: Int) {
        if (depth > 30) return
        val text = node.text?.toString()
        val cd = node.contentDescription?.toString()
        if (text != null || cd != null || node.isClickable || node.isEditable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            out.add(ScreenElement(text, cd, node.viewIdResourceName,
                node.className?.toString(), "${r.left},${r.top},${r.right},${r.bottom}",
                node.isClickable, node.isScrollable, node.isEditable, node.isEnabled, depth))
        }
        for (i in 0 until node.childCount) traverse(node.getChild(i) ?: continue, out, depth + 1)
    }

    // ── Screen state (HTML-like with nid) ────────────────────────────────────

    private fun buildScreenState(): String {
        val root = rootInActiveWindow ?: return "<error>accessibility_service_not_running</error>"
        // Clear old node map and reset counter
        nodeMap.clear()
        nodeCounter = 0
        val sb = StringBuilder()
        val pkg = root.packageName?.toString() ?: "unknown"
        sb.appendLine("<screen pkg=\"$pkg\">")
        buildStateNode(root, sb, 0)
        sb.append("</screen>")
        return sb.toString()
    }

    private fun buildStateNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 25) return
        val text = node.text?.toString()?.replace("\"", "'")?.replace("\n", " ")
        val desc = node.contentDescription?.toString()?.replace("\"", "'")?.replace("\n", " ")
        val r = Rect()
        node.getBoundsInScreen(r)
        val bounds = "${r.left},${r.top},${r.right},${r.bottom}"
        val hasContent = text != null || desc != null || node.isClickable || node.isEditable ||
                         node.isScrollable || node.isFocused

        if (hasContent) {
            val nid = "n${nodeCounter++}"
            // Store live reference so click_node can use it
            nodeMap[nid] = node

            val indent = "  ".repeat(depth)
            sb.append("$indent<n nid=\"$nid\" b=\"$bounds\"")
            if (text != null) sb.append(" txt=\"$text\"")
            if (desc != null && desc != text) sb.append(" dsc=\"$desc\"")
            if (node.isClickable) sb.append(" clickable")
            if (node.isEditable) sb.append(" editable")
            if (node.isScrollable) sb.append(" scrollable")
            if (node.isFocused) sb.append(" focused")
            if (node.isChecked) sb.append(" checked")

            if (node.childCount == 0) {
                sb.appendLine("/>")
            } else {
                sb.appendLine(">")
                for (i in 0 until node.childCount) {
                    buildStateNode(node.getChild(i) ?: continue, sb, depth + 1)
                }
                sb.appendLine("$indent</n>")
            }
        } else {
            // Still traverse children even if this node has no content
            for (i in 0 until node.childCount) {
                buildStateNode(node.getChild(i) ?: continue, sb, depth + 1)
            }
        }
    }

    // ── await_screen_change ───────────────────────────────────────────────────

    private fun performAwaitScreenChange(timeoutMs: Long): Boolean {
        screenChangedFlag = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (screenChangedFlag) return true
            Thread.sleep(100)
        }
        return false
    }

    // ── take_screenshot ───────────────────────────────────────────────────────

    private fun performTakeScreenshot(quality: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return performTakeScreenshotApi30(quality)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun performTakeScreenshotApi30(quality: Int): String? {
        val result = AtomicReference<Bitmap?>()
        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(0, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    try {
                        val buffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                        result.set(hwBitmap?.copy(Bitmap.Config.ARGB_8888, false))
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot copy failed: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed code=$errorCode")
                    latch.countDown()
                }
            })
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot failed: ${e.message}")
            executor.shutdown()
            return null
        }
        executor.shutdown()
        val bitmap = result.get() ?: return null
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap encoding failed: ${e.message}")
            null
        }
    }
}
