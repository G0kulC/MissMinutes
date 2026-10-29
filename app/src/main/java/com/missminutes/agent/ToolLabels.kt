package com.missminutes.agent

import org.json.JSONObject

/** Short, human labels for what she's doing, read out by TalkBack from the orb. */
object ToolLabels {
    fun label(name: String, input: JSONObject): String {
        fun s(key: String) = input.optString(key).trim().takeUnless { it.isEmpty() || it == "null" } ?: ""
        val text = when (name) {
            "send_whatsapp_message" -> "WhatsApp to ${s("contact_name")}"
            "send_sms" -> "SMS to ${s("contact_name")}"
            "call_contact" -> "Calling ${s("contact_name")}"
            "read_recent_messages" -> if (s("from").isNotEmpty()) "Messages from ${s("from")}" else "Reading messages"
            "reply_to_message" -> "Replying to ${s("sender")}"
            "open_app" -> "Opening ${s("app_name")}"
            "play_on_youtube" -> "YouTube: ${s("query")}"
            "set_alarm" -> "Alarm %d:%02d".format(input.optInt("hour"), input.optInt("minute"))
            "set_timer" -> "Timer ${input.optInt("minutes")} min"
            "phone_navigation" -> when (s("action")) {
                "home" -> "Going home"
                "back" -> "Going back"
                "recents" -> "Recent apps"
                "notifications" -> "Opening notifications"
                "quick_settings" -> "Quick settings"
                "lock_screen" -> "Locking the screen"
                else -> "Navigating"
            }
            "read_screen" -> "Looking at the screen"
            "tap_text" -> "Tapping ${s("text")}"
            "type_text" -> "Typing"
            "scroll" -> "Scrolling ${s("direction")}"
            "flashlight" -> if (input.optBoolean("on")) "Flashlight on" else "Flashlight off"
            "set_volume" -> when (s("change")) {
                "up" -> "Volume up"
                "down" -> "Volume down"
                "mute" -> "Muting"
                "unmute" -> "Unmuting"
                else -> "Volume ${input.optInt("percent")}%"
            }
            "set_brightness" -> when {
                s("change") == "up" -> "Brighter"
                s("change") == "down" -> "Dimmer"
                input.has("percent") -> "Brightness ${input.optInt("percent")}%"
                else -> if (input.optBoolean("auto")) "Auto brightness" else "Brightness"
            }
            "sound_mode" -> when (s("mode")) {
                "normal" -> "Ringer on"
                "vibrate" -> "Vibrate mode"
                "silent" -> "Silent mode"
                "dnd_on" -> "Do Not Disturb on"
                else -> "Do Not Disturb off"
            }
            "directions" -> if (input.optBoolean("start")) "Navigating to ${s("destination")}" else "Route to ${s("destination")}"
            "read_calendar" -> "Checking your calendar"
            "add_calendar_event" -> "Adding ${s("title")}"
            "save_routine" -> "Saving routine ${s("name")}"
            "delete_routine" -> "Deleting routine ${s("name")}"
            "media_control" -> "Music: ${s("action").replace('_', ' ')}"
            "phone_status" -> "Checking the phone"
            "remember" -> "Remembering"
            "forget" -> "Forgetting"
            "set_reminder" -> "Reminder: ${s("text")}"
            "list_reminders" -> "Checking reminders"
            "cancel_reminder" -> "Cancelling reminder"
            "watch_notifications" -> if (s("from").isNotEmpty()) "Watching for ${s("from")}" else "Watching notifications"
            "stop_watching" -> "Stopping alerts"
            "think_deeper" -> "Thinking it through"
            "web_search" -> if (s("query").isNotEmpty()) "Searching: ${s("query")}" else "Searching the web"
            else -> name.replace('_', ' ')
        }
        return if (text.length > 44) text.take(43) + "…" else text
    }
}
