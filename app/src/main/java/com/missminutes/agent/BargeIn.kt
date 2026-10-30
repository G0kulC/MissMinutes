package com.missminutes.agent

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens while she speaks so you can cut in. Uses the phone's echo-cancelled "voice call" microphone, then
 * learns how loud her own leftover echo is during her first half second and only counts sound well above that.
 */
class BargeIn {
    companion object {
        private const val RATE = VoiceRecorder.SAMPLE_RATE
        private const val CHUNK = 800                 // 50 ms
        private const val LEARN_CHUNKS = 10           // first 500 ms: learn how much of her voice the mic picks up
        private const val SPEECH_CHUNKS = 8           // 400 ms of loud sound counts as you talking
        /** How much louder than her expected echo you must be. */
        private const val ECHO_MARGIN = 2.5
        /** Her voice below this (raw RMS) is a pause: nothing to learn from. */
        private const val VOICE_FLOOR = 300.0
        private const val MAX_RATIO = 2.0
        private const val PREROLL_CHUNKS = 10         // keep the last 500 ms so your first words aren't lost
        private const val MIN_THRESHOLD = 1600.0
    }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    /**
     * Blocking. Calls [onSpeech] the moment you start talking and returns the 16-bit PCM of your first words,
     * or returns null once [cancel] is called (she finished, or the session ended).
     */
    @SuppressLint("MissingPermission")
    fun listen(voiceRms: () -> Float, onSpeech: () -> Unit): ByteArray? {
        cancelled = false
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, CHUNK * 8)
            )
        }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching {
            AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching {
            NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        }.getOrNull() else null

        val buf = ShortArray(CHUNK)
        val recent = ArrayDeque<ByteArray>()
        // Her echo rises and falls with how loud she is at each moment, so the bar does too: it's her current
        // loudness times how much of it reaches the mic. A bar learned once from a soft start ("Ooh…") let the
        // louder words after it ("yes please!") count as you talking.
        var ratio = 0.0
        val her = ArrayDeque<Double>() // her loudness over the last 200 ms: the echo arrives a little late
        var learned = 0
        var loud = 0
        rec.startRecording()
        try {
            while (!cancelled) {
                val n = rec.read(buf, 0, CHUNK)
                if (n <= 0) continue
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until n) { bytes.putShort(buf[i]); sum += buf[i].toDouble() * buf[i] }
                recent.addLast(bytes.array())
                if (recent.size > PREROLL_CHUNKS) recent.removeFirst()
                val rms = sqrt(sum / n)
                her.addLast(voiceRms().toDouble())
                if (her.size > 4) her.removeFirst()
                val voice = her.max()

                if (learned < LEARN_CHUNKS) {
                    if (voice > VOICE_FLOOR) ratio = max(ratio, (rms / voice).coerceAtMost(MAX_RATIO))
                    learned++
                    continue
                }
                val threshold = max(MIN_THRESHOLD, ratio * voice * ECHO_MARGIN)
                if (rms > threshold) {
                    if (++loud >= SPEECH_CHUNKS) {
                        onSpeech()
                        val out = ByteArrayOutputStream()
                        recent.forEach { out.write(it) }
                        return out.toByteArray()
                    }
                } else {
                    loud = 0
                    // Keep learning slowly, in case the room or the volume changes; it may rise only 2% a step, so
                    // your quieter first syllables can't push the bar up past your own voice.
                    if (voice > VOICE_FLOOR) {
                        ratio = max(ratio * 0.995, minOf(rms / voice, ratio * 1.02, MAX_RATIO))
                    }
                }
            }
            return null
        } finally {
            runCatching { rec.stop() }
            rec.release()
            aec?.release()
            ns?.release()
        }
    }
}
