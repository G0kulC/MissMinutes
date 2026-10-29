package com.missminutes.agent

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs text to speech, streamed as raw PCM at the same rate as Sarvam's, so the player treats both alike.
 * The language is detected from the text, so Tamil, English and Tanglish all work with one voice.
 */
class ElevenLabsClient(private val prefs: Prefs) : TtsEngine {
    companion object {
        // If ElevenLabs renames things, update them here (see elevenlabs.io/docs).
        const val TTS_URL = "https://api.elevenlabs.io/v1/text-to-speech"
    }

    class ElevenException(val code: Int, msg: String) : IOException(msg)

    override val speaker: String get() = "eleven:${prefs.elevenModel}:${prefs.elevenVoice}"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun speechCall(text: String): Call {
        val key = prefs.elevenKey
        if (key.isBlank()) throw ElevenException(401, "Add your ElevenLabs API key in the Miss Minutes app")
        val payload = JSONObject()
            .put("text", text.take(3000))
            .put("model_id", prefs.elevenModel)
        val req = Request.Builder()
            .url("$TTS_URL/${prefs.elevenVoice}/stream?output_format=pcm_${SarvamClient.STREAM_RATE}")
            .header("xi-api-key", key)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req)
    }

    override fun checked(resp: Response): Response {
        if (!resp.isSuccessful) {
            val text = resp.body?.string().orEmpty()
            throw ElevenException(resp.code, "ElevenLabs ${resp.code}: ${text.take(200)}")
        }
        return resp
    }
}

/** The voice service picked in the app. If ElevenLabs fails before a word is said, Sarvam's voice takes over. */
class VoiceSwitch(
    private val prefs: Prefs,
    private val sarvam: SarvamClient,
    private val eleven: ElevenLabsClient,
) : TtsEngine {
    private val active: TtsEngine get() = if (prefs.voiceProvider == Prefs.ELEVENLABS) eleven else sarvam

    override val speaker: String get() = active.speaker
    override fun speechCall(text: String) = active.speechCall(text)
    override fun checked(resp: Response) = active.checked(resp)
    override val backup: TtsEngine? get() = if (active === eleven && prefs.sarvamKey.isNotBlank()) sarvam else null
}
