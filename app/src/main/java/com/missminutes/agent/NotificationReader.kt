package com.missminutes.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Collects incoming chat notifications so Pico can read them out and reply, and checks every new
 * notification against the watches the user asked for ("tell me when Ravi messages").
 */
class NotificationReader : NotificationListenerService() {

    data class Message(
        val key: String,
        val app: String,
        val sender: String,
        val text: String,
        val time: Long,
        val replyAction: Notification.Action?
    )

    companion object {
        @Volatile var instance: NotificationReader? = null
        private val lock = Any()
        private val messages = ArrayList<Message>()
        private const val MAX = 50
        private val SUMMARY = Regex("^\\d+ new messages?.*", RegexOption.IGNORE_CASE)
        private val CHAT_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.apps.messaging" to "Messages",
            "com.samsung.android.messaging" to "Messages",
            "com.android.mms" to "Messages",
            "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger",
            "com.google.android.gm" to "Gmail"
        )

        /** One chat (or missed calls) still waiting in the notification shade: the unread ones. */
        data class Unread(val app: String, val from: String, val texts: List<String>)

        /** Everything unread right now, newest chats first; null when notification access is off. */
        fun unread(): List<Unread>? {
            val service = instance ?: return null
            val active = runCatching { service.activeNotifications }.getOrNull() ?: return null
            val out = ArrayList<Unread>()
            for (sbn in active.sortedByDescending { it.postTime }) {
                val n = sbn.notification ?: continue
                if (sbn.packageName == service.packageName || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
                val extras = n.extras ?: continue
                val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
                val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                    ?.toString()?.trim().orEmpty()
                val missedCall = n.category == "missed_call" || (title.contains("missed call", ignoreCase = true) &&
                    (sbn.packageName.contains("dialer") || sbn.packageName.contains("telecom") || sbn.packageName.contains("contacts")))
                if (missedCall) {
                    out += Unread("Missed call", if (title.contains("missed", true)) text.ifEmpty { title } else title, emptyList())
                    continue
                }
                val app = CHAT_APPS[sbn.packageName] ?: continue
                if (title.isEmpty() || SUMMARY.matches(text)) continue
                // A chat with several messages keeps them all (MessagingStyle); show the last few.
                @Suppress("DEPRECATION")
                val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                val texts = bundles?.mapNotNull { (it as? Bundle)?.getCharSequence("text")?.toString() }?.takeLast(3)
                    ?.takeIf { it.isNotEmpty() } ?: listOf(text).filter { it.isNotEmpty() }
                if (texts.isEmpty() || title.equals("You", true)) continue
                out += Unread(app, title, texts)
            }
            return out
        }

        /** "Unread: WhatsApp, Ravi (3): “…”; Missed calls: Amma." in words for the assistant to read out. */
        fun describeUnread(list: List<Unread>?): String {
            if (list == null) return "Notification access is off, so unread messages can't be read. The user can allow it in the app's Settings, Permissions."
            if (list.isEmpty()) return "Nothing unread: no new messages or missed calls."
            val calls = list.filter { it.app == "Missed call" }.groupingBy { it.from }.eachCount()
            val chats = list.filter { it.app != "Missed call" }.take(8)
            return buildString {
                if (chats.isNotEmpty()) {
                    append("Unread messages: ")
                    append(chats.joinToString("; ") { u -> "${u.from} on ${u.app}: " + u.texts.joinToString(" / ") { "“${it.take(120)}”" } })
                    if (list.count { it.app != "Missed call" } > chats.size) append("; and more")
                    append(". ")
                }
                if (calls.isNotEmpty()) append("Missed calls: " + calls.entries.joinToString { (who, n) -> if (n > 1) "$who ($n)" else who } + ".")
            }.trim()
        }

        fun recent(from: String?, limit: Int): List<Message> = synchronized(lock) {
            messages.asReversed()
                .filter { from == null || it.sender.contains(from, ignoreCase = true) }
                .take(limit)
        }

        fun latestFrom(sender: String): Message? = synchronized(lock) {
            val q = sender.trim()
            if (q.isEmpty()) return@synchronized null
            messages.asReversed().firstOrNull { it.replyAction != null && it.sender.contains(q, ignoreCase = true) }
                ?: messages.asReversed().firstOrNull {
                    it.replyAction != null && q.contains(it.sender.substringBefore(' '), ignoreCase = true)
                }
        }

        fun reply(context: Context, message: Message, text: String): Boolean {
            val action = message.replyAction ?: return false
            val inputs = action.remoteInputs ?: return false
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return try {
                action.actionIntent.send(context, 0, intent)
                true
            } catch (e: PendingIntent.CanceledException) {
                false
            }
        }

        private fun add(m: Message) = synchronized(lock) {
            messages.removeAll { it.key == m.key && it.text == m.text }
            messages.add(m)
            while (messages.size > MAX) messages.removeAt(0)
        }
    }

    override fun onListenerConnected() {
        instance = this
        runCatching { activeNotifications?.sortedBy { it.postTime }?.forEach { capture(it) } }
    }

    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        capture(sbn)
        runCatching { watch(sbn) }.onFailure { PicoLog.w("Notification watch failed", it) }
    }

    private val prefs by lazy { Prefs(this) }

    private fun watch(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        if (Watches.active(prefs).isEmpty()) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (text.isEmpty() || SUMMARY.matches(text)) return
        val chatApp = CHAT_APPS[sbn.packageName]
        val app = chatApp ?: runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        Watches.check(this, prefs, app, chatApp != null, title, text)
    }

    private fun capture(sbn: StatusBarNotification) {
        val app = CHAT_APPS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (sender.isEmpty() || text.isEmpty() || SUMMARY.matches(text)) return
        // After a reply (yours, or the assistant's), WhatsApp posts the chat again as "You: …": that's not a new message.
        if (sender.equals("You", ignoreCase = true) || sender.endsWith(": You")) return
        val reply = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val message = Message(sbn.key, app, sender, text, sbn.postTime, reply)
        add(message)
        // Not-available mode notes it and may answer once; a group chat is never answered.
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || sender.contains(": ")
        // The chat the assistant is keeping going comes first; Not-available mode handles the rest.
        val handled = runCatching { ChatForMe.onMessage(this, prefs, message, group) }.onFailure { PicoLog.w("Chat for me failed", it) }.getOrDefault(false)
        if (!handled) runCatching { Away.onMessage(this, prefs, message, group) }.onFailure { PicoLog.w("Away message failed", it) }
    }
}
