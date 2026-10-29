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

/** ElevenLabs models that speak Tamil, best everyday choice first (timed: first audio after about 0.5 s). */
enum class ElevenModel(val id: String, val title: String) {
    TURBO("eleven_v4_turbo", "v4 Turbo: natural and quick (best)"),
    FLASH("eleven_flash_v2_5", "Flash 2.5: fastest"),
    V4("eleven_v4", "v4: most expressive, a little slower");

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** ElevenLabs' ready-made voices, which every account has. Warm, conversational ones first. */
object ElevenVoices {
    class Voice(val id: String, val title: String)

    val all = listOf(
        Voice("cgSgspJ2msm6clMCkdW9", "Jessica: playful, bright, warm"),
        Voice("FGY2WhTYpPnrIDTdsKH5", "Laura: enthusiastic, quirky"),
        Voice("EXAVITQu4vr4xnSDxMaL", "Sarah: mature, reassuring"),
        Voice("XrExE9yKIg1WjnnlVkGX", "Matilda: knowledgeable, upbeat"),
        Voice("hpp4J3VqNfWAUOO0d1Us", "Bella: professional, warm"),
        Voice("Xb7hH8MSUJpSbSDYk0k2", "Alice: clear, British"),
        Voice("pFZP5JQG7iQjIQuC4Bku", "Lily: velvety, British"),
        Voice("SAz9YHcvj6GT2YYXdXww", "River: relaxed, neutral"),
        Voice("iP95p4xoKVk53GoZ742B", "Chris: charming, down to earth"),
        Voice("cjVigY5qzO86Huf0OWal", "Eric: smooth, trustworthy"),
        Voice("bIHbv24MWmeRgasZH58o", "Will: relaxed optimist"),
        Voice("nPczCjzI2devNBz1zQrb", "Brian: deep, comforting"),
        Voice("JBFqnCBsd6RMkjVDRZzb", "George: warm storyteller"),
        Voice("onwK4e9ZLuTAKqWW03F9", "Daniel: steady broadcaster"),
    )
    val default = all.first()
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
