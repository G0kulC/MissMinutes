package com.missminutes.agent

/**
 * Small helpers that make her feel quick: picking Haiku for simple commands, cutting Claude's
 * streamed reply into sentences she can start saying straight away, and short fillers for slow starts.
 */

/** Decides, on the phone and instantly, whether a request is a simple command Haiku can handle. */
object QuickRouter {

    // Anything that needs reading, searching, planning or long app work goes to the main model.
    private val COMPLEX = listOf(
        "news", "score", "price", "search", "find", "look up", "google", "why", "how ", "explain",
        "what is", "what's the", "who is", "tell me about", "order", "book", "buy", "pay", "and then", "after that",
        "summar", "plan", "write", "email", "translate", "compare", "recommend", "suggest", "story", "joke",
        "screen", "read my", "what did", "yesterday",
        "செய்தி", "ஏன்", "எப்படி", "யார்", "எவ்வளவு", "தேடு", "சொல்லு",
        "yen ", "eppadi", "evlo", "yaaru", "thedu",
    )

    private val WEATHER = listOf("weather", "temperature", "will it rain", "is it raining", "வானிலை", "மழை வருமா")

    // Everyday phone commands Haiku does reliably.
    private val SIMPLE = listOf(
        "flashlight", "torch", "light", "volume", "louder", "quieter", "mute", "pause", "play", "next", "previous",
        "song", "music", "alarm", "timer", "remind", "reminder", "call", "ring", "open", "home", "back", "lock",
        "battery", "time", "message", "whatsapp", "sms", "text", "send", "reply", "stop", "cancel", "remember", "forget",
        "youtube", "notification", "messages", "hotspot", "wifi", "wi-fi", "bluetooth", "airplane", "dark mode", "settings", "brightness", "brighter", "dim", "sound", "silent", "ringtone",
        "அனுப்பு", "போடு", "வை", "திற", "கால்", "நிறுத்து", "அலாரம்", "மெசேஜ்", "வெளிச்சம்", "சத்தம்", "வால்யூம்",
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
        // Weather has its own tool, so even "what's the weather in Coimbatore?" is a quick one.
        if (WEATHER.any { it in t }) return true
        if (COMPLEX.any { it in t }) return false
        if (words.size <= 3 && ANSWERS.any { " $it " in t || t.trim() == it }) return true
        return SIMPLE.any { it in t }
    }
}

/**
 * Collects Claude's streamed text and hands over whole sentences as soon as each one is complete.
 * With [earlyStart], the very first piece can end at a comma, so her voice starts on the first phrase.
 */
class SentenceSplitter(
    private val earlyStart: Boolean = false,
    /** The user's name: a first phrase is never cut just before it ("…about you," | "Gokul?"). */
    private val name: String = "",
    private val emit: (String) -> Unit,
) {
    private val buf = StringBuilder()
    private var first = true

    fun add(text: String) {
        buf.append(text)
        while (true) {
            val cut = findCut()
            if (cut < 0) break
            val sentence = buf.substring(0, cut)
            buf.delete(0, cut)
            sentence.trim().takeIf { it.isNotEmpty() }?.let { first = false; emit(it) }
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
            val end = c == '.' || c == '!' || c == '?' || c == '…' || c == '।' || c == '\n'
            val pause = earlyStart && first && i >= FIRST_PHRASE_CHARS && (c == ',' || c == ';' || c == ':') &&
                // Wait until the next words are in: a lone "Gokul?" after the cut kept her name in and hid the question.
                buf.length - i > LOOKAHEAD_CHARS && !nameFollows(i + 1)
            if ((end || pause) && buf[i + 1].isWhitespace()) return i + 1
        }
        return -1
    }

    private fun nameFollows(at: Int): Boolean =
        name.isNotEmpty() && buf.substring(at).trimStart().startsWith(name, ignoreCase = true)

    private companion object {
        const val MIN_CHARS = 8
        /** Characters needed after a comma before cutting there, so we know what comes next. */
        const val LOOKAHEAD_CHARS = 12
        /** A first phrase shorter than this sounds clipped, so wait for more. */
        const val FIRST_PHRASE_CHARS = 20
    }
}

/** What she says when Claude is slow to start, so there's never dead silence. Played from the phone's cache. */
object Fillers {
    private val tamil = listOf("ஒரு நிமிஷம்…", "சரி, பார்க்கிறேன்…", "இதோ, பார்க்கிறேன்…")
    private val english = listOf("One sec…", "Let me check…", "On it…")

    fun all() = tamil + english

    /** Tamil filler if you spoke Tamil, English otherwise. */
    fun pick(heard: String) = (if (heard.any { it in '\u0B80'..'\u0BFF' }) tamil else english).random()
}

/**
 * Takes Pico's mood tags ("[happy]") out of Claude's streamed text before it's spoken: the tag sets the ghost's
 * face, and only the words go on to be said. Anything in brackets that isn't a mood is passed through unchanged.
 */
class MoodTags(private val onMood: (String) -> Unit, private val forward: (String) -> Unit) {
    private val pending = StringBuilder()

    fun add(text: String) {
        val out = StringBuilder()
        for (ch in text) {
            when {
                pending.isNotEmpty() -> {
                    pending.append(ch)
                    if (ch == ']') {
                        val word = pending.substring(1, pending.length - 1).trim().lowercase()
                        if (word in MOODS) onMood(word) else out.append(pending)
                        pending.setLength(0)
                    } else if (pending.length > MAX_TAG) {
                        out.append(pending)
                        pending.setLength(0)
                    }
                }
                ch == '[' -> pending.append(ch)
                else -> out.append(ch)
            }
        }
        if (out.isNotEmpty()) forward(out.toString())
    }

    fun flush() {
        if (pending.isNotEmpty()) forward(pending.toString())
        pending.setLength(0)
    }

    companion object {
        val MOODS = setOf("calm", "happy", "playful", "excited", "love", "sad", "surprised", "confused", "scared", "angry")
        private const val MAX_TAG = 12
    }
}
