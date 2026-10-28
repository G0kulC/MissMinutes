package com.missminutes.agent

import android.content.Context
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Offline wake word with Vosk. The recognizer only knows a tiny grammar built from the user's wake phrase,
 * so it just listens for that. Changing the phrase needs no training: any words in the model's English
 * vocabulary work straight away. The small English model (~40 MB) is downloaded once on first start.
 */
class WakeWord private constructor(private val model: Model) {

    companion object {
        const val DEFAULT_PHRASE = "hey miss minutes"
        private const val TAG = "MissMinutes"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model"
        private const val DONE = ".ready"
        private const val RATE = 16000f
        private val GREETINGS = setOf("hey", "hi", "hello", "ok", "okay", "oh")

        /** The model the running service has loaded, shared so the app screen can check words without a second copy. */
        @Volatile var shared: WakeWord? = null

        fun modelReady(ctx: Context) = File(ctx.filesDir, "$MODEL_DIR/$DONE").exists()

        /** "  Hey,  JARVIS! " becomes "hey jarvis". Only English letters: the model is English. */
        fun normalize(phrase: String) =
            phrase.lowercase().replace(Regex("[^a-z' ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .joinToString(" ")

        /** "hey jarvis" becomes "Hey Jarvis", for showing on screen. */
        fun display(phrase: String) = normalize(phrase).split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

        /** The part that must be heard: the phrase without a leading "hey" or "ok". */
        fun core(phrase: String): String {
            val words = normalize(phrase).split(' ').filter { it.isNotEmpty() }
            val rest = words.dropWhile { it in GREETINGS }
            return (rest.ifEmpty { words }).joinToString(" ")
        }

        /** A reason the phrase won't work well, or null if it's fine. */
        fun shapeProblem(raw: String): String? {
            if (raw.any { it in '஀'..'௿' }) return "Use English letters, for example Hey Minnu. The wake word model only knows English spelling."
            val phrase = normalize(raw)
            if (phrase.isEmpty()) return "Type a wake phrase, for example Hey Jarvis."
            val words = phrase.split(' ')
            if (words.size > 5) return "Keep it to five words or fewer."
            if (core(phrase).replace(" ", "").length < 5) {
                return "That's too short; short words wake her by mistake. Try two words, like Hey Jarvis."
            }
            return null
        }

        /** Downloads the model if needed and loads it. Slow: call off the main thread. */
        fun load(ctx: Context): WakeWord {
            val dir = File(ctx.filesDir, MODEL_DIR)
            if (!modelReady(ctx)) download(dir)
            return WakeWord(Model(dir.absolutePath))
        }

        /**
         * Words in [phrase] the model can't recognise, or null if the model isn't downloaded yet so nothing
         * could be checked. Slow when the service isn't running: call off the main thread.
         */
        fun unknownWords(ctx: Context, phrase: String): List<String>? {
            shared?.let { return it.unknown(phrase) }
            if (!modelReady(ctx)) return null
            val temp = runCatching { load(ctx) }.getOrNull() ?: return null
            return try { temp.unknown(phrase) } finally { temp.release() }
        }

        private fun download(dir: File) {
            dir.deleteRecursively()
            dir.mkdirs()
            val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Wake word model download failed (${resp.code})")
                ZipInputStream(resp.body!!.byteStream()).use { zip ->
                    val root = dir.canonicalPath + File.separator
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // The zip has one top folder (vosk-model-small-en-us-0.15/); drop it.
                        val name = entry.name.substringAfter('/', "")
                        if (name.isEmpty()) continue
                        val out = File(dir, name)
                        if (!out.canonicalPath.startsWith(root)) continue
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            File(dir, DONE).createNewFile()
        }
    }

    /** vosk_model_find_word is in the native library but not in the Java wrapper, so bind it here. */
    private interface VoskExtras : Library {
        fun vosk_model_find_word(model: Pointer, word: String): Int
    }

    private val extras: VoskExtras? by lazy {
        runCatching { Native.load("vosk", VoskExtras::class.java) }.getOrNull()
    }

    var onWake: () -> Unit = {}

    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null

    fun unknown(phrase: String): List<String> {
        val lib = extras ?: return emptyList() // can't check on this build; let the grammar sort it out
        return normalize(phrase).split(' ').filter { word ->
            word.isNotEmpty() && runCatching { lib.vosk_model_find_word(model.pointer, word) < 0 }.getOrDefault(false)
        }
    }

    /**
     * Starts listening for [phrase]. With high sensitivity it wakes on partial words (faster, but more false
     * wakes); otherwise it waits for the short pause after the phrase.
     */
    fun start(phrase: String, sensitivity: Float) {
        if (service != null) return
        val full = normalize(phrase).ifEmpty { DEFAULT_PHRASE }
        val trigger = core(full)
        val grammar = JSONArray(listOf(full, trigger, "[unk]").distinct()).toString()
        val onPartial = sensitivity >= 0.75f
        val rec = Recognizer(model, RATE, grammar)
        val svc = SpeechService(rec, RATE)
        recognizer = rec
        service = svc
        svc.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                if (onPartial) check(hypothesis, "partial", trigger)
            }
            override fun onResult(hypothesis: String?) = check(hypothesis, "text", trigger)
            override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text", trigger)
            override fun onError(e: Exception?) { Log.e(TAG, "Wake word error", e) }
            override fun onTimeout() {}
        })
    }

    private fun check(hypothesis: String?, field: String, trigger: String) {
        if (service == null || hypothesis == null) return
        val heard = runCatching { JSONObject(hypothesis).optString(field) }.getOrDefault("")
        if (trigger in heard) onWake()
    }

    /** Stops listening and frees the microphone. */
    fun stop() {
        val svc = service ?: return
        service = null
        runCatching { svc.stop() }
        runCatching { svc.shutdown() }
        runCatching { recognizer?.close() }
        recognizer = null
    }

    fun release() {
        stop()
        if (shared === this) shared = null
        runCatching { model.close() }
    }
}
