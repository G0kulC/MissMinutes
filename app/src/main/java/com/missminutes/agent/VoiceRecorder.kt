package com.missminutes.agent

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Records your voice, reports live loudness for the animation, and stops when you stop talking. */
class VoiceRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK = 800              // 50 ms
        private const val CALIBRATION_CHUNKS = 6   // first 300 ms measures room noise
        private const val END_SILENCE_MS = 1200L
        private const val MAX_MS = 15000L
    }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    /** Blocking. Returns WAV bytes, or null if nothing was said within [waitForSpeechMs]. */
    @SuppressLint("MissingPermission")
    fun record(waitForSpeechMs: Long = 6000L, onLevel: (Float) -> Unit = {}): ByteArray? {
        cancelled = false
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, CHUNK * 8)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("The microphone is busy")
        }

        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(CHUNK)
        val bytes = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN)
        var noise = 0.0
        var noiseChunks = 0
        var threshold = 700.0
        var speechStarted = false
        val start = System.currentTimeMillis()
        var lastSpeech = start

        recorder.startRecording()
        try {
            while (!cancelled) {
                val n = recorder.read(buf, 0, CHUNK)
                if (n <= 0) continue
                bytes.clear()
                for (i in 0 until n) bytes.putShort(buf[i])
                pcm.write(bytes.array(), 0, n * 2)

                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                onLevel((rms / 5000.0).coerceIn(0.0, 1.0).pow(0.7).toFloat())
                val now = System.currentTimeMillis()

                if (noiseChunks < CALIBRATION_CHUNKS) {
                    noise += rms
                    noiseChunks++
                    if (noiseChunks == CALIBRATION_CHUNKS) {
                        threshold = (noise / CALIBRATION_CHUNKS * 2.5).coerceIn(600.0, 2000.0)
                    }
                    continue
                }
                if (rms > threshold) { speechStarted = true; lastSpeech = now }

                val elapsed = now - start
                if (!speechStarted && elapsed > waitForSpeechMs) break
                if (speechStarted && now - lastSpeech > END_SILENCE_MS) break
                if (elapsed > MAX_MS) break
            }
        } finally {
            onLevel(0f)
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (!speechStarted || cancelled) return null
        return toWav(pcm.toByteArray())
    }

    private fun toWav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16)
        h.putShort(1.toShort()); h.putShort(1.toShort())
        h.putInt(SAMPLE_RATE); h.putInt(SAMPLE_RATE * 2)
        h.putShort(2.toShort()); h.putShort(16.toShort())
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }
}
