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
import kotlin.math.roundToInt

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
        "set_volume" -> setVolume(input.getInt("percent"))
        "media_control" -> mediaControl(input.text("action"))
        "phone_status" -> phoneStatus()
        "remember" -> remember(input.text("fact"))
        "forget" -> forget(input.text("about"))
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

    private fun setVolume(percent: Int): String {
        val p = percent.coerceIn(0, 100)
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * p / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
        return "Media volume set to $p%."
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

    companion object {
        private const val ACCESSIBILITY_OFF =
            "ERROR: Accessibility is off for Miss Minutes. The user can turn it on in the Miss Minutes app."
        private const val NOTIFICATIONS_OFF =
            "ERROR: Notification access is off for Miss Minutes. The user can allow it in the Miss Minutes app."
    }
}
