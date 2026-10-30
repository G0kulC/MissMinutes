package com.missminutes.agent

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sarvam AI: Saaras (speech to text) and Bulbul (streamed text to speech) for Tamil, English and Tanglish. */
class SarvamClient(private val prefs: Prefs) {
    companion object {
        // If Sarvam renames models or fields, update them here (see docs.sarvam.ai).
        const val STT_URL = "https://api.sarvam.ai/speech-to-text"
        const val STT_STREAM_URL = "wss://api.sarvam.ai/speech-to-text/ws"
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

    /** An agent's own voice while it's talking; null for the main assistant's. */
    @Volatile var speakerOverride: String? = null

    /** The voice speaking right now. */
    val speaker: String get() = speakerOverride ?: prefs.sarvamSpeaker

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
     * Opens the connection to Sarvam ahead of time (while she greets you), so the first real request skips the
     * handshake. Any answer will do; it's the open connection that's kept.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url("https://api.sarvam.ai/").head().build()).execute().close() }
    }

    /** Starts live hearing for one turn: send it the microphone with [SttStream.send] while you talk. */
    fun listen(): SttStream = SttStream()

    /**
     * Live hearing: your voice goes to Sarvam over a WebSocket while you talk, so the words are ready the moment
     * you stop instead of after uploading the recording. [finish] returns null when streaming didn't work out;
     * the caller then transcribes the recording the usual way, so nothing is lost.
     */
    inner class SttStream internal constructor() {
        private val parts = ArrayList<String>()
        @Volatile private var speaking = false
        @Volatile private var failed = false
        @Volatile private var flushedAt = 0L
        @Volatile private var heardAfterFlush = false
        private val socket: WebSocket? = prefs.sarvamKey.takeIf { it.isNotBlank() }?.let { key ->
            val url = "$STT_STREAM_URL?model=$STT_MODEL&mode=$STT_MODE&language-code=$STT_LANGUAGE" +
                "&sample_rate=${VoiceRecorder.SAMPLE_RATE}&input_audio_codec=pcm_s16le&vad_signals=true&flush_signal=true"
            http.newWebSocket(Request.Builder().url(url).header("Api-Subscription-Key", key).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                    val data = msg.optJSONObject("data")
                    when (msg.optString("type")) {
                        "data" -> {
                            val words = data?.optString("transcript").orEmpty().trim()
                            if (words.isNotEmpty()) synchronized(parts) { parts += words }
                            speaking = false
                            if (flushedAt > 0) heardAfterFlush = true
                        }
                        "events" -> if (data?.optString("signal_type") == "START_SPEECH") speaking = true
                        "error" -> {
                            PicoLog.w("Live hearing error: $text")
                            failed = true
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    PicoLog.w("Live hearing failed (${response?.code})", t)
                    failed = true
                }
            })
        }

        /** 16-bit mono PCM from the microphone. Safe from any thread; queued until the socket opens. */
        fun send(pcm: ByteArray, length: Int) {
            if (failed) return
            val audio = JSONObject()
                .put("data", Base64.encodeToString(pcm, 0, length, Base64.NO_WRAP))
                .put("sample_rate", VoiceRecorder.SAMPLE_RATE.toString())
                .put("encoding", "audio/wav")
            socket?.send(JSONObject().put("audio", audio).toString())
        }

        /**
         * You've stopped talking: asks Sarvam for the last words and returns everything heard, or null if it
         * isn't sure it has it all (the caller then uses the recording instead).
         */
        suspend fun finish(timeoutMs: Long = 1500): String? {
            val ws = socket ?: return null
            if (failed) return null
            flushedAt = System.currentTimeMillis()
            ws.send(JSONObject().put("type", "flush").toString())
            val end = flushedAt + timeoutMs
            while (System.currentTimeMillis() < end && !failed && !heardAfterFlush) {
                // Sarvam may already have sent everything when it noticed you stop, with nothing left to flush.
                if (!speaking && synchronized(parts) { parts.isNotEmpty() } && System.currentTimeMillis() - flushedAt > 300) break
                // Nothing at all after 700 ms: a word this short ("Time.") never reaches the live transcript, so
                // stop waiting (it used to take the full 1.5 s) and let the upload answer.
                if (synchronized(parts) { parts.isEmpty() } && System.currentTimeMillis() - flushedAt > 700) break
                delay(40)
            }
            close()
            val complete = heardAfterFlush || (!speaking && !failed)
            val words = synchronized(parts) { parts.joinToString(" ") }.trim()
            return words.takeIf { complete && it.isNotEmpty() }
        }

        fun close() {
            socket?.close(1000, null)
        }
    }

    /**
     * A call that streams [text] as speech. Execute it, pass the response through [checked], then read raw
     * PCM ([STREAM_RATE] Hz, 16-bit, mono) from the body as it's generated. Cancel the call to stop early.
     */
    fun speechCall(text: String): Call {
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

    fun checked(resp: Response): Response {
        if (!resp.isSuccessful) {
            val text = resp.body?.string().orEmpty()
            throw SarvamException(resp.code, "Sarvam ${resp.code}: ${text.take(200)}")
        }
        return resp
    }

    private fun authed(builder: Request.Builder): Request {
        val key = prefs.sarvamKey
        if (key.isBlank()) throw SarvamException(401, "Add your Sarvam API key in the Pico app")
        return builder.header("api-subscription-key", key).build()
    }
}
