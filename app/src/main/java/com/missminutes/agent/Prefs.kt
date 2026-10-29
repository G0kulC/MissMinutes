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
    /** Who speaks for her: [SARVAM] or [ELEVENLABS]. She always hears you with Sarvam. */
    var voiceProvider: String
        get() = str("voice_provider", SARVAM).takeIf { it == ELEVENLABS } ?: SARVAM
        set(v) = putStr("voice_provider", v)
    var elevenKey: String get() = secret("eleven_key"); set(v) = putSecret("eleven_key", v)
    var elevenVoice: String
        get() = str("eleven_voice", ElevenVoices.default.id).takeIf { id -> ElevenVoices.all.any { it.id == id } }
            ?: ElevenVoices.default.id
        set(v) = putStr("eleven_voice", v)
    var elevenModel: String
        get() = str("eleven_model", ElevenModel.TURBO.id).takeIf { ElevenModel.byId(it) != null } ?: ElevenModel.TURBO.id
        set(v) = putStr("eleven_model", v)
    /** Live mode: real-time ElevenLabs conversations instead of listen, then think, then speak. */
    var liveMode: Boolean get() = bool("live_mode", false); set(v) = putBool("live_mode", v)
    var countryCode: String get() = str("country_code", "91"); set(v) = putStr("country_code", v)

    var confirmBeforeSending: Boolean get() = bool("confirm", true); set(v) = putBool("confirm", v)
    var webSearch: Boolean get() = bool("web_search", true); set(v) = putBool("web_search", v)
    var keepListening: Boolean get() = bool("keep_listening", false); set(v) = putBool("keep_listening", v)
    var bargeIn: Boolean get() = bool("barge_in", true); set(v) = putBool("barge_in", v)
    var edgeGlow: Boolean get() = bool("edge_glow", true); set(v) = putBool("edge_glow", v)

    /** What wakes her, in English letters ("hey miss minutes"). Editable in the app; no training needed. */
    var wakePhrase: String
        get() = WakeWord.normalize(str("wake_phrase", WakeWord.DEFAULT_PHRASE)).ifEmpty { WakeWord.DEFAULT_PHRASE }
        set(v) = putStr("wake_phrase", WakeWord.normalize(v))
    /** What she calls the user. Empty means she doesn't use a name. */
    var userName: String get() = str("user_name", ""); set(v) = putStr("user_name", v)
    /** Say a short hello ("சொல்லுங்க Arun!") when woken, before listening. */
    var greetOnWake: Boolean get() = bool("greet_on_wake", true); set(v) = putBool("greet_on_wake", v)
    /** The day (yyyyDDD) of the last greeting, so the first one each morning can say good morning. */
    var lastGreetDay: Int get() = sp.getInt("last_greet_day", 0); set(v) = sp.edit().putInt("last_greet_day", v).apply()
    /** The welcome screen has been seen. */
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
        set(v) = putStr("memories", JSONArray(v.takeLast(60)).toString())

    /** Routines: one phrase ("good night") runs several steps, written in plain words for Claude to carry out. */
    var routines: Map<String, String>
        get() = runCatching {
            val o = JSONObject(str("routines", DEFAULT_ROUTINES))
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        set(v) = putStr("routines", JSONObject(v).toString())

    /** Pending reminders and notification watches, as JSON arrays (see Reminders.kt). */
    var remindersJson: String get() = str("reminders", "[]"); set(v) = putStr("reminders", v)
    var watchesJson: String get() = str("watches", "[]"); set(v) = putStr("watches", v)

    companion object {
        const val SARVAM = "sarvam"
        const val ELEVENLABS = "elevenlabs"

        /** Ready-made routines; the user can change or delete them by voice or in the app. */
        private val DEFAULT_ROUTINES: String = JSONObject(mapOf(
            "good night" to "Turn on Do Not Disturb until 6 AM, set brightness to 10 percent, set media volume to 20 percent, " +
                "mention tomorrow's first calendar event if there is one, and wish me good night.",
            "good morning" to "Turn Do Not Disturb off, turn auto brightness on, then tell me today's weather and today's " +
                "calendar events in two or three short sentences.",
            "i'm driving" to "Set media volume to 80 percent and ask me where I'm going, then start navigation there.",
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
