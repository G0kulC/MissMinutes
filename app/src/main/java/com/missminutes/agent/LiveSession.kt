package com.missminutes.agent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Live mode: one real-time ElevenLabs conversation. ElevenLabs hears you while you speak, Claude Haiku answers,
 * and her voice streams straight back, with interrupting built in. Phone actions run here on the phone; harder
 * jobs (web, step-by-step app work) go to the main Claude brain through the think_deeper tool.
 */
class LiveSession(
    private val context: Context,
    private val prefs: Prefs,
    private val actions: PhoneActions,
    private val brain: ClaudeBrain,
    private val ui: Ui,
) {
    /** Called from any thread. */
    interface Ui {
        fun state(s: OrbState)
        fun level(v: Float)
        fun activity(label: String)
        fun toolStarted(name: String)
        fun toolEnded(name: String, ok: Boolean)
    }

    companion object {
        /** The Miss Minutes agent on the user's ElevenLabs account. Its tools mirror TOOLS_JSON in ClaudeBrain. */
        const val AGENT_ID = "agent_0201m631fa28fvjagek5tvrpthnf"
        private const val SIGNED_URL = "https://api.elevenlabs.io/v1/convai/conversation/get-signed-url?agent_id="
        private const val TAG = "MissMinutes"
        private const val MIC_RATE = 16000
        private const val CHUNK_BYTES = MIC_RATE / 10 * 2 // 100 ms of 16-bit mono
        /** Hang up after this much quiet once she's done (before the agent's own 7 s "are you there?"). */
        private const val IDLE_END_MS = 6000L
        /** Longest wait for her to answer something you said before giving up. */
        private const val REPLY_WAIT_MS = 15000L
        /** While she talks, only sound this much louder than her own echo counts as you cutting in. */
        private const val ECHO_MARGIN = 2.2
        private const val GATE_MIN = 1500.0
        private val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastActivity = 0L
    @Volatile private var awaitingReply = false
    private var toolsRunning = 0

    /** One conversation, until it goes quiet, she hangs up, a call starts, or the caller cancels. */
    suspend fun run() = coroutineScope {
        val scope = this
        val url = signedUrl()
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.getOrNull()?.let { events.trySend(it) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Live: closed by ElevenLabs ($code $reason)")
                webSocket.close(1000, null)
                events.close()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                events.close(t)
            }
        })
        val speaker = Speaker()
        val restoreAudio = callAudio()
        try {
            // Not withTimeout: its exception counts as a cancellation, which would end the session silently.
            withTimeoutOrNull(10_000) { opened.await() } ?: throw IOException("Couldn't reach ElevenLabs")
            // The greeting ("சொல்லுங்க Gokul!") is set on the agent itself: ElevenLabs refuses a first_message
            // override even when the agent allows it. Starting in Tamil also hears Tanglish better.
            socket.send(JSONObject()
                .put("type", "conversation_initiation_client_data")
                .put("conversation_config_override", JSONObject().put("agent", JSONObject()
                    .put("language", "ta")
                    .put("prompt", JSONObject().put("prompt", brain.livePrompt()))))
                .toString())
            lastActivity = System.currentTimeMillis()
            awaitingReply = false
            launch(Dispatchers.IO) { speaker.writeLoop() }
            launch(Dispatchers.IO) { streamMic(socket, speaker) }
            launch { watch(socket, speaker) }
            for (event in events) scope.handle(event, socket, speaker)
        } finally {
            socket.close(1000, null)
            scope.coroutineContext.cancelChildren()
            speaker.release()
            restoreAudio()
        }
    }

    /**
     * Plays her through the phone's call path, on the loudspeaker unless a headset is in, because the phone's
     * echo cancelling only removes sound played as a call. Without it she hears herself and answers her own words.
     * Returns how to put things back.
     */
    private fun callAudio(): () -> Unit {
        val am = context.getSystemService(AudioManager::class.java)
        val oldMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = am.availableCommunicationDevices
            if (devices.none { it.type in HEADSETS }) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            }
            return { am.clearCommunicationDevice(); am.mode = oldMode }
        }
        @Suppress("DEPRECATION")
        val speakerOn = !am.isWiredHeadsetOn && !am.isBluetoothScoOn
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speakerOn
        @Suppress("DEPRECATION")
        return { am.isSpeakerphoneOn = false; am.mode = oldMode }
    }

    private fun CoroutineScope.handle(e: JSONObject, socket: WebSocket, speaker: Speaker) {
        val now = System.currentTimeMillis()
        when (e.optString("type")) {
            "conversation_initiation_metadata" -> {
                val format = e.optJSONObject("conversation_initiation_metadata_event")
                    ?.optString("agent_output_audio_format").orEmpty()
                speaker.open(format.removePrefix("pcm_").toIntOrNull() ?: 16000)
                Log.i(TAG, "Live: connected, voice $format")
            }
            "audio" -> e.optJSONObject("audio_event")?.optString("audio_base_64")?.takeIf { it.isNotEmpty() }?.let {
                awaitingReply = false
                speaker.play(Base64.decode(it, Base64.DEFAULT))
            }
            "interruption" -> speaker.flush()
            "ping" -> e.optJSONObject("ping_event")?.let {
                socket.send(JSONObject().put("type", "pong").put("event_id", it.optInt("event_id")).toString())
            }
            "vad_score" -> if ((e.optJSONObject("vad_score_event")?.optDouble("vad_score") ?: 0.0) > 0.6) lastActivity = now
            "user_transcript" -> {
                val heard = e.optJSONObject("user_transcription_event")?.optString("user_transcript").orEmpty()
                Log.i(TAG, "Live heard: $heard")
                if (heard.isNotBlank()) { awaitingReply = true; lastActivity = now }
            }
            "agent_response" -> {
                awaitingReply = false
                Log.i(TAG, "Live said: ${e.optJSONObject("agent_response_event")?.optString("agent_response")}")
            }
            "client_tool_call" -> e.optJSONObject("client_tool_call")?.let { call -> launch { runTool(call, socket) } }
        }
    }

    private suspend fun runTool(call: JSONObject, socket: WebSocket) {
        val name = call.optString("tool_name")
        val params = call.optJSONObject("parameters") ?: JSONObject()
        toolsRunning++
        ui.activity(ToolLabels.label(name, params))
        ui.toolStarted(name)
        val result = try {
            if (name == "think_deeper") brain.handle(params.optString("task"), deep = true)
            else actions.run(name, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "ERROR: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            toolsRunning--
            lastActivity = System.currentTimeMillis()
        }
        Log.i(TAG, "Live tool $name: ${result.take(120)}")
        ui.toolEnded(name, !result.startsWith("ERROR"))
        socket.send(JSONObject()
            .put("type", "client_tool_result")
            .put("tool_call_id", call.optString("tool_call_id"))
            .put("result", result)
            .put("is_error", result.startsWith("ERROR"))
            .toString())
        if (actions.quietAfterAction) { // a phone call started: get off the line
            delay(300)
            socket.close(1000, "call started")
        }
    }

    /** Keeps the orb in step with the conversation, and hangs up once it has gone quiet. */
    private suspend fun watch(socket: WebSocket, speaker: Speaker) {
        var shown: OrbState? = null
        while (true) {
            delay(100)
            val now = System.currentTimeMillis()
            if (speaker.busy || toolsRunning > 0) lastActivity = now
            val state = when {
                speaker.busy -> OrbState.SPEAKING
                toolsRunning > 0 || awaitingReply -> OrbState.THINKING
                else -> OrbState.LISTENING
            }
            if (state != shown) { ui.state(state); shown = state }
            val limit = if (awaitingReply) REPLY_WAIT_MS else IDLE_END_MS
            if (toolsRunning == 0 && !speaker.busy && now - lastActivity > limit) {
                Log.i(TAG, "Live: quiet, ending the conversation")
                socket.close(1000, "quiet")
                return
            }
        }
    }

    /** Sends the microphone to ElevenLabs in 100 ms pieces. Echo cancelling keeps her own voice out. */
    @SuppressLint("MissingPermission")
    private suspend fun streamMic(socket: WebSocket, speaker: Speaker) {
        val minBuf = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, CHUNK_BYTES * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("The microphone is busy")
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching {
            AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching {
            NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        }.getOrNull() else null
        val buf = ByteArray(CHUNK_BYTES)
        // A safety gate in case echo cancelling misses some of her voice: while she talks, learn how loud her
        // echo is in her first half second, then only pass sound well above it (you cutting in), plus a second
        // after it so your whole sentence gets through. Everything else goes as silence.
        var talkingChunks = 0
        var echo = 0.0
        var openUntil = 0L
        rec.startRecording()
        try {
            while (currentCoroutineContext().isActive) {
                var n = 0
                while (n < buf.size) {
                    val r = rec.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                if (n <= 0) continue
                val rms = rmsOf(buf, n)
                val now = System.currentTimeMillis()
                val pass = if (!speaker.busy) {
                    talkingChunks = 0
                    echo = 0.0
                    true
                } else if (++talkingChunks <= 5) {
                    echo = max(echo, rms)
                    false
                } else {
                    val gate = max(GATE_MIN, echo * ECHO_MARGIN)
                    if (rms > gate) openUntil = now + 1000 else echo = echo * 0.98 + rms * 0.02
                    now < openUntil
                }
                if (pass) ui.level(shape(rms)) else buf.fill(0, 0, n)
                socket.send(JSONObject().put("user_audio_chunk", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)).toString())
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            aec?.release()
            ns?.release()
        }
    }

    private suspend fun signedUrl(): String = withContext(Dispatchers.IO) {
        val key = prefs.elevenKey
        if (key.isBlank()) throw ElevenLabsClient.ElevenException(401, "Add your ElevenLabs API key in the Miss Minutes app")
        val req = Request.Builder().url(SIGNED_URL + AGENT_ID).header("xi-api-key", key).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ElevenLabsClient.ElevenException(resp.code, "ElevenLabs ${resp.code}: ${body.take(200)}")
            JSONObject(body).getString("signed_url")
        }
    }

    /** RMS of little-endian 16-bit PCM. */
    private fun rmsOf(pcm: ByteArray, n: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return sqrt(sum / max(1, n / 2))
    }

    /** 0..1 loudness, shaped like the rest of the app's meters. */
    private fun shape(rms: Double) = (rms / 5000.0).coerceIn(0.0, 1.0).pow(0.7).toFloat()

    /** Plays her streamed voice, and knows roughly when she has finished saying what's queued. */
    private inner class Speaker {
        private var track: AudioTrack? = null
        private var rate = 16000
        private val queue = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
        @Volatile private var generation = 0
        @Volatile private var busyUntil = 0L

        // A little extra after the last sample for the room's echo to die away.
        val busy: Boolean get() = System.currentTimeMillis() < busyUntil + 300

        fun open(sampleRate: Int) {
            if (track != null) return
            rate = sampleRate
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // the call path, so echo cancelling works
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(max(minBuf, rate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { play() }
        }

        fun play(pcm: ByteArray) {
            if (track == null) open(rate)
            val now = System.currentTimeMillis()
            busyUntil = max(busyUntil, now) + pcm.size / 2 * 1000L / rate
            ui.level(shape(rmsOf(pcm, pcm.size)))
            queue.trySend(generation to pcm)
        }

        /** You talked over her: drop everything not yet played. */
        fun flush() {
            generation++
            busyUntil = 0
            track?.let { runCatching { it.pause(); it.flush(); it.play() } }
        }

        suspend fun writeLoop() {
            for ((gen, pcm) in queue) {
                if (gen != generation) continue
                track?.write(pcm, 0, pcm.size)
            }
        }

        fun release() {
            queue.close()
            track?.let { runCatching { it.stop() }; it.release() }
            track = null
        }
    }
}
