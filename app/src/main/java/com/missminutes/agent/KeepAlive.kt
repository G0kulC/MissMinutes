package com.missminutes.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Keeps the assistant listening while the user wants it on. Android (and battery savers like ColorOS's) can close
 * the service while the phone sleeps; every 15 minutes, and after a restart or an app update, this checks and starts
 * it again. Android 14+ often won't let an app turn the microphone on from the background, so when the restart is
 * refused a notification asks for one tap instead (starting from a notification is always allowed).
 */
object KeepAlive {
    const val ACTION_CHECK = "com.missminutes.agent.KEEP_ALIVE"
    private const val EVERY_MS = 15 * 60_000L
    private const val NOTIF_ID = 77
    private const val CHANNEL = "pico_status"

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + EVERY_MS
        // An exact alarm still fires in Doze, and a broadcast from one may start a foreground service.
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context)) }
            .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context)) }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
        clearStopped(context)
    }

    /** Starts the service again if it should be on and isn't. [why] goes in the log. */
    fun check(context: Context, prefs: Prefs, why: String) {
        if (!prefs.wantRunning) return
        schedule(context)
        if (MissMinutesService.running) return
        PicoLog.i("Keep-alive ($why): not running, starting again")
        runCatching {
            context.startForegroundService(MissMinutesService.intent(context, MissMinutesService.ACTION_START))
        }.onFailure {
            PicoLog.w("Keep-alive: Android didn't allow a restart", it)
            notifyStopped(context, prefs)
        }
    }

    /** "Pico stopped listening. Tap to start again." */
    fun notifyStopped(context: Context, prefs: Prefs) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant status", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when your assistant stopped listening and needs a tap to start again"
            setSound(null, null)
        })
        val start = PendingIntent.getForegroundService(context, 5,
            MissMinutesService.intent(context, MissMinutesService.ACTION_START), PendingIntent.FLAG_IMMUTABLE)
        val name = prefs.assistantName
        nm.notify(NOTIF_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_minutes)
            .setColor(Palette.AMBER)
            .setContentTitle("$name stopped listening")
            .setContentText("Android closed $name to save battery. Tap to start listening again.")
            .setContentIntent(start)
            .setAutoCancel(true)
            .build())
    }

    fun clearStopped(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 6, Intent(context, ReminderReceiver::class.java).setAction(ACTION_CHECK), PendingIntent.FLAG_IMMUTABLE
    )
}
