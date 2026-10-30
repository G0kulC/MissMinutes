package com.missminutes.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a backup file holds: the user's profile and assistant settings, memories, routines and agents.
 * Never the API keys, and not reminders (they're tied to a time that has usually passed by the time you restore).
 */
data class BackupData(
    /** Simple settings by name: strings, booleans and numbers (see [Backup.SETTINGS]). */
    val settings: Map<String, Any>,
    val memories: List<String>,
    val routines: Map<String, String>,
    val agents: List<Agent>,
)

object Backup {
    const val FORMAT = "pico-backup"
    const val VERSION = 1

    /** One setting that goes into a backup, with how to read it and how to put it back safely. */
    private class Setting(val key: String, val read: (Prefs) -> Any, val write: (Prefs, Any) -> Unit)

    private fun text(key: String, get: (Prefs) -> String, set: (Prefs, String) -> Unit, ok: (String) -> Boolean = { true }) =
        Setting(key, get) { p, v -> (v as? String)?.takeIf(ok)?.let { set(p, it) } }

    private fun flag(key: String, get: (Prefs) -> Boolean, set: (Prefs, Boolean) -> Unit) =
        Setting(key, get) { p, v -> (v as? Boolean)?.let { set(p, it) } }

    private val SETTINGS = listOf(
        text("userName", { it.userName }, { p, v -> p.userName = v.take(30) }),
        text("city", { it.city }, { p, v -> p.city = v.take(40) }),
        text("countryCode", { it.countryCode }, { p, v -> p.countryCode = v }, { it.isNotEmpty() && it.all(Char::isDigit) }),
        text("assistantName", { it.assistantName }, { p, v -> p.assistantName = Assistant.cleanName(v) }, { it.isNotBlank() }),
        text("presentation", { it.presentation }, { p, v -> p.presentation = v }, { v -> Presentation.entries.any { it.name == v } }),
        text("voice", { it.sarvamSpeaker }, { p, v -> p.sarvamSpeaker = v }, { it in Voices.sarvam }),
        text("colour", { it.ghostTheme }, { p, v -> p.ghostTheme = v }, { v -> GhostTheme.entries.any { it.name == v } }),
        text("wakePhrase", { it.wakePhrase }, { p, v -> p.wakePhrase = v }, { WakeWord.shapeProblem(it) == null }),
        text("model", { it.model }, { p, v -> p.model = v }, { BrainModel.byId(it) != null }),
        flag("greetOnWake", { it.greetOnWake }, { p, v -> p.greetOnWake = v }),
        flag("greetWithName", { it.greetWithName }, { p, v -> p.greetWithName = v }),
        flag("picoLive", { it.picoLive }, { p, v -> p.picoLive = v }),
        flag("confirmBeforeSending", { it.confirmBeforeSending }, { p, v -> p.confirmBeforeSending = v }),
        flag("quickReplies", { it.quickReplies }, { p, v -> p.quickReplies = v }),
        flag("webSearch", { it.webSearch }, { p, v -> p.webSearch = v }),
        flag("keepListening", { it.keepListening }, { p, v -> p.keepListening = v }),
        flag("sounds", { it.sounds }, { p, v -> p.sounds = v }),
        flag("bargeIn", { it.bargeIn }, { p, v -> p.bargeIn = v }),
        flag("edgeGlow", { it.edgeGlow }, { p, v -> p.edgeGlow = v }),
        Setting("wakeSensitivity", { it.wakeSensitivity.toDouble() }) { p, v -> (v as? Number)?.let { p.wakeSensitivity = it.toFloat() } },
    )

    fun read(prefs: Prefs) = BackupData(
        settings = SETTINGS.associate { it.key to it.read(prefs) },
        memories = prefs.memories,
        routines = prefs.routines,
        agents = Agents.all(prefs),
    )

    /** Restores [incoming] on top of what's on the phone (see [merge]); the API keys stay as they are. */
    fun restore(prefs: Prefs, incoming: BackupData) {
        val merged = merge(read(prefs), incoming)
        for (s in SETTINGS) merged.settings[s.key]?.let { s.write(prefs, it) }
        prefs.memories = merged.memories
        prefs.routines = merged.routines
        Agents.save(prefs, merged.agents)
    }

    /**
     * Nothing on the phone is lost: the backup's settings are used, its memories are added to yours, its routines
     * and agents are added (one with the same name or id is replaced by the backup's copy).
     */
    fun merge(current: BackupData, incoming: BackupData) = BackupData(
        settings = current.settings + incoming.settings,
        memories = current.memories + incoming.memories.filter { m -> current.memories.none { it.equals(m, ignoreCase = true) } },
        routines = current.routines + incoming.routines,
        agents = current.agents.map { a -> incoming.agents.firstOrNull { it.id == a.id } ?: a } +
            incoming.agents.filter { b -> current.agents.none { it.id == b.id } },
    )

    fun encode(data: BackupData, exportedAt: Long): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("exportedAt", exportedAt)
        .put("settings", JSONObject().apply { data.settings.forEach { (k, v) -> put(k, v) } })
        .put("memories", JSONArray(data.memories))
        .put("routines", JSONObject(data.routines))
        .put("agents", JSONArray(data.agents.map { it.toJson() }))
        .toString(2)

    /** Reads a backup file; throws [IllegalArgumentException] with a friendly message if it isn't one. */
    fun decode(text: String): BackupData {
        val o = runCatching { JSONObject(text) }.getOrNull()
        require(o != null && o.optString("format") == FORMAT) { "That file isn't a backup from this app." }
        require(o.optInt("version", 0) in 1..VERSION) { "That backup is from a newer version of the app. Update the app, then try again." }
        val s = o.optJSONObject("settings") ?: JSONObject()
        val m = o.optJSONArray("memories") ?: JSONArray()
        val r = o.optJSONObject("routines") ?: JSONObject()
        return BackupData(
            settings = s.keys().asSequence().associateWith { s.get(it) }.filterValues { it is String || it is Boolean || it is Number },
            memories = List(m.length()) { m.optString(it) }.filter { it.isNotBlank() },
            routines = r.keys().asSequence().associateWith { r.optString(it) }.filterValues { it.isNotBlank() },
            agents = Agents.parse((o.optJSONArray("agents") ?: JSONArray()).toString()),
        )
    }
}
