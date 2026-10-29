package com.missminutes.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Reminders she says out loud (and posts as a notification) at a set time. They survive a restart. */
object Reminders {
    data class Reminder(val id: Int, val at: Long, val text: String)

    private val lock = Any()

    fun all(prefs: Prefs): List<Reminder> = synchronized(lock) {
        runCatching {
            val a = JSONArray(prefs.remindersJson)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Reminder(o.getInt("id"), o.getLong("at"), o.getString("text"))
            }
        }.getOrDefault(emptyList()).sortedBy { it.at }
    }

    private fun save(prefs: Prefs, list: List<Reminder>) {
        prefs.remindersJson = JSONArray(list.map {
            JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)
        }).toString()
    }

    fun add(context: Context, prefs: Prefs, at: Long, text: String): Reminder = synchronized(lock) {
        val r = Reminder((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), at, text)
        save(prefs, all(prefs) + r)
        schedule(context, r)
        r
    }

    /** Cancels reminders whose text mentions [about], or all of them for "all". Returns what was cancelled. */
    fun cancel(context: Context, prefs: Prefs, about: String): List<Reminder> = synchronized(lock) {
        val list = all(prefs)
        val gone = list.filter { about.equals("all", true) || it.text.contains(about, ignoreCase = true) }
        gone.forEach { alarms(context).cancel(pending(context, it.id)) }
        save(prefs, list - gone.toSet())
        gone
    }

    /** Removes a reminder that just went off and returns it. */
    fun take(prefs: Prefs, id: Int): Reminder? = synchronized(lock) {
        val list = all(prefs)
        val r = list.firstOrNull { it.id == id } ?: return null
        save(prefs, list - r)
        r
    }

    /** After a restart or app update: set every alarm again, and deliver any that were missed. */
    fun rescheduleAll(context: Context, prefs: Prefs) {
        val now = System.currentTimeMillis()
        all(prefs).forEach { r ->
            if (r.at <= now) fire(context, prefs, r.id) else schedule(context, r)
        }
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun pending(context: Context, id: Int) = PendingIntent.getBroadcast(
        context, id,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun schedule(context: Context, r: Reminder) {
        val am = alarms(context)
        val pi = pending(context, r.id)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        // An alarm-clock alarm is the only kind phone makers never delay (a while-idle one ran 45 s late).
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        if (exact) am.setAlarmClock(AlarmManager.AlarmClockInfo(r.at, show), pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
    }

    fun fire(context: Context, prefs: Prefs, id: Int) {
        val r = take(prefs, id) ?: return
        Announcer.notifyReminder(context, r)
        Announcer.speak(context, "Reminder: ${r.text}")
    }

    /** "6:30 PM today", "9:00 AM tomorrow", "Fri 2 Nov, 8:00 AM". */
    fun describe(at: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance()
        val days = ((startOfDay(cal) - startOfDay(today)) / 86_400_000L).toInt()
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(at))
        return when (days) {
            0 -> "$time today"
            1 -> "$time tomorrow"
            else -> SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(Date(at))
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * "Tell me when Ravi messages", "remind me if any WhatsApp comes": while a watch is active, matching
 * notifications are read out loud as they arrive.
 */
object Watches {
    data class Watch(val from: String, val app: String, val keyword: String, val until: Long, val once: Boolean) {
        fun describe(): String = buildString {
            append(when {
                app.equals("any", true) -> "any notification"
                app.isNotEmpty() -> "$app notifications"
                else -> "new messages"
            })
            if (from.isNotEmpty()) append(" from $from")
            if (keyword.isNotEmpty()) append(" mentioning \"$keyword\"")
            append(if (once) ", once," else ",")
            append(" until ${Reminders.describe(until)}")
        }
    }

    private val lock = Any()
    private val lastSpoken = HashMap<String, Long>()

    fun active(prefs: Prefs): List<Watch> = synchronized(lock) {
        val now = System.currentTimeMillis()
        runCatching {
            val a = JSONArray(prefs.watchesJson)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Watch(o.optString("from"), o.optString("app"), o.optString("keyword"), o.getLong("until"), o.optBoolean("once"))
            }
        }.getOrDefault(emptyList()).filter { it.until > now }
    }

    private fun save(prefs: Prefs, list: List<Watch>) {
        prefs.watchesJson = JSONArray(list.map {
            JSONObject().put("from", it.from).put("app", it.app).put("keyword", it.keyword)
                .put("until", it.until).put("once", it.once)
        }).toString()
    }

    fun add(prefs: Prefs, w: Watch) = synchronized(lock) { save(prefs, active(prefs) + w) }

    fun remove(prefs: Prefs, w: Watch) = synchronized(lock) { save(prefs, active(prefs) - w) }

    fun clear(prefs: Prefs, about: String): Int = synchronized(lock) {
        val list = active(prefs)
        val keep = if (about.isBlank() || about.equals("all", true)) emptyList()
                   else list.filterNot { "${it.from} ${it.app} ${it.keyword}".contains(about, ignoreCase = true) }
        save(prefs, keep)
        list.size - keep.size
    }

    /** Called for every new notification. Announces it if a watch matches. */
    fun check(context: Context, prefs: Prefs, app: String, isChat: Boolean, sender: String, text: String) {
        val match = synchronized(lock) {
            val list = active(prefs)
            val hit = list.firstOrNull { w ->
                val appOk = when {
                    w.app.equals("any", true) -> true
                    w.app.isNotEmpty() -> app.contains(w.app, ignoreCase = true) || w.app.contains(app, ignoreCase = true)
                    else -> isChat
                }
                appOk &&
                    (w.from.isEmpty() || sender.contains(w.from, ignoreCase = true)) &&
                    (w.keyword.isEmpty() || text.contains(w.keyword, ignoreCase = true) || sender.contains(w.keyword, ignoreCase = true))
            } ?: return
            // One announcement per sender every 20 seconds, so a burst of messages isn't read out ten times.
            val key = "$app/$sender"
            val now = System.currentTimeMillis()
            if (now - (lastSpoken[key] ?: 0L) < 20_000) return
            lastSpoken[key] = now
            if (hit.once) save(prefs, list - hit)
            hit
        }
        Log.i("MissMinutes", "Watch matched: ${match.describe()}")
        val who = if (sender.isNotEmpty()) "$app message from $sender" else "$app notification"
        Announcer.speak(context, "$who: ${text.take(200)}")
    }
}

/** Gets a reminder or alert to the user: a notification, and Miss Minutes saying it out loud. */
object Announcer {
    private const val CHANNEL = "reminders"

    /** "Reminder · 6:30 PM" with the text, plus Snooze 10 min and Done. */
    fun notifyReminder(context: Context, r: Reminders.Reminder) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you asked Miss Minutes to set"
            }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(
            context, r.id * 4 + code,
            Intent(context, ReminderReceiver::class.java).setAction(action)
                .putExtra("id", r.id).putExtra("text", r.text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(r.at))
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_minutes)
            .setColor(Palette.AMBER)
            .setContentTitle(r.text)
            .setContentText("Reminder · $time")
            .setSubText("Miss Minutes")
            .setStyle(Notification.BigTextStyle().bigText(r.text).setSummaryText("Reminder · $time"))
            .setWhen(r.at)
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_snooze), "Snooze 10 min",
                action(ReminderReceiver.ACTION_SNOOZE, 1)).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_check), "Done",
                action(ReminderReceiver.ACTION_DONE, 2)).build())
            .build()
        runCatching { nm.notify(r.id, n) }
    }

    /** Speaks through the running Miss Minutes service. If she's switched off, the notification has to do. */
    fun speak(context: Context, text: String) {
        if (!MissMinutesService.running) return
        runCatching {
            context.startService(
                MissMinutesService.intent(context, MissMinutesService.ACTION_ANNOUNCE)
                    .putExtra(MissMinutesService.EXTRA_TEXT, text)
            )
        }.onFailure { Log.w("MissMinutes", "Couldn't announce", it) }
    }
}

/** Turns Do Not Disturb off again after "DND for an hour". */
object DndTimer {
    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 7_100, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DND_OFF),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun offAt(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        }
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(pending(context))
}

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DND_OFF = "com.missminutes.agent.DND_OFF"
        const val ACTION_FIRE = "com.missminutes.agent.REMINDER"
        const val ACTION_SNOOZE = "com.missminutes.agent.SNOOZE"
        const val ACTION_DONE = "com.missminutes.agent.DONE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        when (intent.action) {
            ACTION_FIRE -> Reminders.fire(context, prefs, intent.getIntExtra("id", -1))
            ACTION_DND_OFF -> runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            ACTION_SNOOZE, ACTION_DONE -> {
                context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("id", -1))
                val text = intent.getStringExtra("text")
                if (intent.action == ACTION_SNOOZE && text != null) {
                    Reminders.add(context, prefs, System.currentTimeMillis() + 10 * 60_000L, text)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.rescheduleAll(context, prefs)
        }
    }
}
