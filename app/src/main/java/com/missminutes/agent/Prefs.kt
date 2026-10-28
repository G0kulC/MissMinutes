package com.missminutes.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
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

    var model: String get() = str("model", BrainModel.BEST.id); set(v) = putStr("model", v)
    // Falls back to the default if a saved voice was retired by Sarvam (bulbul:v2 voices don't work on v3).
    var sarvamSpeaker: String
        get() = str("sarvam_speaker", Voices.default).takeIf { it in Voices.sarvam } ?: Voices.default
        set(v) = putStr("sarvam_speaker", v)
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

    /** Pending reminders and notification watches, as JSON arrays (see Reminders.kt). */
    var remindersJson: String get() = str("reminders", "[]"); set(v) = putStr("reminders", v)
    var watchesJson: String get() = str("watches", "[]"); set(v) = putStr("watches", v)

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
