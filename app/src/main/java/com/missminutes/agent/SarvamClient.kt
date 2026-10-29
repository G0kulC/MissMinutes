package com.missminutes.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sarvam AI: Saaras (speech to text) and Bulbul (streamed text to speech) for Tamil, English and Tanglish. */
class SarvamClient(private val prefs: Prefs) : TtsEngine {
    companion object {
        // If Sarvam renames models or fields, update them here (see docs.sarvam.ai).
        const val STT_URL = "https://api.sarvam.ai/speech-to-text"
        const val TTS_STREAM_URL = "https://api.sarvam.ai/text-to-speech/stream"
        const val STT_MODEL = "saaras:v4"
        // Tamil + codemix: Tamil words in Tamil script, English words in English. Auto-detect ("unknown")
        // mistook Tamil and Tanglish for Hindi or Telugu, and pure English still comes out as English.
        const val STT_LANGUAGE = "ta-IN"
        const val STT_MODE = "codemix"
        /** Speech arrives as raw 16-bit mono PCM at this rate. */
        const val STREAM_RATE = 22050

        /** v4-flash voices have styled names like "shalini_en_companion"; the plain names are bulbul:v3. */
        fun ttsModel(speaker: String) = if ('_' in speaker) "bulbul:v4-flash" else "bulbul:v3"
    }

    class SarvamException(val code: Int, msg: String) : IOException(msg)

    /** The voice she speaks with right now. */
    override val speaker: String get() = prefs.sarvamSpeaker

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", STT_MODEL)
            .addFormDataPart("language_code", STT_LANGUAGE)
            .addFormDataPart("mode", STT_MODE)
            .build()
        http.newCall(authed(Request.Builder().url(STT_URL).post(body))).execute().use { resp ->
            JSONObject(checked(resp).body!!.string()).optString("transcript").trim()
        }
    }

    /**
     * A call that streams [text] as speech. Execute it, pass the response through [checked], then read raw
     * PCM ([STREAM_RATE] Hz, 16-bit, mono) from the body as it's generated. Cancel the call to stop early.
     */
    override fun speechCall(text: String): Call {
        val lang = if (text.any { it in '஀'..'௿' }) "ta-IN" else "en-IN"
        val payload = JSONObject()
            .put("text", text.take(3000))
            .put("language_code", lang)
            .put("speaker", speaker)
            .put("model", ttsModel(speaker))
            .put("output_audio_codec", "linear16")
            .put("speech_sample_rate", STREAM_RATE)
        val req = Request.Builder().url(TTS_STREAM_URL)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(authed(req))
    }

    override fun checked(resp: Response): Response {
        if (!resp.isSuccessful) {
            val text = resp.body?.string().orEmpty()
            throw SarvamException(resp.code, "Sarvam ${resp.code}: ${text.take(200)}")
        }
        return resp
    }

    private fun authed(builder: Request.Builder): Request {
        val key = prefs.sarvamKey
        if (key.isBlank()) throw SarvamException(401, "Add your Sarvam API key in the Miss Minutes app")
        return builder.header("api-subscription-key", key).build()
    }
}
