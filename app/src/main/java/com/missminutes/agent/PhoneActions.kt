package com.missminutes.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.Settings
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import java.util.TimeZone
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything Miss Minutes can do on the phone. To add a power: add a case here and a tool in ClaudeBrain. */
class PhoneActions(private val context: Context, private val prefs: Prefs) {

    /** Set when she should stay quiet afterwards (for example, a call just started). */
    var quietAfterAction = false

    suspend fun run(name: String, input: JSONObject): String = when (name) {
        "send_whatsapp_message" -> sendWhatsApp(input.text("contact_name"), input.text("message"))
        "send_sms" -> sendSms(input.text("contact_name"), input.text("message"))
        "call_contact" -> call(input.text("contact_name"))
        "read_recent_messages" -> readMessages(input.text("from").ifEmpty { null }, input.optInt("limit", 5))
        "reply_to_message" -> replyToMessage(input.text("sender"), input.text("message"))
        "open_app" -> openApp(input.text("app_name"))
        "play_on_youtube" -> playOnYoutube(input.text("query"))
        "set_alarm" -> setAlarm(input.getInt("hour"), input.getInt("minute"), input.text("label").ifEmpty { "Miss Minutes" })
        "set_timer" -> setTimer(input.optInt("minutes", 0), input.optInt("seconds", 0), input.text("label").ifEmpty { "Miss Minutes" })
        "phone_navigation" -> navigate(input.text("action"))
        "read_screen" -> screen()?.readScreen() ?: ACCESSIBILITY_OFF
        "tap_text" -> screen()?.tapText(input.text("text")) ?: ACCESSIBILITY_OFF
        "type_text" -> screen()?.typeText(input.text("text"), input.optBoolean("press_enter", false)) ?: ACCESSIBILITY_OFF
        "scroll" -> screen()?.scroll(input.text("direction")) ?: ACCESSIBILITY_OFF
        "flashlight" -> flashlight(input.optBoolean("on", true))
        "set_volume" -> setVolume(input)
        "set_brightness" -> setBrightness(input)
        "sound_mode" -> soundMode(input.text("mode"), input.optInt("minutes", 0))
        "directions" -> directions(input.text("destination"), input.text("mode"), input.optBoolean("start", false))
        "read_calendar" -> readCalendar(input.text("date"), input.optInt("days", 1))
        "add_calendar_event" -> addCalendarEvent(input)
        "save_routine" -> saveRoutine(input.text("name"), input.text("steps"))
        "delete_routine" -> deleteRoutine(input.text("name"))
        "media_control" -> mediaControl(input.text("action"))
        "phone_status" -> phoneStatus()
        "remember" -> remember(input.text("fact"))
        "forget" -> forget(input.text("about"))
        "set_reminder" -> setReminder(input)
        "list_reminders" -> listReminders()
        "cancel_reminder" -> cancelReminder(input.text("about"))
        "watch_notifications" -> watchNotifications(input)
        "stop_watching" -> stopWatching(input.text("about"))
        else -> "ERROR: unknown tool $name"
    }

    private fun JSONObject.text(key: String) = optString(key).trim().takeUnless { it == "null" } ?: ""
    private fun screen() = MinutesAccessibility.instance

    // ---------- Contacts ----------

    data class Contact(val name: String, val number: String)

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun findContact(query: String): Contact? {
        if (query.isBlank()) return null
        // A spoken phone number works too: "send to 98765 43210"
        if (query.count { it.isDigit() } >= 7 && query.none { it.isLetter() }) return Contact(query, query)
        if (!granted(Manifest.permission.READ_CONTACTS)) throw SecurityException("Contacts permission is off")
        val q = query.trim().lowercase()
        val qCompact = q.replace(" ", "")
        var best: Contact? = null
        var bestScore = 0
        context.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val n = name.lowercase()
                val score = when {
                    n == q -> 5
                    n.replace(" ", "") == qCompact -> 4
                    n.split(" ").any { it == q } -> 3
                    n.startsWith(q) -> 2
                    n.contains(q) -> 1
                    else -> 0
                }
                if (score > bestScore) { bestScore = score; best = Contact(name, number) }
            }
        }
        return best
    }

    /** "098765 43210" becomes "919876543210" for WhatsApp links. */
    private fun normalize(number: String): String {
        val hasPlus = number.trim().startsWith("+")
        var digits = number.filter { it.isDigit() }
        if (hasPlus) return digits
        if (digits.startsWith("00")) return digits.drop(2)
        if (digits.startsWith("0")) digits = digits.drop(1)
        return if (digits.length <= 10) prefs.countryCode + digits else digits
    }

    private fun installed(pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun start(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    // ---------- Messages and calls ----------

    private suspend fun sendWhatsApp(contactName: String, message: String): String {
        if (message.isBlank()) return "ERROR: the message is empty"
        val contact = findContact(contactName) ?: return "ERROR: no contact named '$contactName' in the phone"
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(it) }
            ?: return "ERROR: WhatsApp is not installed"
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${normalize(contact.number)}&text=${Uri.encode(message)}")
        start(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))

        val acc = screen()
            ?: return "Opened ${contact.name}'s chat with the message typed, but Accessibility is off, so the user has to tap Send."
        return if (acc.clickWhatsAppSend()) "Sent to ${contact.name} on WhatsApp."
        else "Opened ${contact.name}'s chat with the message typed, but couldn't press Send automatically."
    }

    private fun sendSms(contactName: String, message: String): String {
        if (message.isBlank()) return "ERROR: the message is empty"
        val contact = findContact(contactName) ?: return "ERROR: no contact named '$contactName' in the phone"
        if (!granted(Manifest.permission.SEND_SMS)) return "ERROR: SMS permission is off"
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        sms.sendMultipartTextMessage(contact.number, null, sms.divideMessage(message), null, null)
        return "SMS sent to ${contact.name}."
    }

    private fun call(contactName: String): String {
        val contact = findContact(contactName) ?: return "ERROR: no contact named '$contactName' in the phone"
        if (!granted(Manifest.permission.CALL_PHONE)) return "ERROR: phone call permission is off"
        start(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(contact.number)}")))
        quietAfterAction = true
        return "Calling ${contact.name}."
    }

    private fun readMessages(from: String?, limit: Int): String {
        if (NotificationReader.instance == null) return NOTIFICATIONS_OFF
        val list = NotificationReader.recent(from, limit.coerceIn(1, 15))
        if (list.isEmpty()) return if (from != null) "No recent messages from $from." else "No new messages."
        val now = System.currentTimeMillis()
        return list.joinToString("\n") { m -> "${m.app}, ${m.sender}, ${ago(now - m.time)}: ${m.text.take(300)}" }
    }

    private fun replyToMessage(sender: String, message: String): String {
        if (message.isBlank()) return "ERROR: the reply is empty"
        if (NotificationReader.instance == null) return NOTIFICATIONS_OFF
        val target = NotificationReader.latestFrom(sender)
            ?: return "ERROR: no recent message from $sender that can be answered. Use send_whatsapp_message instead."
        return if (NotificationReader.reply(context, target, message)) "Replied to ${target.sender} on ${target.app}."
        else "ERROR: ${target.app} didn't accept a quick reply. Use send_whatsapp_message instead."
    }

    private fun ago(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    // ---------- Apps ----------

    private fun openApp(appName: String): String {
        if (appName.isBlank()) return "ERROR: which app?"
        val pm = context.packageManager
        val q = appName.lowercase()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val best = pm.queryIntentActivities(launcher, 0)
            .map { info ->
                val label = info.loadLabel(pm).toString()
                val l = label.lowercase()
                val score = when {
                    l == q -> 3
                    l.startsWith(q) -> 2
                    l.contains(q) || (l.length >= 3 && q.contains(l)) -> 1
                    else -> 0
                }
                Triple(info.activityInfo.packageName, label, score)
            }
            .filter { it.third > 0 }
            .maxByOrNull { it.third }
            ?: return "ERROR: no app called '$appName' is installed"
        val intent = pm.getLaunchIntentForPackage(best.first) ?: return "ERROR: ${best.second} can't be opened"
        start(intent)
        return "Opened ${best.second}."
    }

    private fun playOnYoutube(query: String): String {
        if (query.isBlank()) return "ERROR: what should I search for?"
        val yt = "com.google.android.youtube"
        val intent = if (installed(yt)) {
            Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra("query", query)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
        }
        start(intent)
        return "Showing YouTube results for \"$query\". To play one, use read_screen and then tap_text on its title."
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) return "ERROR: that time isn't valid"
        start(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Alarm set for %02d:%02d.".format(hour, minute)
    }

    private fun setTimer(minutes: Int, seconds: Int, label: String): String {
        val total = minutes * 60 + seconds
        if (total <= 0) return "ERROR: the timer needs a length"
        start(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, total)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Timer started for ${total / 60} min ${total % 60} s."
    }

    private fun navigate(action: String): String {
        val acc = screen() ?: return ACCESSIBILITY_OFF
        val code = when (action) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                             else return "ERROR: locking the screen needs Android 9 or newer"
            else -> return "ERROR: unknown action $action"
        }
        return if (acc.performGlobalAction(code)) "Done." else "ERROR: the phone refused that action"
    }

    // ---------- Phone controls ----------

    private fun flashlight(on: Boolean): String {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "ERROR: this phone has no flashlight"
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    /** An exact level, or a step up or down, or mute, for media, ringtone, notifications, alarms or calls. */
    private fun setVolume(input: JSONObject): String {
        val (stream, label) = when (input.text("stream")) {
            "ring" -> AudioManager.STREAM_RING to "Ringtone"
            "notification" -> AudioManager.STREAM_NOTIFICATION to "Notification"
            "alarm" -> AudioManager.STREAM_ALARM to "Alarm"
            "call" -> AudioManager.STREAM_VOICE_CALL to "Call"
            else -> AudioManager.STREAM_MUSIC to "Media"
        }
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(stream)
        val step = (max * 0.15).roundToInt().coerceAtLeast(1)
        val flags = AudioManager.FLAG_SHOW_UI
        try {
            when (input.text("change")) {
                "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
                "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
                "up" -> am.setStreamVolume(stream, (am.getStreamVolume(stream) + step).coerceAtMost(max), flags)
                "down" -> am.setStreamVolume(stream, (am.getStreamVolume(stream) - step).coerceAtLeast(0), flags)
                else -> {
                    if (!input.has("percent")) return "ERROR: give percent, or change up, down, mute or unmute"
                    val p = input.optInt("percent").coerceIn(0, 100)
                    am.setStreamVolume(stream, (max * p / 100.0).roundToInt(), flags)
                }
            }
        } catch (e: SecurityException) {
            // Android blocks ringtone and notification changes that would switch Do Not Disturb on or off.
            return "ERROR: Do Not Disturb is on, so Android won't let me change the $label volume. The user can turn it off first."
        }
        if (am.isStreamMute(stream)) return "$label volume muted."
        return "$label volume is now ${(am.getStreamVolume(stream) * 100.0 / max).roundToInt()}%."
    }

    /** An exact level, a step up or down, or auto brightness on or off. Needs "Modify system settings". */
    private fun setBrightness(input: JSONObject): String {
        if (!Settings.System.canWrite(context)) return BRIGHTNESS_OFF
        val cr = context.contentResolver
        val mode = Settings.System.SCREEN_BRIGHTNESS_MODE
        val change = input.text("change")
        if (input.has("auto") && !input.isNull("auto")) {
            val auto = input.optBoolean("auto")
            Settings.System.putInt(cr, mode,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            if (auto || (change.isEmpty() && !input.has("percent"))) {
                return if (auto) "Auto brightness on." else "Auto brightness off."
            }
        }
        // The brightness slider isn't linear: half way on it is about a quarter of the raw 0-255 value.
        val raw = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val current = sqrt(raw.coerceIn(0, 255) / 255.0) * 100
        val target = when (change) {
            "up" -> current + 20
            "down" -> current - 20
            else -> if (input.has("percent")) input.optInt("percent").toDouble()
                    else return "ERROR: give percent, change up or down, or auto"
        }.coerceIn(1.0, 100.0)
        val wasAuto = Settings.System.getInt(cr, mode, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        Settings.System.putInt(cr, mode, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (255 * (target / 100).pow(2)).roundToInt().coerceIn(1, 255))
        return "Brightness set to ${target.roundToInt()}%." + if (wasAuto) " Auto brightness is now off." else ""
    }

    private fun mediaControl(action: String): String {
        val code = when (action) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "ERROR: unknown media action $action"
        }
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Done."
    }

    /** Ring, vibrate, silent, and Do Not Disturb (optionally for a while). Needs "Do Not Disturb access". */
    private fun soundMode(mode: String, minutes: Int): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AudioManager::class.java)
        if (mode != "vibrate" && !nm.isNotificationPolicyAccessGranted) return DND_OFF
        try {
            when (mode) {
                "normal" -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                "vibrate" -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "silent" -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
                // Priority only: starred contacts and alarms still get through.
                "dnd_on" -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                "dnd_off" -> { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL); DndTimer.cancel(context) }
                else -> return "ERROR: mode must be normal, vibrate, silent, dnd_on or dnd_off"
            }
        } catch (e: SecurityException) {
            return DND_OFF
        }
        return when (mode) {
            "normal" -> "Ringer on."
            "vibrate" -> "Phone on vibrate."
            "silent" -> "Phone on silent."
            "dnd_off" -> "Do Not Disturb off."
            else -> if (minutes > 0) {
                val until = System.currentTimeMillis() + minutes * 60_000L
                DndTimer.offAt(context, until)
                "Do Not Disturb on until ${Reminders.describe(until)}. Starred contacts and alarms still come through."
            } else {
                DndTimer.cancel(context)
                "Do Not Disturb on. Starred contacts and alarms still come through."
            }
        }
    }

    // ---------- Maps ----------

    /** Directions in Google Maps, or straight into turn-by-turn navigation with [go]. */
    private fun directions(destination: String, mode: String, go: Boolean): String {
        if (destination.isBlank()) return "ERROR: where to?"
        val maps = "com.google.android.apps.maps"
        val hasMaps = installed(maps)
        val navigate = go && hasMaps && mode != "transit"
        val uri = if (navigate) {
            val m = when (mode) { "walk" -> "w"; "bike" -> "b"; "two_wheeler" -> "l"; else -> "d" }
            Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$m")
        } else {
            val m = when (mode) { "walk" -> "walking"; "bike", "two_wheeler" -> "bicycling"; "transit" -> "transit"; else -> "driving" }
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=$m")
        }
        start(Intent(Intent.ACTION_VIEW, uri).apply { if (hasMaps) setPackage(maps) })
        return if (navigate) "Started navigation to $destination."
        else "Showing directions to $destination. To tell the user the travel time, use read_screen."
    }

    // ---------- Calendar ----------

    private fun parseDay(date: String): java.util.Calendar? {
        val cal = java.util.Calendar.getInstance()
        if (date.isNotEmpty()) {
            cal.time = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(date) }.getOrNull() ?: return null
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal
    }

    private fun readCalendar(date: String, days: Int): String {
        if (!granted(Manifest.permission.READ_CALENDAR)) return CALENDAR_OFF
        val from = parseDay(date)?.timeInMillis ?: return "ERROR: date must be YYYY-MM-DD"
        val to = from + days.coerceIn(1, 14) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        val timed = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH)
        // All-day events are stored at midnight UTC; reading them in local time can slip a day.
        val allDay = SimpleDateFormat("EEE d MMM", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = mutableListOf<String>()
        context.contentResolver.query(
            uri, cols, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext() && out.size < 25) {
                val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)"
                val begin = Date(c.getLong(1))
                val whenText = if (c.getInt(2) == 1) allDay.format(begin) + ", all day" else timed.format(begin)
                val place = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
                out += "$whenText: $title$place"
            }
        }
        return if (out.isEmpty()) "No events." else out.joinToString("\n")
    }

    private fun addCalendarEvent(input: JSONObject): String {
        if (!granted(Manifest.permission.WRITE_CALENDAR) || !granted(Manifest.permission.READ_CALENDAR)) return CALENDAR_OFF
        val title = input.text("title").ifEmpty { return "ERROR: what is the event called?" }
        val day = parseDay(input.text("date")) ?: return "ERROR: date must be YYYY-MM-DD"
        val time = input.text("time")
        val calendarId = writableCalendar()
            ?: return "ERROR: no calendar on this phone can take new events. The user needs a Google account in Calendar."
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            input.text("location").takeIf { it.isNotEmpty() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        val start: Long
        if (time.isEmpty()) {
            // All-day events must start at midnight UTC.
            val utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(day.get(java.util.Calendar.YEAR), day.get(java.util.Calendar.MONTH), day.get(java.util.Calendar.DAY_OF_MONTH))
            }
            start = utc.timeInMillis
            values.put(CalendarContract.Events.ALL_DAY, 1)
            values.put(CalendarContract.Events.DTSTART, start)
            values.put(CalendarContract.Events.DTEND, start + 86_400_000L)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } else {
            val m = Regex("""^(\d{1,2}):(\d{2})$""").find(time) ?: return "ERROR: time must be HH:MM in 24-hour format"
            day.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt().coerceIn(0, 23))
            day.set(java.util.Calendar.MINUTE, m.groupValues[2].toInt().coerceIn(0, 59))
            start = day.timeInMillis
            val minutes = input.optInt("duration_minutes", 60).coerceIn(5, 24 * 60)
            values.put(CalendarContract.Events.DTSTART, start)
            values.put(CalendarContract.Events.DTEND, start + minutes * 60_000L)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return "ERROR: Calendar didn't accept the event"
        if (time.isNotEmpty()) {
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                put(CalendarContract.Reminders.MINUTES, 10)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        val whenText = if (time.isEmpty()) SimpleDateFormat("EEEE d MMMM", Locale.ENGLISH).format(day.time) + ", all day"
                       else Reminders.describe(start)
        return "Added \"$title\" to the calendar for $whenText."
    }

    /** The phone's main calendar that can take new events (the Google account's own one if there is one). */
    private fun writableCalendar(): Long? {
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE)
        val where = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}" +
            " AND ${CalendarContract.Calendars.VISIBLE} = 1"
        var best: Long? = null
        var bestScore = -1
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, where, null, null)?.use { c ->
            while (c.moveToNext()) {
                val score = (if (c.getInt(1) == 1) 2 else 0) + (if (c.getString(2) == "com.google") 1 else 0)
                if (score > bestScore) { bestScore = score; best = c.getLong(0) }
            }
        }
        return best
    }

    private fun phoneStatus(): String {
        val bm = context.getSystemService(BatteryManager::class.java)
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "online"
        }
        val time = SimpleDateFormat("h:mm a, EEEE d MMMM", Locale.ENGLISH).format(Date())
        return "Battery $battery%${if (bm.isCharging) ", charging" else ""}. Network: $network. Time: $time."
    }

    // ---------- Memory ----------

    private fun remember(fact: String): String {
        if (fact.isBlank()) return "ERROR: nothing to remember"
        prefs.memories = prefs.memories.filterNot { it.equals(fact, ignoreCase = true) } + fact
        return "Saved to memory."
    }

    private fun forget(about: String): String {
        if (about.isBlank()) return "ERROR: what should I forget?"
        val before = prefs.memories
        val after = before.filterNot { it.contains(about, ignoreCase = true) }
        prefs.memories = after
        val removed = before.size - after.size
        return if (removed > 0) "Forgot $removed thing(s)." else "Nothing in memory mentions \"$about\"."
    }

    // ---------- Routines ----------

    private fun saveRoutine(name: String, steps: String): String {
        val key = name.lowercase().trim().ifEmpty { return "ERROR: what should the routine be called?" }
        if (steps.isBlank()) return "ERROR: what should the routine do?"
        prefs.routines = prefs.routines + (key to steps)
        return "Saved the \"$key\" routine."
    }

    private fun deleteRoutine(name: String): String {
        val key = name.lowercase().trim()
        val before = prefs.routines
        if (key !in before) return "No routine called \"$key\". Routines: ${before.keys.joinToString()}."
        prefs.routines = before - key
        return "Deleted the \"$key\" routine."
    }

    // ---------- Reminders and notification watches ----------

    private fun setReminder(input: JSONObject): String {
        val text = input.text("text").ifEmpty { return "ERROR: what should I remind you about?" }
        val now = System.currentTimeMillis()
        val minutes = input.optInt("minutes_from_now", 0)
        val at = if (minutes > 0) {
            now + minutes * 60_000L
        } else {
            val time = input.text("time")
            val m = Regex("""^(\d{1,2}):(\d{2})$""").find(time)
                ?: return "ERROR: give minutes_from_now, or time as HH:MM in 24-hour format"
            val cal = java.util.Calendar.getInstance()
            val date = input.text("date")
            if (date.isNotEmpty()) {
                val d = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(date) }.getOrNull()
                    ?: return "ERROR: date must be YYYY-MM-DD"
                cal.time = d
            }
            cal.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt().coerceIn(0, 23))
            cal.set(java.util.Calendar.MINUTE, m.groupValues[2].toInt().coerceIn(0, 59))
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            if (date.isEmpty() && cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            cal.timeInMillis
        }
        if (at <= now) return "ERROR: that time has already passed"
        val r = Reminders.add(context, prefs, at, text)
        val spoken = if (MissMinutesService.running) "I'll say it out loud and post a notification."
                     else "Miss Minutes is switched off, so it will only appear as a notification."
        return "Reminder set for ${Reminders.describe(r.at)}: ${r.text}. $spoken"
    }

    private fun listReminders(): String {
        val reminders = Reminders.all(prefs)
        val watches = Watches.active(prefs)
        if (reminders.isEmpty() && watches.isEmpty()) return "No reminders or notification watches."
        return buildString {
            reminders.forEach { appendLine("Reminder ${Reminders.describe(it.at)}: ${it.text}") }
            watches.forEach { appendLine("Watching for ${it.describe()}") }
        }.trim()
    }

    private fun cancelReminder(about: String): String {
        if (about.isBlank()) return "ERROR: which reminder? Say a word from it, or all."
        val gone = Reminders.cancel(context, prefs, about)
        return if (gone.isEmpty()) "No reminder mentions \"$about\"."
               else "Cancelled: " + gone.joinToString("; ") { "${Reminders.describe(it.at)} ${it.text}" }
    }

    private fun watchNotifications(input: JSONObject): String {
        if (NotificationReader.instance == null) return NOTIFICATIONS_OFF
        val hours = input.optDouble("hours", 4.0).let { if (it.isNaN() || it <= 0) 4.0 else it.coerceAtMost(24.0) }
        val w = Watches.Watch(
            from = input.text("from"),
            app = input.text("app"),
            keyword = input.text("keyword"),
            until = System.currentTimeMillis() + (hours * 3_600_000).toLong(),
            once = input.optBoolean("once", false),
        )
        Watches.add(prefs, w)
        val spoken = if (MissMinutesService.running) "" else " Miss Minutes is switched off, so she can't speak them until it's started."
        return "Now watching for ${w.describe()}.$spoken"
    }

    private fun stopWatching(about: String): String {
        val n = Watches.clear(prefs, about)
        return if (n == 0) "No notification watch to stop." else "Stopped $n notification watch(es)."
    }

    companion object {
        private const val ACCESSIBILITY_OFF =
            "ERROR: Accessibility is off for Miss Minutes. The user can turn it on in the Miss Minutes app."
        private const val DND_OFF =
            "ERROR: Miss Minutes isn't allowed to change Do Not Disturb or silent mode yet. The user can turn on Do Not Disturb access in the Miss Minutes app."
        private const val CALENDAR_OFF =
            "ERROR: Calendar permission is off. The user can allow it in the Miss Minutes app."
        private const val BRIGHTNESS_OFF =
            "ERROR: Miss Minutes isn't allowed to change brightness yet. The user can turn on Change brightness in the Miss Minutes app."
        private const val NOTIFICATIONS_OFF =
            "ERROR: Notification access is off for Miss Minutes. The user can allow it in the Miss Minutes app."
    }
}
