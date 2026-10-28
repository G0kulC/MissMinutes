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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Speaks with the Sarvam voice and reports loudness while playing, so the dial moves in sync.
 * Falls back to Android's built-in voice if Sarvam fails.
 */
class VoicePlayer(context: Context, private val sarvam: SarvamClient) {
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
     */
    suspend fun speak(text: String, fallback: Boolean = true, onStart: () -> Unit = {}) {
        if (text.isBlank()) return
        stopRequested = false
        var started = false
        try {
            streamSarvam(text) { started = true; onStart() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (stopRequested) return
            if (started) { Log.w("MissMinutes", "Sarvam voice stream broke off", e); return }
            if (!fallback) throw e
            Log.w("MissMinutes", "Sarvam voice failed, using Android voice", e)
            onStart()
            speakWithAndroid(text)
        }
    }

    private suspend fun streamSarvam(text: String, onFirstAudio: () -> Unit) = coroutineScope {
        val rate = SarvamClient.STREAM_RATE
        val call = sarvam.speechCall(text)
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
                call.execute().use { resp ->
                    val input = sarvam.checked(resp).body!!.byteStream()
                    val buf = ByteArray(4096)
                    var carry = -1 // a stray odd byte waiting for its partner
                    while (isActive && !call.isCanceled()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
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
                }
                // Let the last buffered audio play out.
                val total = levels.frames
                while (isActive && !call.isCanceled() && meter != null && track.playbackHeadPosition < total) delay(30)
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
