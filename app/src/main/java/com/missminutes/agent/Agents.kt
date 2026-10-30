package com.missminutes.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Groups of phone abilities an agent can be allowed, in plain words. The main assistant has all of them; an agent
 * gets only the ones the user ticked when creating it (none by default).
 */
enum class Ability(val title: String, val detail: String, val tools: Set<String>) {
    MESSAGES("Messages and calls", "Send WhatsApp and SMS, call people, read and reply to messages.",
        setOf("send_whatsapp_message", "send_sms", "call_contact", "read_recent_messages", "reply_to_message", "chat_for_me")),
    APPS("Apps and screen", "Open apps, see what's on screen, tap, type and scroll for you.",
        setOf("open_app", "read_screen", "tap_text", "type_text", "scroll", "phone_navigation", "play_on_youtube", "open_settings", "open_camera", "close_camera")),
    PHONE("Phone settings", "Flashlight, volume, brightness, Wi-Fi, Bluetooth, music controls and battery.",
        setOf("flashlight", "set_volume", "set_brightness", "sound_mode", "quick_setting", "media_control", "play_music", "phone_status")),
    TIME("Reminders and calendar", "Alarms, timers, reminders, alerts and your calendar.",
        setOf("set_alarm", "list_alarms", "alarm_switch", "set_timer", "set_reminder", "list_reminders", "cancel_reminder", "read_calendar",
            "add_calendar_event", "watch_notifications", "stop_watching")),
    MAPS("Maps", "Directions and navigation.", setOf("directions")),
    EMAIL("Email drafts", "Write emails for you to check and send.", setOf("draft_email")),
    WEB("Web search and weather", "Look up live information: news, weather, prices, and find products to buy.", setOf("web_search", "get_weather", "shop_search")),
    MEMORY("Memory", "Remember and forget things about you.", setOf("remember", "forget"));

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name }
    }
}

/** How an agent answers, as a choice of plain styles plus anything the user writes. */
enum class Style(val title: String, val instruction: String) {
    FRIENDLY("Friendly", "Be warm and friendly."),
    SHORT("Short and direct", "Keep answers short and to the point."),
    DETAILED("Detailed", "Explain things clearly, with useful detail when it helps."),
    PLAYFUL("Playful", "Be playful and fun.");

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: FRIENDLY
    }
}

/**
 * An extra agent the user made ("Chef", "Study buddy"): its own name, purpose, way of answering, the abilities it
 * may use, and optionally its own voice. It's separate from the main assistant, who can switch to it by voice.
 */
data class Agent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val purpose: String,
    val style: Style = Style.FRIENDLY,
    val instructions: String = "",
    val abilities: Set<Ability> = emptySet(),
    /** A Sarvam voice id, or null to sound like the main assistant. */
    val voice: String? = null,
    /** The ghost's colour while this agent talks (a [GhostTheme] name), or null for the main assistant's. */
    val colour: String? = null,
) {
    /** The tools this agent may call. */
    val tools: Set<String> get() = abilities.flatMap { it.tools }.toSet()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("purpose", purpose).put("style", style.name)
        .put("instructions", instructions).put("abilities", JSONArray(abilities.map { it.name }))
        .apply { voice?.let { put("voice", it) } }
        .apply { colour?.let { put("colour", it) } }

    companion object {
        fun fromJson(o: JSONObject): Agent {
            val a = o.optJSONArray("abilities") ?: JSONArray()
            return Agent(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name"),
                purpose = o.optString("purpose"),
                style = Style.of(o.optString("style")),
                instructions = o.optString("instructions"),
                abilities = List(a.length()) { Ability.of(a.optString(it)) }.filterNotNull().toSet(),
                voice = o.optString("voice").takeIf { it.isNotBlank() && it in Voices.sarvam },
                colour = o.optString("colour").takeIf { c -> GhostTheme.entries.any { it.name == c } },
            )
        }
    }
}

/** The user's agents, kept on the phone with the other settings. */
object Agents {
    fun all(prefs: Prefs): List<Agent> = parse(prefs.agentsJson)

    fun parse(json: String): List<Agent> = runCatching {
        val a = JSONArray(json)
        List(a.length()) { Agent.fromJson(a.getJSONObject(it)) }.filter { it.name.isNotBlank() }
    }.getOrDefault(emptyList())

    fun save(prefs: Prefs, agents: List<Agent>) {
        prefs.agentsJson = JSONArray(agents.map { it.toJson() }).toString()
    }

    /** Adds [agent], or replaces the one with the same id. */
    fun put(prefs: Prefs, agent: Agent) {
        val list = all(prefs)
        save(prefs, if (list.any { it.id == agent.id }) list.map { if (it.id == agent.id) agent else it } else list + agent)
    }

    fun remove(prefs: Prefs, id: String) = save(prefs, all(prefs).filterNot { it.id == id })

    /** The agent called [name] (any case, ignoring "the"), if there is one. */
    fun find(prefs: Prefs, name: String): Agent? {
        val want = name.trim().lowercase().removePrefix("the ").trim()
        return all(prefs).firstOrNull { it.name.lowercase() == want }
            ?: all(prefs).firstOrNull { want.isNotEmpty() && (it.name.lowercase().startsWith(want) || want.startsWith(it.name.lowercase())) }
    }

    fun byId(prefs: Prefs, id: String?) = id?.let { want -> all(prefs).firstOrNull { it.id == want } }
}
