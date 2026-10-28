package com.missminutes.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
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
    private var player: MediaPlayer? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * [onStart] runs the moment sound begins (after the voice has been generated).
     * With [fallback] false, Sarvam errors are thrown instead of using Android's voice.
     */
    suspend fun speak(text: String, fallback: Boolean = true, onStart: () -> Unit = {}) {
        if (text.isBlank()) return
        val wav = try {
            sarvam.synthesize(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!fallback) throw e
            Log.w("MissMinutes", "Sarvam voice failed, using Android voice", e)
            null
        }
        onStart()
        if (wav != null) playWav(wav) else speakWithAndroid(text)
    }

    private suspend fun playWav(wav: ByteArray) = coroutineScope {
        val envelope = Envelope.fromWav(wav)
        val file = File(appContext.cacheDir, "reply.wav")
        withContext(Dispatchers.IO) { file.writeBytes(wav) }
        val mp = MediaPlayer()
        player = mp
        val meter = launch {
            while (isActive) {
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                onLevel?.invoke(envelope.at(pos))
                delay(33)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                mp.setAudioAttributes(attributes)
                mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                cont.invokeOnCancellation { runCatching { mp.stop() } }
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.start()
            }
        } finally {
            meter.cancel()
            onLevel?.invoke(0f)
            runCatching { mp.release() }
            player = null
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
        runCatching { player?.stop() }
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}

/** Loudness of a WAV file over time (0..1), sampled in short windows. */
class Envelope private constructor(private val levels: FloatArray, private val windowMs: Int) {

    fun at(positionMs: Int): Float =
        if (levels.isEmpty()) 0f else levels[(positionMs / windowMs).coerceIn(0, levels.size - 1)]

    companion object {
        fun fromWav(wav: ByteArray, windowMs: Int = 30): Envelope {
            val empty = Envelope(FloatArray(0), windowMs)
            if (wav.size < 44) return empty
            val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            var rate = 22050
            var channels = 1
            var bits = 16
            var dataStart = -1
            var dataLen = 0
            var p = 12
            while (p + 8 <= wav.size) {
                val id = String(wav, p, 4, Charsets.US_ASCII)
                val len = bb.getInt(p + 4)
                if (id == "fmt " && p + 24 <= wav.size) {
                    channels = bb.getShort(p + 10).toInt().coerceAtLeast(1)
                    rate = bb.getInt(p + 12)
                    bits = bb.getShort(p + 22).toInt()
                } else if (id == "data") {
                    dataStart = p + 8
                    val rest = wav.size - dataStart
                    dataLen = if (len <= 0 || len > rest) rest else len
                    break
                }
                if (len < 0) break
                p += 8 + len + (len and 1)
            }
            if (dataStart < 0 || bits != 16 || rate <= 0) return empty

            val frameBytes = 2 * channels
            val perWindow = (rate * windowMs / 1000).coerceAtLeast(1)
            val frames = dataLen / frameBytes
            val count = (frames + perWindow - 1) / perWindow
            val out = FloatArray(count)
            var peak = 1f
            for (w in 0 until count) {
                var sum = 0.0
                var n = 0
                var f = w * perWindow
                val end = minOf(frames, f + perWindow)
                while (f < end) {
                    val s = bb.getShort(dataStart + f * frameBytes).toDouble()
                    sum += s * s
                    n++
                    f++
                }
                val rms = if (n > 0) sqrt(sum / n).toFloat() else 0f
                out[w] = rms
                if (rms > peak) peak = rms
            }
            for (i in out.indices) out[i] = (out[i] / peak).pow(0.8f)
            return Envelope(out, windowMs)
        }
    }
}
