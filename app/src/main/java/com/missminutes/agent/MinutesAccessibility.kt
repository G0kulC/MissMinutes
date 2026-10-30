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

/** Lets Pico see the screen and use any app: read, tap, type, scroll, Home, Back, Lock. */
/**
 * A Clock app row's texts ("9:15", "AM", "Mon to Sat") → 24-hour time and the repeat days; null if no time.
 * Handles "9:15 AM" in one text, 24-hour "21:15", and the narrow spaces some Clock apps put after the time.
 */
fun parseRow(texts: List<String>): Triple<Int, Int, String>? {
    val clean = texts.map { it.replace('\u202f', ' ').replace('\u00a0', ' ').trim() }
    val timeAt = clean.indexOfFirst { Regex("""^\d{1,2}[:.]\d{2}(\s*[AaPp]\.?[Mm]\.?)?$""").matches(it) }
    if (timeAt < 0) return null
    val m = Regex("""^(\d{1,2})[:.](\d{2})\s*([AaPp])?""").find(clean[timeAt]) ?: return null
    var hour = m.groupValues[1].toInt()
    val minute = m.groupValues[2].toInt()
    val half = m.groupValues[3].ifEmpty {
        clean.firstOrNull { Regex("^[AaPp]\\.?[Mm]\\.?$").matches(it) }?.take(1).orEmpty()
    }.lowercase()
    if (half == "p" && hour < 12) hour += 12
    if (half == "a" && hour == 12) hour = 0
    if (hour > 23 || minute > 59) return null
    val days = clean.filterIndexed { i, t ->
        i != timeAt && !Regex("^[AaPp]\\.?[Mm]\\.?$").matches(t) && !t.equals("on", true) && !t.equals("off", true)
    }.firstOrNull().orEmpty()
    return Triple(hour, minute, days)
}

class MinutesAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: MinutesAccessibility? = null
        private val SEND_IDS = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        private const val SETTLE_MS = 700L
        private const val MAX_ITEMS = 120
        /** What each Quick Settings tile is called on different phones (Pixel, Samsung, OnePlus/Oppo, Xiaomi). */
        private val TILE_NAMES = mapOf(
            "hotspot" to listOf("hotspot", "personal hotspot", "mobile hotspot", "portable hotspot", "tethering"),
            "wifi" to listOf("wi-fi", "wifi", "wlan"),
            "bluetooth" to listOf("bluetooth"),
            "mobile_data" to listOf("mobile data", "cellular data", "data"),
            "airplane_mode" to listOf("airplane mode", "aeroplane mode", "flight mode", "airplane"),
            "location" to listOf("location", "gps"),
            "auto_rotate" to listOf("auto-rotate", "auto rotate", "rotate", "auto-rotation"),
            "dark_mode" to listOf("dark mode", "dark theme"),
            "battery_saver" to listOf("battery saver", "power saving", "power saver", "battery saving"),
            "nfc" to listOf("nfc"),
            "night_light" to listOf("night light", "eye comfort", "eye protection", "reading mode", "night mode"),
            "do_not_disturb" to listOf("do not disturb", "dnd"),
        )
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
        // Upper part of the screen, clear of Pico's card at the bottom.
        val (from, to) = when (direction) {
            "down" -> m.heightPixels * 0.55f to m.heightPixels * 0.18f
            "up" -> m.heightPixels * 0.18f to m.heightPixels * 0.55f
            else -> return "ERROR: direction must be up or down"
        }
        val ok = gesture(Path().apply { moveTo(x, from); lineTo(x, to) }, 320)
        delay(SETTLE_MS)
        return if (ok) "Scrolled $direction." else "ERROR: couldn't scroll"
    }

    // ---------- Music ----------

    /**
     * In a music app's search results, taps the first song (a row marked "Song •"), or the artist's Shuffle when
     * there's no song row. YouTube Music answers "play from search" with results only, so this is the "play" press.
     */
    suspend fun playFirstResult(pkg: String): Boolean {
        repeat(20) {
            val root = windows.mapNotNull { it.root }.firstOrNull { it.packageName == pkg }
            if (root != null) {
                val song = findBy(root) { n -> labelOf(n).let { Regex("^(Song|Video)\\s*[•·]").containsMatchIn(it) } }
                val target = song?.parent ?: findBy(root) { labelOf(it).equals("Shuffle", true) }
                if (target != null) return tapCenter(target) || click(target)
            }
            delay(250)
        }
        return false
    }

    // ---------- Alarms ----------

    /** One alarm as the Clock app lists it. */
    class AlarmRow(val hour: Int, val minute: Int, val days: String, val on: Boolean?, val switch: AccessibilityNodeInfo?) {
        val time: String get() = "%d:%02d %s".format(if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")
        val key: String get() = "$hour:$minute|$days"
        fun describe() = "$time, ${days.ifEmpty { "no repeat shown" }}, " + when (on) { true -> "on"; false -> "off"; null -> "state unknown" }
    }

    /**
     * Every alarm in the Clock app (opened at its alarm list by [open]), on or off, scrolling to the end of the list.
     * Clock apps differ, so a row is found from its on/off switch: the time and days are the texts beside it.
     */
    suspend fun alarms(pkg: String, open: () -> Unit): List<AlarmRow>? {
        open()
        if (waitForRows(pkg).isNullOrEmpty()) { leave(); delay(400); open(); waitForRows(pkg) ?: return null }
        toTop(pkg)
        val all = LinkedHashMap<String, AlarmRow>()
        var still = 0
        for (page in 0 until 14) {
            val before = all.size
            alarmRows(pkg).forEach { all.putIfAbsent(it.key, it) }
            // Nothing new twice running: the end of the list (once may just be the header folding away).
            if (page > 0 && all.size == before) { if (++still >= 2) break } else still = 0
            swipeList(pkg, later = true)
        }
        return all.values.toList()
    }

    /** Turns the alarm at [hour]:[minute] on or off with its switch, then checks the switch really moved. */
    suspend fun switchAlarm(pkg: String, hour: Int, minute: Int, on: Boolean, todayOnly: Boolean, open: () -> Unit): String {
        val row = findAlarm(pkg, hour, minute, open)
            ?: return "ERROR: there's no alarm at %d:%02d in the Clock app. Use list_alarms to see them, or set_alarm to make one.".format(hour, minute)
        if (row.on == on) return "The ${row.time} alarm (${row.days}) was already ${if (on) "on" else "off"}."
        val sw = row.switch ?: return "ERROR: found the ${row.time} alarm but not its switch."
        // A real tap: the OnePlus Clock flips its switch for an accessibility "click" but doesn't save it.
        if (!(tapCenter(sw) || click(sw))) return "ERROR: couldn't tap the ${row.time} alarm's switch."
        delay(900)
        // Turning off a repeating alarm can ask first ("Turn off for today" / "Turn off recurring alarm" / "Cancel").
        val choice = if (!on) answerTurnOffDialog(pkg, todayOnly) else null
        if (choice != null) {
            delay(800)
            leave()
            return if (choice == TODAY) "SKIPPED_TODAY: chose \"Turn off for today\" for the ${row.time} alarm (${row.days}); it rings again on its next day."
            else "The ${row.time} alarm (${row.days}) is now off on every day (chose \"Turn off recurring alarm\")."
        }
        delay(300)
        // Only trust what's saved: close the Clock app, open it again and read the alarm afresh.
        leave()
        delay(500)
        val saved = findAlarm(pkg, hour, minute, open)?.on
        val repeats = row.days.isNotBlank() && !row.days.contains("once", true) && !row.days.contains("today", true) &&
            !row.days.contains("tomorrow", true)
        return when (saved) {
            on -> "The ${row.time} alarm (${row.days}) is now ${if (on) "on" else "off"}." +
                if (!on && repeats) " It repeats ${row.days}, so it stays off on those days too until it's switched back on." else ""
            null -> "Tapped the ${row.time} alarm's switch, but couldn't check it afterwards."
            else -> "ERROR: tapped the ${row.time} alarm's switch, but the Clock app still has it ${if (saved) "on" else "off"}."
        }
    }

    private val TODAY = "today"

    /**
     * Answers the Clock app's "turn off a repeating alarm?" question: just today's ring, or the alarm for good.
     * Returns which was chosen, or null when no such question appeared.
     */
    private suspend fun answerTurnOffDialog(pkg: String, todayOnly: Boolean): String? {
        val todayWords = listOf("turn off for today", "skip today", "turn off today", "only today", "just this once", "skip once", "skip next")
        val allWords = listOf("turn off recurring", "turn off all", "turn off repeating", "turn off every", "turn off alarm")
        repeat(6) {
            val root = clockRoot(pkg)
            if (root != null) {
                val today = findBy(root) { n -> labelOf(n).lowercase().let { l -> todayWords.any { l.startsWith(it) } } }
                val all = findBy(root) { n -> labelOf(n).lowercase().let { l -> allWords.any { l.startsWith(it) } } }
                val pick = if (todayOnly) today ?: all else all ?: today
                if (pick != null) {
                    if (!(click(pick) || tapCenter(pick))) return null
                    PicoLog.i("Alarm dialog: chose \"${labelOf(pick)}\"")
                    return if (pick === today) TODAY else "all"
                }
            }
            delay(200)
        }
        return null
    }

    /** Opens the alarm list and finds the alarm at [hour]:[minute], scrolling from the top. */
    private suspend fun findAlarm(pkg: String, hour: Int, minute: Int, open: () -> Unit): AlarmRow? {
        open()
        if (waitForRows(pkg).isNullOrEmpty()) { leave(); delay(400); open(); waitForRows(pkg) ?: return null }
        toTop(pkg)
        val seen = HashSet<String>()
        var still = 0
        for (page in 0 until 14) {
            val rows = alarmRows(pkg)
            // An exact match, or the same clock time if AM/PM was unclear ("9:15") and only one fits.
            (rows.firstOrNull { it.hour == hour && it.minute == minute }
                ?: rows.filter { it.hour % 12 == hour % 12 && it.minute == minute }.singleOrNull())?.let { return it }
            if (page > 0 && rows.all { it.key in seen }) { if (++still >= 2) return null } else still = 0 // the end of the list
            rows.forEach { seen += it.key }
            swipeList(pkg, later = true)
        }
        return null
    }

    /** The Clock app remembers where its list was scrolled to: start from the top so no alarm is missed. */
    private suspend fun toTop(pkg: String) {
        // The first swipe may only open out the app's big header, so stop after two swipes that change nothing.
        var still = 0
        repeat(10) {
            val first = alarmRows(pkg).firstOrNull()?.key
            swipeList(pkg, later = false)
            if (alarmRows(pkg).firstOrNull()?.key == first) { if (++still >= 2) return } else still = 0
        }
    }

    /**
     * Swipes the alarm list like a finger: up for later alarms, down for earlier ones. (The list's own "scroll"
     * action moved it but left nothing readable afterwards.) It swipes on the left side, clear of Pico's ghost.
     */
    private suspend fun swipeList(pkg: String, later: Boolean) {
        val r = Rect()
        val list = clockRoot(pkg)?.let { root ->
            val all = ArrayList<AccessibilityNodeInfo>()
            fun collect(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 30) return
                if (n.isScrollable) all += n
                for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, depth + 1) }
            }
            collect(root, 0)
            all.maxByOrNull { n -> Rect().also { n.getBoundsInScreen(it) }.let { it.width() * it.height() } }
        }
        if (list != null) list.getBoundsInScreen(r)
        if (r.isEmpty) resources.displayMetrics.let { m -> r.set(0, (m.heightPixels * 0.2f).toInt(), m.widthPixels, (m.heightPixels * 0.75f).toInt()) }
        val x = r.left + r.width() * 0.3f
        val high = r.top + r.height() * 0.25f
        val low = r.top + r.height() * 0.8f
        gesture(Path().apply { if (later) { moveTo(x, low); lineTo(x, high) } else { moveTo(x, high); lineTo(x, low) } }, 300)
        delay(800)
    }

    /** Leaves the Clock app once the job is done. */
    fun leave() { performGlobalAction(GLOBAL_ACTION_BACK) }

    private suspend fun waitForRows(pkg: String): List<AlarmRow>? {
        repeat(12) {
            val rows = alarmRows(pkg)
            if (rows.isNotEmpty()) return rows
            delay(250)
        }
        return if (clockRoot(pkg) != null) emptyList() else null
    }

    /** The Clock app's window (Pico's own floating ghost is another window, never the "active" one to read). */
    private fun clockRoot(pkg: String): AccessibilityNodeInfo? =
        windows.mapNotNull { it.root }.firstOrNull { it.packageName == pkg } ?: rootInActiveWindow?.takeIf { it.packageName == pkg }

    private fun alarmRows(pkg: String): List<AlarmRow> {
        val root = clockRoot(pkg) ?: return emptyList()
        val switches = ArrayList<AccessibilityNodeInfo>()
        fun collect(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            if (n.isCheckable && n.isVisibleToUser) switches += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, depth + 1) }
        }
        collect(root, 0)
        return switches.mapNotNull { sw ->
            // Climb from the switch until the row holds a time.
            var row: AccessibilityNodeInfo? = sw.parent
            var parsed: Triple<Int, Int, String>? = null
            for (level in 0 until 4) {
                val texts = row?.let { textsIn(it) } ?: break
                parsed = parseRow(texts)
                if (parsed != null) break
                row = row.parent
            }
            parsed?.let { (h, m, days) -> AlarmRow(h, m, days, sw.isChecked, sw) }
        }
    }

    private fun textsIn(node: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12) return
            if (!n.isCheckable) n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(node, 0)
        return out
    }

    /**
     * Switches a Quick Settings tile (Hotspot, Wi-Fi, Bluetooth…) the way you would: pull down the panel, find the
     * tile, tap it only if it isn't already as asked, then close the panel. Much quicker and steadier than hunting
     * for the switch inside the Settings app, and it works for toggles Android won't let apps flip directly.
     * With [on] null it only reports whether the tile is on.
     */
    suspend fun quickSetting(tile: String, on: Boolean?): String {
        val key = tile.lowercase().trim().replace(' ', '_').replace("-", "")
        val names = TILE_NAMES[key] ?: listOf(tile.lowercase().trim())
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        delay(800)
        try {
            for (page in 0 until 3) {
                val node = findTile(names)
                if (node != null) {
                    val label = labelOf(node).substringBefore(',').ifEmpty { tile }.take(40)
                    val before = tileState(node)
                    if (on == null) return "$label is ${stateWord(before)}."
                    if (before == on) return "$label was already ${stateWord(on)}."
                    if (!(click(node) || tapCenter(node))) return "ERROR: couldn't tap the $label tile"
                    delay(900)
                    val after = findTile(names)?.let { tileState(it) }
                    return when (after) {
                        on -> "$label is now ${stateWord(on)}."
                        null -> "Tapped the $label tile."
                        else -> "ERROR: tapped $label, but it is still ${stateWord(after)}. The phone may be asking the user to confirm."
                    }
                }
                // Not on this page of Quick Settings: swipe to the next one.
                val m = resources.displayMetrics
                gesture(Path().apply {
                    moveTo(m.widthPixels * 0.85f, m.heightPixels * 0.3f); lineTo(m.widthPixels * 0.15f, m.heightPixels * 0.3f)
                }, 250)
                delay(600)
            }
            return "ERROR: no \"$tile\" tile in Quick Settings. Use open_settings for that page instead, or the user can add the tile."
        } finally {
            // One step that closes the whole panel; pressing Back twice could also leave the app underneath.
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            else performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /** The tile among the open windows (the panel is its own window, not the app on screen). */
    private fun findTile(names: List<String>): AccessibilityNodeInfo? {
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        for (root in roots) {
            findBy(root) { n ->
                val label = labelOf(n).lowercase()
                label.isNotEmpty() && names.any { label == it || label.startsWith("$it,") || label.startsWith("$it ") }
            }?.let { return it }
        }
        return null
    }

    /** On, off, or null when the tile doesn't say; tiles report it in different places on different phones. */
    private fun tileState(node: AccessibilityNodeInfo): Boolean? {
        var n: AccessibilityNodeInfo? = node
        repeat(4) {
            val cur = n ?: return null
            if (cur.isCheckable) return cur.isChecked
            val words = listOfNotNull(
                if (Build.VERSION.SDK_INT >= 30) cur.stateDescription else null, cur.contentDescription, cur.text,
            ).joinToString(" ").lowercase()
            if (Regex("""\b(off|disabled|turned off)\b""").containsMatchIn(words)) return false
            if (Regex("""\b(on|enabled|connected|turned on)\b""").containsMatchIn(words)) return true
            n = cur.parent
        }
        return null
    }

    private fun stateWord(on: Boolean?) = when (on) { true -> "on"; false -> "off"; null -> "in an unknown state" }

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
