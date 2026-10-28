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
 * Collects incoming chat notifications so Miss Minutes can read them out and reply, and checks every new
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
        runCatching { watch(sbn) }.onFailure { Log.w("MissMinutes", "Notification watch failed", it) }
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
        val reply = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        add(Message(sbn.key, app, sender, text, sbn.postTime, reply))
    }
}
