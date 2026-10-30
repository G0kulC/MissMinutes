package com.missminutes.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Chat for me": the assistant keeps one chat going while the user is busy, replying as their assistant
 * ("Pico here, Gokul's assistant…"), by itself, until they say stop (at most 12 hours). Only that person, only
 * one-to-one, never to its own or anyone's automatic replies.
 */
object ChatForMe {
    const val ACTION_STOP = "com.missminutes.agent.CHAT_STOP"
    private const val LONGEST_MS = 12 * 60 * 60_000L
    private const val MAX_REPLIES = 60
    private const val NOTIF_ID = 81
    private const val CHANNEL = "pico_chat"

    /** Who the assistant is chatting with, in which app, since when. */
    data class Session(val who: String, val app: String, val since: Long)

    /** One line of the chat: theirs or the assistant's. */
    data class Line(val mine: Boolean, val text: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private var lastText = ""
    private var lastAt = 0L

    fun session(prefs: Prefs): Session? = runCatching {
        val o = JSONObject(prefs.chatJson)
        Session(o.getString("who"), o.getString("app"), o.getLong("since"))
    }.getOrNull()?.takeIf { System.currentTimeMillis() - it.since < LONGEST_MS }

    fun lines(prefs: Prefs): List<Line> = runCatching {
        val a = JSONArray(prefs.chatLogJson)
        List(a.length()) { a.getJSONObject(it).let { o -> Line(o.optBoolean("mine"), o.optString("text")) } }
    }.getOrDefault(emptyList())

    private fun addLine(prefs: Prefs, line: Line) {
        prefs.chatLogJson = JSONArray((lines(prefs) + line).takeLast(30).map { JSONObject().put("mine", it.mine).put("text", it.text) }).toString()
    }

    fun start(context: Context, prefs: Prefs, who: String, app: String): String {
        val name = who.trim()
        if (name.isEmpty()) return "ERROR: whose chat? Use read_screen to see which chat is open, then give its name."
        prefs.chatJson = JSONObject().put("who", name).put("app", app.ifBlank { "WhatsApp" }).put("since", System.currentTimeMillis()).toString()
        prefs.chatLogJson = "[]"
        // What they said last, so the first reply answers it.
        NotificationReader.recent(name, 3).reversed().forEach { addLine(prefs, Line(false, it.text)) }
        notify(context, prefs)
        PicoLog.i("Chat for me: started with $name on $app")
        val latest = NotificationReader.latestFrom(name)
        if (latest != null) scope.launch { reply(context, prefs, latest) }
        return "Chat for me is on: I'll reply to $name on ${app.ifBlank { "WhatsApp" }} as your assistant until you say stop." +
            if (latest == null) " Nothing new from them yet; I'll answer their next message." else " Answering their last message now."
    }

    fun stop(context: Context, prefs: Prefs): String {
        val s = session(prefs) ?: return "Chat for me wasn't on."
        val sent = lines(prefs).count { it.mine }
        prefs.chatJson = ""
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        PicoLog.i("Chat for me: stopped with ${s.who} after $sent replies")
        return "Stopped chatting with ${s.who}. I sent $sent message${if (sent == 1) "" else "s"}." +
            lines(prefs).lastOrNull { !it.mine }?.let { " Their last message: “${it.text.take(100)}”." }.orEmpty()
    }

    /** A new chat message: true if it belongs to the chat the assistant is handling (so nothing else answers it). */
    fun onMessage(context: Context, prefs: Prefs, message: NotificationReader.Message, group: Boolean): Boolean {
        val s = session(prefs) ?: return false
        if (group || !message.sender.equals(s.who, ignoreCase = true) || message.app != s.app) return false
        val text = message.text.trim()
        // WhatsApp posts each message twice, and our own reply comes back too: answer real, new messages only.
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < 30_000) return true
        if (lines(prefs).lastOrNull()?.let { it.mine && it.text == text } == true) return true
        if (Away.isOwnReply(text, prefs.assistantName) || Away.isAutoReply(text)) return true
        lastText = text; lastAt = now
        if (lines(prefs).count { it.mine } >= MAX_REPLIES) { stop(context, prefs); return true }
        scope.launch { reply(context, prefs, message) }
        return true
    }

    private suspend fun reply(context: Context, prefs: Prefs, message: NotificationReader.Message) {
        val s = session(prefs) ?: return
        addLine(prefs, Line(false, message.text))
        val answer = write(prefs, s) ?: return
        val sent = NotificationReader.reply(context, message, answer) ||
            (NotificationReader.latestFrom(s.who)?.let { NotificationReader.reply(context, it, answer) } ?: false)
        if (sent) { addLine(prefs, Line(true, answer)); notify(context, prefs) }
        PicoLog.i("Chat for me: ${if (sent) "replied to" else "couldn't reply to"} ${s.who}: ${answer.take(120)}")
    }

    /** The assistant's next message in the chat, from Claude. */
    private fun write(prefs: Prefs, s: Session): String? {
        val user = prefs.userName.trim().split(' ').first().ifEmpty { "the user" }
        val me = prefs.assistantName
        val first = lines(prefs).none { it.mine }
        val system = buildString {
            appendLine("You are $me, $user's personal AI assistant. $user is busy and asked you to chat with ${s.who} on ${s.app} for them.")
            appendLine(if (first) "This is your first message: start by saying you're $me, $user's assistant, chatting for them while they're busy."
                else "You've already introduced yourself; don't do it again.")
            appendLine("Write ONE short, friendly, casual chat message (one or two sentences), in the language ${s.who} uses " +
                "(Tamil in Tamil script, English, or Tanglish as they write). No quotes, no labels, just the message.")
            appendLine("Never agree to plans, money, meetings or promises for $user, and never share private details: say you'll pass it on to $user.")
            prefs.memories.takeIf { it.isNotEmpty() }?.let { appendLine("Things you know about $user (share only harmless ones): " + it.take(20).joinToString("; ")) }
        }
        val messages = JSONArray()
        for (line in lines(prefs)) {
            val role = if (line.mine) "assistant" else "user"
            // Messages must alternate; join any in a row from the same side.
            val last = if (messages.length() > 0) messages.getJSONObject(messages.length() - 1) else null
            if (last != null && last.getString("role") == role) last.put("content", last.getString("content") + "\n" + line.text)
            else messages.put(JSONObject().put("role", role).put("content", line.text))
        }
        if (messages.length() == 0 || messages.getJSONObject(messages.length() - 1).getString("role") != "user") return null
        val body = JSONObject().put("model", BrainModel.FAST.id).put("max_tokens", 300)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("system", system).put("messages", messages)
        val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", prefs.claudeKey).header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            http.newCall(req).execute().use { r ->
                val o = JSONObject(r.body!!.string())
                if (!r.isSuccessful) { PicoLog.w("Chat for me: Claude said ${r.code} ${o.optJSONObject("error")?.optString("message")}"); return null }
                val c = o.getJSONArray("content")
                List(c.length()) { c.getJSONObject(it) }.filter { it.optString("type") == "text" }
                    .joinToString("") { it.optString("text") }.trim().removeSurrounding("\"").takeIf { it.isNotEmpty() }
            }
        }.onFailure { PicoLog.w("Chat for me: couldn't write a reply", it) }.getOrNull()
    }

    /** "Pico is chatting with Arun · 3 replies", with Stop. */
    private fun notify(context: Context, prefs: Prefs) {
        val s = session(prefs) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chat for me", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while your assistant is chatting with someone for you"
        })
        val stop = PendingIntent.getBroadcast(context, 12, Intent(context, ReminderReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val sent = lines(prefs).count { it.mine }
        nm.notify(NOTIF_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_minutes)
            .setColor(Palette.AMBER)
            .setContentTitle("${prefs.assistantName} is chatting with ${s.who}")
            .setContentText(if (sent == 0) "Waiting for their message on ${s.app}." else "$sent repl${if (sent == 1) "y" else "ies"} sent on ${s.app}.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build())
    }
}
