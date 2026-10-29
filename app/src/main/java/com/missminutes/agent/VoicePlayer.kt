package com.missminutes.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A service that streams speech as 16-bit mono PCM at [SarvamClient.STREAM_RATE] Hz. */
interface TtsEngine {
    /** Names the voice, so cached clips are kept per voice. */
    val speaker: String
    fun speechCall(text: String): okhttp3.Call
    fun checked(resp: okhttp3.Response): okhttp3.Response
    /** Another service to try if this one fails before any sound. */
    val backup: TtsEngine? get() = null
}

/**
 * Speaks with the chosen voice (Sarvam or ElevenLabs) and reports loudness while playing, so the dial moves in sync.
 * Falls back to Sarvam, then to Android's built-in voice, if that fails.
 */
class VoicePlayer(context: Context, private val voice: TtsEngine) {
    companion object {
        /** 6 seconds of 16-bit mono audio. */
        private const val MAX_CACHED_BYTES = SarvamClient.STREAM_RATE * 2L * 6
    }

    private val appContext = context.applicationContext
    var onLevel: ((Float) -> Unit)? = null

    private var ttsReady = false
    private val tts = TextToSpeech(appContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    @Volatile private var currentCall: Call? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopRequested = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Streams the Sarvam voice: playback starts as soon as the first audio arrives instead of after the
     * whole reply is generated. [onStart] runs the moment sound begins.
     * With [fallback] false, Sarvam errors are thrown instead of using Android's voice.
     * With [cache] true the audio is kept on the phone, so a phrase said often (a greeting) plays instantly.
     */
    suspend fun speak(text: String, fallback: Boolean = true, cache: Boolean = false, onStart: () -> Unit = {}) {
        if (text.isBlank()) return
        stopRequested = false
        var started = false
        try {
            stream(text, cache, voice) { started = true; onStart() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (stopRequested) return
            if (started) { Log.w("MissMinutes", "Voice stream broke off", e); return }
            if (!fallback) throw e
            voice.backup?.let { backup ->
                Log.w("MissMinutes", "Voice failed, using Sarvam instead", e)
                try {
                    stream(text, false, backup) { started = true; onStart() }
                    return
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    if (stopRequested || started) return
                }
            }
            Log.w("MissMinutes", "Voice failed, using Android voice", e)
            onStart()
            speakWithAndroid(text)
        }
    }

    /**
     * Downloads and keeps the audio for [texts] without playing it, so they're instant later.
     * Recordings for anything else (an old name, voice or greeting) are deleted.
     */
    suspend fun prefetch(texts: List<String>) = withContext(Dispatchers.IO) {
        val wanted = texts.map { cacheFile(it) }.toSet()
        cacheFile("").parentFile?.listFiles()?.filter { it !in wanted }?.forEach { it.delete() }
        for (text in texts) {
            val file = cacheFile(text)
            if (file.exists()) continue
            runCatching {
                voice.speechCall(text).execute().use { resp ->
                    val tmp = File(file.path + ".tmp")
                    voice.checked(resp).body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    keepIfSane(tmp, file)
                }
            }.onFailure { Log.w("MissMinutes", "Couldn't prefetch voice", it) }
        }
    }

    /**
     * Cached lines are short greetings. The voice occasionally glitches and returns many seconds of noise
     * for a one-second line (seen: 22 s for "ம்ம், சொல்லுங்க"), so anything that long is thrown away.
     */
    private fun keepIfSane(tmp: File, file: File) {
        if (tmp.length() in 1..MAX_CACHED_BYTES) tmp.renameTo(file)
        else { Log.w("MissMinutes", "Discarded a ${tmp.length()}-byte voice clip"); tmp.delete() }
    }

    /** True if [text] is already on the phone in the current voice, so it plays instantly. */
    fun isCached(text: String) = cacheFile(text).exists()

    /** One file per phrase and voice: changing her voice in the app means fresh audio. */
    private fun cacheFile(text: String): File {
        val dir = File(appContext.cacheDir, "voice").apply { mkdirs() }
        val key = "${voice.speaker}|$text".hashCode().toUInt().toString(16)
        return File(dir, "$key.pcm")
    }

    private suspend fun stream(text: String, cache: Boolean, engine: TtsEngine, onFirstAudio: () -> Unit) = coroutineScope {
        val rate = SarvamClient.STREAM_RATE
        val cached = if (cache) cacheFile(text).takeIf { it.exists() } else null
        val call = if (cached == null) engine.speechCall(text) else null
        currentCall = call
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, rate / 5 * 2)) // ~200 ms keeps the dial in sync
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        this@VoicePlayer.track = track
        val levels = LevelTrack(rate)
        var meter: Job? = null
        try {
            withContext(Dispatchers.IO) {
                // Play from the phone if we have it; otherwise stream from Sarvam (and keep a copy if asked).
                val resp = call?.execute()
                val tee = if (cache && cached == null) File(cacheFile(text).path + ".tmp").outputStream() else null
                var complete = false
                try {
                    val input = cached?.inputStream() ?: engine.checked(resp!!).body!!.byteStream()
                    val buf = ByteArray(4096)
                    var carry = -1 // a stray odd byte waiting for its partner
                    while (isActive && !stopRequested && call?.isCanceled() != true) {
                        val n = input.read(buf)
                        if (n < 0) { complete = true; break }
                        if (n == 0) continue
                        tee?.write(buf, 0, n)
                        var bytes = if (carry >= 0) byteArrayOf(carry.toByte()) + buf.copyOf(n) else buf.copyOf(n)
                        carry = -1
                        if (bytes.size % 2 == 1) { carry = bytes.last().toInt() and 0xFF; bytes = bytes.copyOf(bytes.size - 1) }
                        if (bytes.isEmpty()) continue
                        levels.add(bytes)
                        if (meter == null) {
                            track.play()
                            withContext(Dispatchers.Main) { onFirstAudio() }
                            meter = this@coroutineScope.launch {
                                while (isActive) {
                                    onLevel?.invoke(levels.at(track.playbackHeadPosition))
                                    delay(33)
                                }
                            }
                        }
                        track.write(bytes, 0, bytes.size)
                    }
                    input.close()
                } finally {
                    resp?.close()
                    tee?.close()
                    val tmp = File(cacheFile(text).path + ".tmp")
                    if (tee != null) { if (complete) keepIfSane(tmp, cacheFile(text)) else tmp.delete() }
                }
                // Let the last buffered audio play out.
                val total = levels.frames
                while (isActive && !stopRequested && call?.isCanceled() != true && meter != null &&
                    track.playbackHeadPosition < total) delay(30)
            }
        } finally {
            meter?.cancel()
            onLevel?.invoke(0f)
            runCatching { track.stop() }
            track.release()
            if (this@VoicePlayer.track === track) this@VoicePlayer.track = null
            if (currentCall === call) currentCall = null
        }
    }

    private suspend fun speakWithAndroid(text: String) = coroutineScope {
        if (!ttsReady) return@coroutineScope
        tts.language = if (text.any { it in '\u0B80'..'\u0BFF' }) Locale("ta", "IN") else Locale("en", "IN")
        val start = System.currentTimeMillis()
        val meter = launch {
            while (isActive) {
                val t = (System.currentTimeMillis() - start) / 1000f
                onLevel?.invoke(0.35f + 0.25f * sin(t * 9f) + 0.1f * sin(t * 23f))
                delay(33)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                })
                cont.invokeOnCancellation { tts.stop() }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            }
        } finally {
            meter.cancel()
            onLevel?.invoke(0f)
        }
    }

    fun stop() {
        stopRequested = true
        currentCall?.cancel()
        runCatching { track?.pause(); track?.flush() }
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}

/** Loudness (0..1) of streamed 16-bit mono PCM in short windows, looked up by playback position. */
private class LevelTrack(rate: Int, windowMs: Int = 30) {
    private val perWindow = (rate * windowMs / 1000).coerceAtLeast(1)
    private val levels = ArrayList<Float>()
    private var sum = 0.0
    private var n = 0
    private var peak = 3000f
    @Volatile var frames = 0
        private set

    @Synchronized fun add(pcm: ByteArray) {
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until pcm.size / 2) {
            val s = bb.getShort(i * 2).toDouble()
            sum += s * s
            if (++n == perWindow) {
                val rms = sqrt(sum / n).toFloat()
                if (rms > peak) peak = rms
                levels.add(rms)
                sum = 0.0
                n = 0
            }
        }
        frames += pcm.size / 2
    }

    @Synchronized fun at(frame: Int): Float {
        if (levels.isEmpty()) return 0f
        return (levels[(frame / perWindow).coerceIn(0, levels.size - 1)] / peak).coerceIn(0f, 1f).pow(0.8f)
    }
}
