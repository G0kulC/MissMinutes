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
    var picovoiceKey: String get() = secret("pico_key"); set(v) = putSecret("pico_key", v)
    var sarvamKey: String get() = secret("sarvam_key"); set(v) = putSecret("sarvam_key", v)

    var model: String get() = str("model", BrainModel.BEST.id); set(v) = putStr("model", v)
    var sarvamSpeaker: String get() = str("sarvam_speaker", "anushka"); set(v) = putStr("sarvam_speaker", v)
    var countryCode: String get() = str("country_code", "91"); set(v) = putStr("country_code", v)

    var confirmBeforeSending: Boolean get() = bool("confirm", true); set(v) = putBool("confirm", v)
    var webSearch: Boolean get() = bool("web_search", true); set(v) = putBool("web_search", v)
    var keepListening: Boolean get() = bool("keep_listening", false); set(v) = putBool("keep_listening", v)
    var edgeGlow: Boolean get() = bool("edge_glow", true); set(v) = putBool("edge_glow", v)

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

    /** v1 stored keys as plain text; move them into encrypted storage. */
    private fun migrateFromV1() {
        for (k in listOf("claude_key", "pico_key", "sarvam_key")) {
            val plain = sp.getString(k, null) ?: continue
            if (plain.isNotBlank() && str("${k}_enc", "").isBlank()) putSecret(k, plain)
            sp.edit().remove(k).apply()
        }
        if (sp.contains("claude_model")) sp.edit().remove("claude_model").apply()
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
