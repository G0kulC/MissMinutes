package com.missminutes.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All settings. API keys are encrypted with a key kept in the phone's secure hardware. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("miss_minutes", Context.MODE_PRIVATE)

    init { migrateFromV1() }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun putStr(key: String, v: String) = sp.edit().putString(key, v.trim()).apply()
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun secret(key: String) = KeyVault.decrypt(str("${key}_enc", ""))
    private fun putSecret(key: String, v: String) = putStr("${key}_enc", KeyVault.encrypt(v.trim()))

    var claudeKey: String get() = secret("claude_key"); set(v) = putSecret("claude_key", v)
    var sarvamKey: String get() = secret("sarvam_key"); set(v) = putSecret("sarvam_key", v)

    /** A model that's no longer offered (Fable) falls back to Sonnet. */
    var model: String
        get() = str("model", BrainModel.BALANCED.id).takeIf { BrainModel.byId(it) != null } ?: BrainModel.BALANCED.id
        set(v) = putStr("model", v)
    /** Simple commands (flashlight, calls, alarms) go to Haiku for instant replies. */
    var quickReplies: Boolean get() = bool("quick_replies", true); set(v) = putBool("quick_replies", v)
    // Falls back to the default if a saved voice was retired by Sarvam (bulbul:v2 voices don't work on v3).
    var sarvamSpeaker: String
        get() = str("sarvam_speaker", Voices.default).takeIf { it in Voices.sarvam } ?: Voices.default
        set(v) = putStr("sarvam_speaker", v)
    /**
     * Pico Live: hears you while you talk (Sarvam streaming), answers after a shorter pause, and keeps the
     * conversation going without the wake word. Off is the classic listen, then think, then speak.
     */
    var picoLive: Boolean get() = bool("pico_live", true); set(v) = putBool("pico_live", v)
    /** Pico's colour (a [GhostTheme] name). */
    var ghostTheme: String get() = str("ghost_theme", GhostTheme.MINT.name); set(v) = putStr("ghost_theme", v)
    /** Short sound cues (wake, heard, success, error, alerts). Always silent on silent or vibrate. */
    var sounds: Boolean get() = bool("sounds", true); set(v) = putBool("sounds", v)
    /** Little feeling sounds ("awww", a giggle, "hmph") with the mood of a reply. */
    var moodSounds: Boolean get() = bool("mood_sounds", true); set(v) = putBool("mood_sounds", v)
    var countryCode: String get() = str("country_code", "91"); set(v) = putStr("country_code", v)

    var confirmBeforeSending: Boolean get() = bool("confirm", true); set(v) = putBool("confirm", v)
    var webSearch: Boolean get() = bool("web_search", true); set(v) = putBool("web_search", v)
    var keepListening: Boolean get() = bool("keep_listening", false); set(v) = putBool("keep_listening", v)
    var bargeIn: Boolean get() = bool("barge_in", true); set(v) = putBool("barge_in", v)
    var edgeGlow: Boolean get() = bool("edge_glow", true); set(v) = putBool("edge_glow", v)

    /** The assistant's name ("Pico" unless the user renamed them). The app itself is always called Pico. */
    var assistantName: String
        get() = str("assistant_name", Assistant.DEFAULT_NAME).ifBlank { Assistant.DEFAULT_NAME }
        set(v) = putStr("assistant_name", v)
    /** A [Presentation] name: decides the wording (she / he / they) and which voices are suggested. */
    var presentation: String get() = str("presentation", Presentation.FEMININE.name); set(v) = putStr("presentation", v)
    /** The user's city, for local answers ("weather here"). Optional. */
    var city: String get() = str("city", ""); set(v) = putStr("city", v)
    /** Use the user's name in some wake-up greetings ("Hey Gokul!"). */
    var greetWithName: Boolean get() = bool("greet_with_name", true); set(v) = putBool("greet_with_name", v)
    /** The agents the user created, as a JSON array (see Agents.kt). */
    var agentsJson: String get() = str("agents", "[]"); set(v) = putStr("agents", v)
    /** When the last backup file was saved (epoch ms), or 0. */
    var lastBackupAt: Long get() = sp.getLong("last_backup_at", 0L); set(v) = sp.edit().putLong("last_backup_at", v).apply()

    /** What wakes the assistant, in English letters ("hey pico"). Editable in the app; no training needed. */
    var wakePhrase: String
        get() = WakeWord.normalize(str("wake_phrase", WakeWord.DEFAULT_PHRASE)).ifEmpty { WakeWord.DEFAULT_PHRASE }
        set(v) = putStr("wake_phrase", WakeWord.normalize(v))
    /** What the assistant calls the user. Empty means no name is used. */
    var userName: String get() = str("user_name", ""); set(v) = putStr("user_name", v)
    /** Say a short hello ("சொல்லுங்க Arun!") when woken, before listening. */
    var greetOnWake: Boolean get() = bool("greet_on_wake", true); set(v) = putBool("greet_on_wake", v)
    /** The day (yyyyDDD) of the last greeting, so the first one each morning can say good morning. */
    var lastGreetDay: Int get() = sp.getInt("last_greet_day", 0); set(v) = sp.edit().putInt("last_greet_day", v).apply()
    /** Setup (onboarding) is done; returning users never see it again unless they ask to. */
    var welcomed: Boolean get() = bool("welcomed", false); set(v) = putBool("welcomed", v)

    var wakeSensitivity: Float
        get() = sp.getFloat("wake_sensitivity", 0.65f)
        set(v) = sp.edit().putFloat("wake_sensitivity", v.coerceIn(0.3f, 0.9f)).apply()

    /** Facts the user asked her to remember ("Amma is saved as Mom"). */
    var memories: List<String>
        get() = runCatching {
            val a = JSONArray(str("memories", "[]"))
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
        set(v) = putStr("memories", JSONArray(v.takeLast(MAX_MEMORIES)).toString())

    /** Routines: one phrase ("good night") runs several steps, written in plain words for Claude to carry out. */
    var routines: Map<String, String>
        get() = runCatching {
            val o = JSONObject(str("routines", "{}")) // new installs start with no routines at all
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        set(v) = putStr("routines", JSONObject(v).toString())

    /** The last few conversations (see [Recent]), newest last, for the Home screen. */
    var recentJson: String get() = str("recent", "[]"); set(v) = putStr("recent", v)
    /** Memory imports so far: when, from what, and how many were added. */
    var importsJson: String get() = str("imports", "[]"); set(v) = putStr("imports", v)
    /** Not-available mode (see [Away]): its state, what happened, and who already got the auto-reply. */
    var awayJson: String get() = str("away", ""); set(v) = putStr("away", v)
    var awayLogJson: String get() = str("away_log", "[]"); set(v) = putStr("away_log", v)
    var awayRepliedJson: String get() = str("away_replied", "[]"); set(v) = putStr("away_replied", v)
    var awayTextCallers: Boolean get() = bool("away_text_callers", true); set(v) = putBool("away_text_callers", v)
    var awayReplyChats: Boolean get() = bool("away_reply_chats", true); set(v) = putBool("away_reply_chats", v)
    var awayLetStarred: Boolean get() = bool("away_let_starred", true); set(v) = putBool("away_let_starred", v)
    /** Auto-replies only go to saved contacts (no texts to spam callers, offers or bank alerts). */
    var awayContactsOnly: Boolean get() = bool("away_contacts_only", true); set(v) = putBool("away_contacts_only", v)
    var awayLetRepeat: Boolean get() = bool("away_let_repeat", true); set(v) = putBool("away_let_repeat", v)
    /** The user's own auto-reply ({until} becomes the time), or empty for the standard one. */
    var awayMessage: String get() = str("away_message", ""); set(v) = putStr("away_message", v)
    /** The day (year*1000 + day of year) the morning briefing was last given. */
    var lastBriefingDay: Int get() = sp.getInt("last_briefing_day", 0); set(v) = sp.edit().putInt("last_briefing_day", v).apply()
    /** "Chat for me": whose chat the assistant is keeping going, and the last lines of it. */
    var chatJson: String get() = str("chat", ""); set(v) = putStr("chat", v)
    var chatLogJson: String get() = str("chat_log", "[]"); set(v) = putStr("chat_log", v)
    /** How many times the camera button's tip has been shown (it's shown twice, then never again). */
    var cameraHintShown: Int get() = sp.getInt("camera_hint", 0); set(v) = sp.edit().putInt("camera_hint", v).apply()
    /** Where the phone was last seen, city level (see [Place]). */
    var lastPlaceJson: String get() = str("last_place", ""); set(v) = putStr("last_place", v)
    /** The user wants the assistant listening (they tapped Start and haven't tapped Stop). */
    var wantRunning: Boolean get() = bool("want_running", false); set(v) = putBool("want_running", v)
    /** The setup step to come back to if the app closed half-way through. */
    var setupStep: Int get() = sp.getInt("setup_step", 0); set(v) = sp.edit().putInt("setup_step", v).apply()

    /** Pending reminders and notification watches, as JSON arrays (see Reminders.kt). */
    var remindersJson: String get() = str("reminders", "[]"); set(v) = putStr("reminders", v)
    var watchesJson: String get() = str("watches", "[]"); set(v) = putStr("watches", v)

    companion object {
        /** Kept small enough to send with every request; imports stop at this many. */
        const val MAX_MEMORIES = 150

        /** Ready-made routines, kept only for installs from before they were removed for new users. */
        private val DEFAULT_ROUTINES: String = JSONObject(mapOf(
            "good night" to "Turn on Do Not Disturb until 6 AM, set brightness to 10 percent, set media volume to 20 percent, " +
                "mention tomorrow's first calendar event if there is one, and wish me good night.",
            "good morning" to "Turn Do Not Disturb off, turn auto brightness on, then tell me today's weather and today's " +
                "calendar events in two or three short sentences.",
            "i'm driving" to "Set media volume to 80 percent and ask me where I'm going, then start navigation there.",
            "i'm home" to "Turn Wi-Fi on, put the ringer back on, turn Do Not Disturb off, and tell me any reminders " +
                "left for today.",
            "going out" to "Tell me the battery level, the weather right now and my next calendar event, in two short sentences.",
            "focus mode" to "Turn on Do Not Disturb for 60 minutes and say you'll keep things quiet for an hour.",
            "meeting mode" to "Put the phone on silent and set a reminder in 60 minutes to turn the ringer back on.",
            "workout time" to "Set media volume to 80 percent and play a workout music playlist on YouTube.",
            "movie time" to "Turn on Do Not Disturb, set brightness to 40 percent and media volume to 70 percent.",
            "lunch break" to "Start a 30 minute timer called lunch and tell me if I have a calendar event in the next hour.",
        )).toString()
    }

    /** v1 stored keys as plain text; move them into encrypted storage. */
    private fun migrateFromV1() {
        for (k in listOf("claude_key", "sarvam_key")) {
            val plain = sp.getString(k, null) ?: continue
            if (plain.isNotBlank() && str("${k}_enc", "").isBlank()) putSecret(k, plain)
            sp.edit().remove(k).apply()
        }
        if (sp.contains("claude_model")) sp.edit().remove("claude_model").apply()
        // The Picovoice wake word was replaced by Vosk, which needs no key.
        sp.edit().remove("pico_key").remove("pico_key_enc").apply()
        // Existing installs keep the ready-made routines (their own and their changes win); new installs start
        // empty, with no sample data of any kind.
        if (!bool("routines_v2", false)) {
            if (welcomed) {
                val defaults = JSONObject(DEFAULT_ROUTINES)
                routines = defaults.keys().asSequence().associateWith { defaults.getString(it) } + routines
            }
            putBool("routines_v2", true)
        }
        // ElevenLabs was removed (too costly per minute): forget its key and settings.
        sp.edit().remove("eleven_key_enc").remove("eleven_voice").remove("eleven_model")
            .remove("voice_provider").remove("live_mode").apply()
    }
}

/** AES-GCM encryption with a key stored in the Android Keystore. */
object KeyVault {
    private const val ALIAS = "miss_minutes_keys"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private const val PLAIN = "plain:"

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        } catch (e: Exception) {
            PLAIN + plain // a few phones have a broken keystore; keep working rather than crash
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (stored.startsWith(PLAIN)) return stored.removePrefix(PLAIN)
        return try {
            val all = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            String(c.doFinal(all.copyOfRange(12, all.size)))
        } catch (e: Exception) {
            ""
        }
    }
}
