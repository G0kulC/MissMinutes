package com.missminutes.agent

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
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
        private const val END_SILENCE_MS = 850L
        private const val MIN_SPEECH_CHUNKS = 5    // 250 ms of voice; a click or a cough isn't a request
        private const val MAX_MS = 15000L
        private const val NOISE_MARGIN = 2.5      // your voice must be this many times louder than the room

        private fun thresholdFor(noise: Double) = (noise * NOISE_MARGIN).coerceIn(800.0, 3500.0)
    }

    @Volatile private var cancelled = false
    @Volatile private var finishing = false
    fun cancel() { cancelled = true }
    /** Stop now and keep what was said (a tap on the orb meaning "I'm done talking"). */
    fun finish() { finishing = true }

    /**
     * Blocking. Returns WAV bytes, or null if nothing was said within [waitForSpeechMs].
     * [alreadySaid] is PCM you started saying before recording began (when you cut in while she was talking).
     */
    @SuppressLint("MissingPermission")
    fun record(
        waitForSpeechMs: Long = 6000L,
        alreadySaid: ByteArray? = null,
        onLevel: (Float) -> Unit = {},
    ): ByteArray? {
        cancelled = false
        finishing = false
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
        // The phone's own noise filter takes the edge off fans, traffic and TV before we measure anything.
        val ns = if (NoiseSuppressor.isAvailable()) runCatching {
            NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
        }.getOrNull() else null

        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(CHUNK)
        val bytes = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN)
        var noise = 0.0
        var noiseChunks = 0
        var threshold = 900.0
        var speechStarted = false
        var speechChunks = 0
        var peak = 0.0
        val start = System.currentTimeMillis()
        var lastSpeech = start
        if (alreadySaid != null) {
            // You're mid-sentence: skip measuring room noise, which would mistake your voice for it.
            pcm.write(alreadySaid)
            speechStarted = true
            speechChunks = MIN_SPEECH_CHUNKS
            noiseChunks = CALIBRATION_CHUNKS
            noise = 300.0
        }

        recorder.startRecording()
        try {
            while (!cancelled && !finishing) {
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
                        noise /= CALIBRATION_CHUNKS
                        threshold = thresholdFor(noise)
                    }
                    continue
                }
                if (rms > threshold) {
                    speechStarted = true; speechChunks++; lastSpeech = now
                } else if (!speechStarted && rms < noise * 1.5) {
                    // Follow the room (a fan speeding up, traffic dying down), but only sounds close to its level:
                    // your quieter first words must never count as noise, or the bar climbs past your voice.
                    noise = noise * (if (rms < noise) 0.8 else 0.97) + rms * (if (rms < noise) 0.2 else 0.03)
                    threshold = thresholdFor(noise)
                }
                if (rms > peak) peak = rms

                val elapsed = now - start
                if (!speechStarted && elapsed > waitForSpeechMs) break
                if (speechStarted && now - lastSpeech > END_SILENCE_MS) {
                    if (speechChunks >= MIN_SPEECH_CHUNKS) break
                    // Only a short bump of noise: keep waiting for real words.
                    speechStarted = false
                    speechChunks = 0
                }
                if (elapsed > MAX_MS) break
            }
        } finally {
            onLevel(0f)
            runCatching { recorder.stop() }
            recorder.release()
            ns?.release()
            Log.i("MissMinutes", "Recording: noise=${noise.toInt()} threshold=${threshold.toInt()} " +
                "peak=${peak.toInt()} speech=${speechChunks * 50}ms heard=$speechStarted")
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
