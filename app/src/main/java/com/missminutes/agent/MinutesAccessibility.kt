package com.missminutes.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Lets Miss Minutes see the screen and use any app: read, tap, type, scroll, Home, Back, Lock. */
class MinutesAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: MinutesAccessibility? = null
        private val SEND_IDS = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        private const val SETTLE_MS = 700L
        private const val MAX_ITEMS = 120
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- WhatsApp ----------

    /** Waits for the WhatsApp chat to open, then taps Send. */
    suspend fun clickWhatsAppSend(timeoutMs: Long = 9000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        delay(600)
        while (System.currentTimeMillis() < end) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString()?.startsWith("com.whatsapp") == true) {
                val button = SEND_IDS.firstNotNullOfOrNull { root.findAccessibilityNodeInfosByViewId(it)?.firstOrNull() }
                    ?: findBy(root) { labelOf(it).equals("send", ignoreCase = true) }
                if (button != null && button.isEnabled && click(button)) return true
            }
            delay(350)
        }
        return false
    }

    // ---------- Seeing the screen ----------

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "ERROR: can't read the screen right now"
        val out = StringBuilder("App on screen: ${appLabel(root.packageName?.toString())}\n")
        var count = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int, insideTappable: Boolean) {
            if (count >= MAX_ITEMS || depth > 60 || !node.isVisibleToUser) return
            val tappable = insideTappable || node.isClickable
            val label = labelOf(node).replace('\n', ' ')
            if (label.isNotEmpty() || node.isEditable) {
                val tags = buildList {
                    if (tappable) add("tap")
                    if (node.isEditable) add("text box")
                    if (node.isCheckable) add(if (node.isChecked) "on" else "off")
                    if (node.isScrollable) add("scrolls")
                }
                out.append("- ").append(label.ifEmpty { "(empty)" }.take(90))
                if (tags.isNotEmpty()) out.append(" [").append(tags.joinToString(", ")).append(']')
                out.append('\n')
                count++
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1, tappable) }
        }

        walk(root, 0, false)
        if (count == 0) out.append("(no readable text on this screen)")
        return out.toString().take(4000)
    }

    // ---------- Acting on the screen ----------

    suspend fun tapText(target: String): String {
        if (target.isBlank()) return "ERROR: say what to tap"
        val root = rootInActiveWindow ?: return "ERROR: can't read the screen right now"
        val want = target.trim().lowercase()
        val node = root.findAccessibilityNodeInfosByText(target).orEmpty()
            .filter { it.isVisibleToUser }
            .minByOrNull { n ->
                val l = labelOf(n).lowercase()
                (if (l == want) 0 else if (l.startsWith(want)) 1 else 2) * 1000 + l.length
            }
            ?: findBy(root) { labelOf(it).lowercase().contains(want) }
            ?: return "ERROR: couldn't find \"$target\" on the screen. Use read_screen to see what's there."
        val label = labelOf(node).ifEmpty { target }
        val done = click(node) || tapCenter(node)
        delay(SETTLE_MS)
        return if (done) "Tapped \"${label.take(60)}\"." else "ERROR: couldn't tap \"$target\""
    }

    suspend fun typeText(text: String, pressEnter: Boolean): String {
        val root = rootInActiveWindow ?: return "ERROR: can't read the screen right now"
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findBy(root) { it.isEditable }
            ?: return "ERROR: no text box on the screen. Tap the search or message box first."
        if (!field.isFocused) {
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(250)
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "ERROR: couldn't type into that box"
        var entered = false
        if (pressEnter && Build.VERSION.SDK_INT >= 30) {
            delay(200)
            entered = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        delay(SETTLE_MS)
        return when {
            !pressEnter -> "Typed \"${text.take(60)}\"."
            entered -> "Typed \"${text.take(60)}\" and pressed Enter."
            else -> "Typed \"${text.take(60)}\", but couldn't press Enter. Tap the search or send button instead."
        }
    }

    suspend fun scroll(direction: String): String {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        // Upper part of the screen, clear of Miss Minutes' card at the bottom.
        val (from, to) = when (direction) {
            "down" -> m.heightPixels * 0.55f to m.heightPixels * 0.18f
            "up" -> m.heightPixels * 0.18f to m.heightPixels * 0.55f
            else -> return "ERROR: direction must be up or down"
        }
        val ok = gesture(Path().apply { moveTo(x, from); lineTo(x, to) }, 320)
        delay(SETTLE_MS)
        return if (ok) "Scrolled $direction." else "ERROR: couldn't scroll"
    }

    // ---------- Helpers ----------

    private fun labelOf(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    private fun findBy(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (depth > 60 || !node.isVisibleToUser) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findBy(child, depth + 1, match)?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private suspend fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 60)
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!sent && cont.isActive) cont.resume(false)
    }

    private fun appLabel(pkg: String?): String {
        if (pkg == null) return "unknown"
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
