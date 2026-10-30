package com.missminutes.agent

/** Claude models, smartest first. If one is unavailable, Pico falls back to the next. */
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
    val options = listOf(
        VoiceOption("kavya", "Kavya", Presentation.FEMININE),
        VoiceOption("priya", "Priya", Presentation.FEMININE),
        VoiceOption("shruti", "Shruti", Presentation.FEMININE),
        VoiceOption("kavitha", "Kavitha", Presentation.FEMININE),
        VoiceOption("ritu", "Ritu", Presentation.FEMININE),
        VoiceOption("neha", "Neha", Presentation.FEMININE),
        VoiceOption("shalini_en_companion", "Shalini · companion (faster)", Presentation.FEMININE),
        VoiceOption("roopa_en_conversational", "Roopa · conversational (faster)", Presentation.FEMININE),
        VoiceOption("gokul", "Gokul", Presentation.MASCULINE),
        VoiceOption("vijay", "Vijay", Presentation.MASCULINE),
        VoiceOption("mani", "Mani", Presentation.MASCULINE),
        VoiceOption("gokul_ta_narration", "Gokul · narration (faster)", Presentation.MASCULINE),
    )
    val sarvam = options.map { it.id }
    val default = sarvam.first()

    /** Voices to offer for a presentation. Sarvam has no neutral voices, so neutral offers all of them. */
    fun forPresentation(p: Presentation) =
        if (p == Presentation.NEUTRAL) options else options.filter { it.presentation == p }

    fun title(id: String) = options.firstOrNull { it.id == id }?.title ?: id
}
