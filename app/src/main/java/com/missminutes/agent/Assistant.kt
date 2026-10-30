package com.missminutes.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How the assistant presents. It only changes the words the app uses for them and which voices are suggested,
 * never what they can do.
 */
enum class Presentation(val title: String, val detail: String, val they: String, val them: String, val their: String) {
    FEMININE("Feminine", "Uses she and her, and suggests feminine voices.", "she", "her", "her"),
    MASCULINE("Masculine", "Uses he and him, and suggests masculine voices.", "he", "him", "his"),
    NEUTRAL("Neutral", "Uses they and them. Any voice can be chosen.", "they", "them", "their");

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: FEMININE
    }
}

/** A Sarvam voice and how it presents (Sarvam has no neutral voices). */
class VoiceOption(val id: String, val title: String, val presentation: Presentation)

/**
 * The assistant as the user set them up: their name ("Pico" unless renamed) and the wording that goes with their
 * presentation. Everything the app says about the assistant comes from here, never a hard-coded "Pico" or "she".
 */
class Assistant(prefs: Prefs) {
    val name: String = prefs.assistantName
    val presentation: Presentation = Presentation.of(prefs.presentation)

    val they get() = presentation.they
    @get:JvmName("getTheyCap") val They get() = they.replaceFirstChar(Char::uppercase)
    val them get() = presentation.them
    val their get() = presentation.their

    /** The verb that goes with [they]: "she speaks" / "they speak". */
    fun verb(singular: String, plural: String) = if (presentation == Presentation.NEUTRAL) plural else singular

    /** "she's" / "they're". */
    val theyre get() = if (presentation == Presentation.NEUTRAL) "they're" else "$they's"
    @get:JvmName("getTheyreCap") val Theyre get() = theyre.replaceFirstChar(Char::uppercase)

    companion object {
        const val DEFAULT_NAME = "Pico"

        /** A tidy assistant name: single spaces, at most 20 characters. */
        fun cleanName(raw: String) = raw.trim().replace(Regex("\\s+"), " ").take(20)

        /**
         * Renames the assistant. When the wake word was the old name's ("hey pico"), it follows the new name
         * ("hey nova") if the offline listener can hear that name. Returns a note for the user when the wake word
         * had to stay as it was, or null.
         */
        suspend fun rename(context: Context, prefs: Prefs, raw: String): String? {
            val name = cleanName(raw).ifEmpty { DEFAULT_NAME }
            val followsName = prefs.wakePhrase == WakeWord.normalize("hey ${prefs.assistantName}")
            prefs.assistantName = name
            if (!followsName) return null // they chose their own wake word; leave it alone
            val wake = WakeWord.normalize("hey $name")
            if (wake == prefs.wakePhrase) return null
            val kept = "Your wake word stays “${WakeWord.display(prefs.wakePhrase)}”"
            WakeWord.shapeProblem(wake)?.let { return "$kept, because “Hey $name” can't be a wake word. $it" }
            val unknown = withContext(Dispatchers.IO) { WakeWord.unknownWords(context, wake) }
            if (!unknown.isNullOrEmpty()) {
                return "$kept: the offline listener doesn't know the word “${unknown.first()}”. " +
                    "You can pick another wake word in the Assistant tab."
            }
            prefs.wakePhrase = wake
            return null
        }
    }
}
