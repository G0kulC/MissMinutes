package com.missminutes.agent

/** Claude models, best first. If one is unavailable, Miss Minutes falls back to the next. */
enum class BrainModel(val id: String, val title: String, val blurb: String) {
    BEST("claude-fable-5-1", "Claude Fable 5.1", "The most capable. Best for long multi-step tasks. Slower replies, highest cost."),
    SMART("claude-opus-5-5", "Claude Opus 5.5", "Very smart and reliable at controlling apps."),
    BALANCED("claude-sonnet-5-5", "Claude Sonnet 5.5", "Fast and capable. A good everyday choice."),
    FAST("claude-haiku-5-5", "Claude Haiku 5.5", "Quickest replies and lowest cost.");

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }

        fun fallbackAfter(id: String): BrainModel? {
            val i = entries.indexOfFirst { it.id == id }
            return if (i >= 0 && i < entries.size - 1) entries[i + 1] else null
        }
    }
}

/**
 * Sarvam Bulbul voices. Plain names are bulbul:v3 (all tested with Tamil); styled names are the faster
 * bulbul:v4-flash. Check docs.sarvam.ai if Sarvam adds or renames voices.
 */
object Voices {
    val sarvam = listOf(
        "kavya", "priya", "shruti", "kavitha", "ritu", "neha", "gokul", "vijay", "mani",
        "shalini_en_companion", "roopa_en_conversational", "gokul_ta_narration",
    )
    val default = sarvam.first()
}
