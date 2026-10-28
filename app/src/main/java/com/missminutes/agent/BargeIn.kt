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
        private const val LEARN_CHUNKS = 10           // first 500 ms: measure her echo
        private const val SPEECH_CHUNKS = 6           // 300 ms of loud sound counts as you talking
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
    fun listen(onSpeech: () -> Unit): ByteArray? {
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
        var echo = 0.0
        var learned = 0
        var threshold = MIN_THRESHOLD
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

                if (learned < LEARN_CHUNKS) {
                    echo = max(echo, rms)
                    if (++learned == LEARN_CHUNKS) threshold = max(MIN_THRESHOLD, echo * 2.2)
                    continue
                }
                if (rms > threshold) {
                    if (++loud >= SPEECH_CHUNKS) {
                        onSpeech()
                        val out = ByteArrayOutputStream()
                        recent.forEach { out.write(it) }
                        return out.toByteArray()
                    }
                } else {
                    loud = 0
                    // Her echo drifts as she speaks louder or softer; follow it slowly.
                    threshold = max(MIN_THRESHOLD, threshold * 0.98 + rms * 2.2 * 0.02)
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
