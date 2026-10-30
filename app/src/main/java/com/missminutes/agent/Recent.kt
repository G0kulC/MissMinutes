package com.missminutes.agent

import org.json.JSONArray
import org.json.JSONObject

/** One exchange: what you said, the reply, and which agent answered (null for the main assistant). */
data class Turn(val at: Long, val heard: String, val said: String, val agent: String?)

/** The last few conversations, kept on the phone so the Home screen can show what you asked recently. */
object Recent {
    const val KEEP = 20

    fun all(prefs: Prefs): List<Turn> = parse(prefs.recentJson)

    fun add(prefs: Prefs, heard: String, said: String, agent: String?) {
        prefs.recentJson = encode(append(all(prefs), Turn(System.currentTimeMillis(), heard.trim(), said.trim(), agent)))
    }

    fun clear(prefs: Prefs) { prefs.recentJson = "[]" }

    fun append(list: List<Turn>, turn: Turn) = (list + turn).filter { it.heard.isNotEmpty() }.takeLast(KEEP)

    fun parse(json: String): List<Turn> = runCatching {
        val a = JSONArray(json)
        List(a.length()) {
            val o = a.getJSONObject(it)
            Turn(o.optLong("at"), o.optString("heard"), o.optString("said"), o.optString("agent").takeIf { n -> n.isNotBlank() })
        }
    }.getOrDefault(emptyList())

    fun encode(list: List<Turn>): String = JSONArray(list.map {
        JSONObject().put("at", it.at).put("heard", it.heard.take(300)).put("said", it.said.take(600))
            .apply { it.agent?.let { a -> put("agent", a) } }
    }).toString()
}
