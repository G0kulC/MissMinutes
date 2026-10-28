package com.missminutes.agent

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sarvam AI: Saarika (speech to text) and Bulbul (text to speech) for Tamil, English and Tanglish. */
class SarvamClient(private val prefs: Prefs) {
    companion object {
        // If Sarvam renames models or fields, update them here (see docs.sarvam.ai).
        const val STT_URL = "https://api.sarvam.ai/speech-to-text"
        const val TTS_URL = "https://api.sarvam.ai/text-to-speech"
        const val STT_MODEL = "saarika:v2.5"
        const val TTS_MODEL = "bulbul:v2"
    }

    class SarvamException(val code: Int, msg: String) : IOException(msg)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", STT_MODEL)
            .addFormDataPart("language_code", "unknown") // auto-detects Tamil, English or a mix
            .build()
        call(Request.Builder().url(STT_URL).post(body)).optString("transcript").trim()
    }

    suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val lang = if (text.any { it in '\u0B80'..'\u0BFF' }) "ta-IN" else "en-IN"
        val clean = text.take(480)
        val base = JSONObject()
            .put("target_language_code", lang)
            .put("speaker", prefs.sarvamSpeaker)
            .put("model", TTS_MODEL)
        val json = try {
            post(JSONObject(base.toString()).put("text", clean))
        } catch (e: SarvamException) {
            // Older API versions expected "inputs": [text]
            if (e.code in 400..422) post(JSONObject(base.toString()).put("inputs", JSONArray().put(clean)))
            else throw e
        }
        Base64.decode(json.getJSONArray("audios").getString(0), Base64.DEFAULT)
    }

    private fun post(payload: JSONObject): JSONObject = call(
        Request.Builder().url(TTS_URL)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
    )

    private fun call(builder: Request.Builder): JSONObject {
        val key = prefs.sarvamKey
        if (key.isBlank()) throw SarvamException(401, "Add your Sarvam API key in the Miss Minutes app")
        val req = builder.header("api-subscription-key", key).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SarvamException(resp.code, "Sarvam ${resp.code}: ${text.take(200)}")
            return JSONObject(text)
        }
    }
}
