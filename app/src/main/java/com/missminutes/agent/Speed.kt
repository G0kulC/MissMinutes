package com.missminutes.agent

/**
 * Small helpers that make her feel quick: picking Haiku for simple commands, cutting Claude's
 * streamed reply into sentences she can start saying straight away, and short fillers for slow starts.
 */

/** Decides, on the phone and instantly, whether a request is a simple command Haiku can handle. */
object QuickRouter {

    // Anything that needs reading, searching, planning or long app work goes to the main model.
    private val COMPLEX = listOf(
        "weather", "news", "score", "price", "search", "find", "look up", "google", "why", "how ", "explain",
        "what is", "what's the", "who is", "tell me about", "order", "book", "buy", "pay", "and then", "after that",
        "summar", "plan", "write", "email", "translate", "compare", "recommend", "suggest", "story", "joke",
        "screen", "read my", "what did", "yesterday",
        "வானிலை", "செய்தி", "ஏன்", "எப்படி", "யார்", "எவ்வளவு", "தேடு", "சொல்லு",
        "yen ", "eppadi", "evlo", "yaaru", "thedu",
    )

    // Everyday phone commands Haiku does reliably.
    private val SIMPLE = listOf(
        "flashlight", "torch", "light", "volume", "louder", "quieter", "mute", "pause", "play", "next", "previous",
        "song", "music", "alarm", "timer", "remind", "reminder", "call", "ring", "open", "home", "back", "lock",
        "battery", "time", "message", "whatsapp", "sms", "text", "send", "reply", "stop", "cancel", "remember", "forget",
        "youtube", "notification", "messages",
        "அனுப்பு", "போடு", "வை", "திற", "கால்", "நிறுத்து", "அலாரம்", "மெசேஜ்",
        "anuppu", "podu", "vai", "thira", "pannu", "niruthu",
    )

    // A short answer to her question ("yes", "சரி") is always quick.
    private val ANSWERS = listOf(
        "yes", "yeah", "yep", "no", "nope", "ok", "okay", "sure", "send it", "do it", "go ahead", "cancel", "stop",
        "seri", "sari", "aama", "venda", "vendaam", "சரி", "ஆமா", "ஆமாம்", "வேண்டாம்", "இல்லை", "அனுப்பு",
    )

    fun isQuick(text: String): Boolean {
        val t = " " + text.lowercase().replace(Regex("[,.!?।]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val words = t.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 12) return false
        if (COMPLEX.any { it in t }) return false
        if (words.size <= 3 && ANSWERS.any { " $it " in t || t.trim() == it }) return true
        return SIMPLE.any { it in t }
    }
}

/** Collects Claude's streamed text and hands over whole sentences as soon as each one is complete. */
class SentenceSplitter(private val emit: (String) -> Unit) {
    private val buf = StringBuilder()

    fun add(text: String) {
        buf.append(text)
        while (true) {
            val cut = findCut()
            if (cut < 0) break
            val sentence = buf.substring(0, cut)
            buf.delete(0, cut)
            sentence.trim().takeIf { it.isNotEmpty() }?.let(emit)
        }
    }

    fun flush() {
        buf.toString().trim().takeIf { it.isNotEmpty() }?.let(emit)
        buf.setLength(0)
    }

    /** End of a sentence followed by a space, once there's enough to sound natural ("3.5" isn't cut). */
    private fun findCut(): Int {
        for (i in MIN_CHARS until buf.length - 1) {
            val c = buf[i]
            if ((c == '.' || c == '!' || c == '?' || c == '…' || c == '।' || c == '\n') && buf[i + 1].isWhitespace()) return i + 1
        }
        return -1
    }

    private companion object { const val MIN_CHARS = 8 }
}

/** What she says when Claude is slow to start, so there's never dead silence. Played from the phone's cache. */
object Fillers {
    private val tamil = listOf("ஒரு நிமிஷம்…", "சரி, பார்க்கிறேன்…", "இதோ, பார்க்கிறேன்…")
    private val english = listOf("One sec…", "Let me check…", "On it…")

    fun all() = tamil + english

    /** Tamil filler if you spoke Tamil, English otherwise. */
    fun pick(heard: String) = (if (heard.any { it in '\u0B80'..'\u0BFF' }) tamil else english).random()
}
