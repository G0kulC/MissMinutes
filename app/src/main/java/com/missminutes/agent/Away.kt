package com.missminutes.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "In a meeting until 4": when it started, why, and when it ends (0 = until the user says they're back). */
data class AwayState(val reason: String, val since: Long, val until: Long)

/** Something that happened while the user was away: a call (declined or let through) or a message. */
data class AwayEvent(
    val at: Long,
    val kind: String, // "call" or "message"
    val who: String,
    val text: String? = null,
    val app: String? = null,
    val rang: Boolean = false, // a call that was let through
    val replied: Boolean = false,
)

/**
 * Not-available mode. While it's on, calls are screened ([PicoCallScreener]): people the user picked (starred
 * contacts) and anyone calling twice in a few minutes still ring; everyone else is declined and gets a text saying
 * when the user is back. Chats get the same reply once per person. When it ends, the assistant says what was missed.
 * Declined calls are rejected like a tap on Decline, so the carrier's "forward when busy" can later send them to a
 * phone line where the assistant answers by voice.
 */
object Away {
    const val ACTION_END = "com.missminutes.agent.AWAY_END"
    /** With no end time, it still switches itself off after this long, so it's never left on by mistake. */
    private const val LONGEST_MS = 12 * 60 * 60_000L
    private const val REPEAT_WINDOW_MS = 5 * 60_000L
    private const val NOTIF_ID = 78
    private const val CHANNEL = "pico_away"

    enum class Decision { RING, DECLINE }

    // ---------- State ----------

    fun state(prefs: Prefs): AwayState? = parseState(prefs.awayJson)?.takeIf { active(it, System.currentTimeMillis()) }

    fun active(s: AwayState, now: Long): Boolean = (if (s.until > 0) s.until else s.since + LONGEST_MS) > now

    fun parseState(json: String): AwayState? = runCatching {
        val o = JSONObject(json)
        AwayState(o.getString("reason"), o.getLong("since"), o.optLong("until"))
    }.getOrNull()

    fun events(prefs: Prefs): List<AwayEvent> = parseEvents(prefs.awayLogJson)

    fun start(context: Context, prefs: Prefs, reason: String, until: Long) {
        val now = System.currentTimeMillis()
        prefs.awayJson = JSONObject().put("reason", reason.trim().ifEmpty { "busy" }).put("since", now).put("until", until).toString()
        prefs.awayLogJson = "[]"
        prefs.awayRepliedJson = "[]"
        scheduleEnd(context, if (until > 0) until else now + LONGEST_MS)
        updateNotification(context, prefs)
        PicoLog.i("Away on: $reason, until ${if (until > 0) clock(until) else "back"}")
    }

    /** Switches it off and returns what happened while away, in words. */
    fun end(context: Context, prefs: Prefs): String {
        val s = parseState(prefs.awayJson)
        prefs.awayJson = ""
        cancelEnd(context)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        PicoLog.i("Away off")
        return summary(s, events(prefs), System.currentTimeMillis())
    }

    // ---------- Calls ----------

    /** Ring for the people who always get through and for someone trying again within a few minutes. */
    fun decide(starred: Boolean, letStarred: Boolean, earlierCalls: List<Long>, letRepeat: Boolean, now: Long): Decision = when {
        letStarred && starred -> Decision.RING
        letRepeat && earlierCalls.any { now - it < REPEAT_WINDOW_MS } -> Decision.RING
        else -> Decision.DECLINE
    }

    /** Screens one incoming call while away; null when not away (the call rings as normal). */
    fun screenCall(context: Context, prefs: Prefs, number: String): Decision? {
        val s = state(prefs) ?: return null
        val contact = contact(context, number)
        val who = contact?.first ?: number
        val now = System.currentTimeMillis()
        val earlier = events(prefs).filter { it.kind == "call" && same(it.who, who) }.map { it.at }
        val decision = decide(contact?.second == true, prefs.awayLetStarred, earlier, prefs.awayLetRepeat, now)
        // Unknown numbers (spam, sales calls) are declined but never texted when "contacts only" is on.
        val mayText = contact != null || !prefs.awayContactsOnly
        val texting = decision == Decision.DECLINE && prefs.awayTextCallers && mayText && firstReplyTo(prefs, number)
        log(prefs, AwayEvent(now, "call", who, rang = decision == Decision.RING))
        PicoLog.i("Away: call from $who -> $decision${if (texting) ", texting back" else ""}")
        updateNotification(context, prefs)
        // The call is answered at once; the text goes in the background and is only counted once the phone says it went.
        if (texting) background.launch {
            val problem = Sms.send(context.applicationContext, number, replyText(prefs, s))
            if (problem == null) { markReplied(prefs, who, now); PicoLog.i("Away: text to $who sent") }
            else { forgetReply(prefs, number); PicoLog.w("Away: text to $who not sent: $problem") }
        }
        return decision
    }

    // ---------- Messages ----------

    /** Notes a chat message while away, and answers it once per person (1-to-1 chats only, never groups or brands). */
    fun onMessage(context: Context, prefs: Prefs, message: NotificationReader.Message, group: Boolean) {
        val s = state(prefs) ?: return
        // Our own reply arriving on the phone's other WhatsApp account: not a message from anyone.
        if (isOwnReply(message.text, prefs.assistantName) || message.text.trim() == replyText(prefs, s).trim()) return
        var replied = false
        // Never answer an auto-reply, or two auto-replies would answer each other forever. It's still listed.
        // Offers, bank alerts and OTPs come from senders that aren't saved contacts, so "contacts only" skips them.
        val key = "${message.app}:${message.sender}"
        if (prefs.awayReplyChats && !group && !isAutoReply(message.text) && looksLikePerson(message.sender) && message.app in REPLY_APPS &&
            (!prefs.awayContactsOnly || inContacts(context, message.sender)) && firstReplyTo(prefs, key)
        ) {
            // Not every version of a chat's notification has a Reply button: try the newest one from them too.
            replied = NotificationReader.reply(context, message, replyText(prefs, s)) ||
                (NotificationReader.latestFrom(message.sender)?.takeIf { it.app == message.app }
                    ?.let { NotificationReader.reply(context, it, replyText(prefs, s)) } ?: false)
            if (replied) PicoLog.i("Away: replied to ${message.sender} on ${message.app}")
            else { forgetReply(prefs, key); PicoLog.w("Away: couldn't reply to ${message.sender} on ${message.app} (no reply button)") }
        }
        val missedCall = Regex("^missed (voice|video|group voice|group video) call", RegexOption.IGNORE_CASE).containsMatchIn(message.text.trim())
        log(prefs, if (missedCall) AwayEvent(System.currentTimeMillis(), "call", message.sender, app = message.app, replied = replied)
            else AwayEvent(System.currentTimeMillis(), "message", message.sender, message.text.take(200), message.app, replied = replied))
        updateNotification(context, prefs)
    }

    /** This app's own reply ("…This is Pico, Gokul's assistant…"), whatever the assistant is called. */
    fun isOwnReply(text: String, assistantName: String): Boolean {
        val t = text.lowercase()
        return Regex("this is \\S+(\\s\\S+)?, \\S+'s assistant").containsMatchIn(t) || "this is ${assistantName.lowercase()}," in t
    }

    /** Another app's automatic answer ("out of office", "auto-reply"). */
    fun isAutoReply(text: String): Boolean {
        val t = text.lowercase()
        return AUTO_WORDS.any { it in t }
    }

    private val AUTO_WORDS = listOf(
        "auto-reply", "auto reply", "automatic reply", "autoreply", "out of office", "currently unavailable",
        "can't take calls", "cannot take calls", "will get back to you", "i'm away", "i am away",
    )

    private val REPLY_APPS = setOf("WhatsApp", "WhatsApp Business", "Messages", "Telegram")

    /** "Ravi", "Amma", "+91 98…" yes; "JX-HDFCBK", "AD-AIRTEL", "Jio" style sender IDs no. */
    fun looksLikePerson(sender: String): Boolean =
        !Regex("^[A-Z]{2}-[A-Z0-9]{3,}").containsMatchIn(sender.trim()) && sender.any { it.isLetter() || it.isDigit() }

    // ---------- Words ----------

    fun replyText(prefs: Prefs, s: AwayState, template: String = prefs.awayMessage): String {
        val custom = template.trim()
        val first = prefs.userName.trim().split(' ').first()
        if (custom.isNotEmpty()) return custom.replace("{until}", if (s.until > 0) clock(s.until) else "later today")
            .replace("{name}", first.ifEmpty { "me" })
            .let(::tidyTime)
        val user = prefs.userName.trim().split(' ').first().ifEmpty { "They" }
        val back = if (s.until > 0) " until ${clock(s.until)}" else " right now"
        return "Hi, $user is ${s.reason}$back and can't take calls. This is ${prefs.assistantName}, $user's assistant. " +
            "I'll let them know you got in touch."
    }

    /** "While you were in a meeting: Ravi called twice, Amma called (it rang through). 2 messages: Ravi on WhatsApp: …" */
    fun summary(s: AwayState?, events: List<AwayEvent>, now: Long): String {
        val during = s?.let { "While you were ${it.reason}" } ?: "While you were away"
        if (events.isEmpty()) return "$during, nobody called or messaged."
        val parts = ArrayList<String>()
        val calls = events.filter { it.kind == "call" }.groupBy { it.who }
        if (calls.isNotEmpty()) {
            parts += calls.entries.take(6).joinToString(", ") { (who, list) ->
                val times = when (list.size) { 1 -> "called"; 2 -> "called twice"; else -> "called ${list.size} times" }
                val on = list.mapNotNull { it.app }.distinct().joinToString(" and ").takeIf { it.isNotEmpty() }?.let { " on $it" }.orEmpty()
                who + " " + times + on + if (list.any { it.rang }) " (it rang through)" else ""
            } + if (calls.size > 6) ", and ${calls.size - 6} others called" else ""
        }
        val messages = events.filter { it.kind == "message" }
        if (messages.isNotEmpty()) {
            val byPerson = messages.groupBy { it.who }
            val lines = byPerson.entries.take(5).joinToString("; ") { (who, list) ->
                "$who on ${list.last().app ?: "chat"}: “${list.last().text.orEmpty().take(80)}”" +
                    if (list.size > 1) " (${list.size} messages)" else ""
            }
            parts += "${messages.size} message${if (messages.size == 1) "" else "s"}: $lines"
        }
        return "$during: " + parts.joinToString(". ") + "."
    }

    /**
     * "{until} AM" in a reply would read "10:14 AM AM" (or "PM AM"): keep the first. Android puts a narrow no-break
     * space before AM/PM, which a plain space match missed.
     */
    fun tidyTime(text: String) = text.replace(Regex("(?i)\\b(AM|PM)[\\s\\u00a0\\u202f]+(?:AM|PM)\\b"), "$1")

    fun clock(at: Long): String = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(at))

    // ---------- Helpers ----------

    private fun same(a: String, b: String) = a.equals(b, ignoreCase = true)

    private fun firstReplyTo(prefs: Prefs, key: String): Boolean {
        val done = runCatching { JSONArray(prefs.awayRepliedJson) }.getOrDefault(JSONArray())
        for (i in 0 until done.length()) if (done.optString(i) == key) return false
        prefs.awayRepliedJson = done.put(key).toString()
        return true
    }

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** A send failed: let the next call or message from them try again. */
    @Synchronized
    private fun forgetReply(prefs: Prefs, key: String) {
        val done = runCatching { JSONArray(prefs.awayRepliedJson) }.getOrDefault(JSONArray())
        prefs.awayRepliedJson = JSONArray((0 until done.length()).map { done.optString(it) }.filter { it != key }).toString()
    }

    @Synchronized
    private fun markReplied(prefs: Prefs, who: String, at: Long) {
        prefs.awayLogJson = encodeEvents(events(prefs).map { if (it.kind == "call" && it.at == at && same(it.who, who)) it.copy(replied = true) else it })
    }

    @Synchronized
    private fun log(prefs: Prefs, e: AwayEvent) {
        prefs.awayLogJson = encodeEvents(append(events(prefs), e))
    }

    /** WhatsApp posts the same message twice (the chat and its update): count it once. */
    fun append(list: List<AwayEvent>, e: AwayEvent): List<AwayEvent> {
        val repeat = e.kind == "message" && list.any {
            it.kind == "message" && same(it.who, e.who) && it.text == e.text && e.at - it.at < 30_000
        }
        return if (repeat) list else (list + e).takeLast(100)
    }

    fun parseEvents(json: String): List<AwayEvent> = runCatching {
        val a = JSONArray(json)
        List(a.length()) {
            val o = a.getJSONObject(it)
            AwayEvent(o.optLong("at"), o.optString("kind"), o.optString("who"), o.optString("text").ifEmpty { null },
                o.optString("app").ifEmpty { null }, o.optBoolean("rang"), o.optBoolean("replied"))
        }
    }.getOrDefault(emptyList())

    fun encodeEvents(list: List<AwayEvent>): String = JSONArray(list.map { e ->
        JSONObject().put("at", e.at).put("kind", e.kind).put("who", e.who).put("rang", e.rang).put("replied", e.replied)
            .apply { e.text?.let { put("text", it) }; e.app?.let { put("app", it) } }
    }).toString()

    /**
     * A chat's sender is someone saved in Contacts: chat apps show a saved contact by name, so a name must match a
     * contact exactly; a number is looked up. Sender IDs like "JX-HDFCBK" or "Jio" match nothing.
     */
    fun inContacts(context: Context, sender: String): Boolean {
        val s = sender.trim()
        if (s.isEmpty()) return false
        if (s.count { it.isDigit() } >= 7 && s.none { it.isLetter() }) return contact(context, s) != null
        return runCatching {
            context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(s), null)?.use { it.count > 0 } == true
        }.getOrDefault(false)
    }

    /** The contact's name and whether they're starred (a favourite), or null for an unknown number. */
    private fun contact(context: Context, number: String): Pair<String, Boolean>? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.STARRED),
            null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) to (c.getInt(1) == 1) else null }
    }.getOrNull()

    private fun endIntent(context: Context) = PendingIntent.getBroadcast(
        context, 8, Intent(context, ReminderReceiver::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleEnd(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(context)) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, endIntent(context)) }
    }

    private fun cancelEnd(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(endIntent(context))

    /** A quiet, ongoing notification: "In a meeting until 4:00 PM · 2 calls, 3 messages". */
    fun updateNotification(context: Context, prefs: Prefs) {
        val s = state(prefs) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Not available", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while your assistant is handling calls and messages for you"
        })
        val ev = events(prefs)
        val calls = ev.count { it.kind == "call" }
        val msgs = ev.count { it.kind == "message" }
        val open = PendingIntent.getActivity(context, 9, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_minutes)
            .setColor(Palette.AMBER)
            .setContentTitle("Not available: ${s.reason}" + if (s.until > 0) " until ${clock(s.until)}" else "")
            .setContentText(if (calls + msgs == 0) "${prefs.assistantName} is handling your calls and messages."
                else "$calls call${if (calls == 1) "" else "s"}, $msgs message${if (msgs == 1) "" else "s"} so far")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build())
    }
}
