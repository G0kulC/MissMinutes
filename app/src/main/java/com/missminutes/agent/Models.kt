package com.missminutes.agent

/** Claude models, smartest first. If one is unavailable, Miss Minutes falls back to the next. */
enum class BrainModel(val id: String, val title: String, val blurb: String) {
    SMART("claude-opus-5-5", "Claude Opus 5.5", "The smartest. Best for long multi-step tasks in your apps. Slower, costs more."),
    BALANCED("claude-sonnet-5-5", "Claude Sonnet 5.5", "Fast and very capable. The best everyday choice."),
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
