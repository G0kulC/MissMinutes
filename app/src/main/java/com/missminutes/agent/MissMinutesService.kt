package com.missminutes.agent

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Always-on service. Waits for the wake word, then runs a conversation:
 * listen -> Sarvam speech-to-text -> Claude with phone tools -> Sarvam voice, with the floating card on screen.
 */
class MissMinutesService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_TALK = "talk"
        const val ACTION_RELOAD = "reload"
        const val KEYWORD_FILE = "hey_miss_minutes.ppn"
        private const val CHANNEL = "miss_minutes"
        private const val NOTIF_ID = 7
        private const val TAG = "MissMinutes"
        private const val MAX_ROUNDS = 6

        @Volatile var running = false

        fun intent(ctx: Context, action: String) =
            Intent(ctx, MissMinutesService::class.java).setAction(action)

        fun hasCustomWakeWord(ctx: Context) = ctx.assets.list("")?.contains(KEYWORD_FILE) == true

        fun wakeWordName(ctx: Context) = if (hasCustomWakeWord(ctx)) "Hey Miss Minutes" else "Jarvis"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayManager
    private lateinit var sarvam: SarvamClient
    private lateinit var player: VoicePlayer
    private lateinit var actions: PhoneActions
    private lateinit var brain: ClaudeBrain
    private val recorder = VoiceRecorder()

    private var porcupine: PorcupineManager? = null
    private var session: Job? = null
    private var foreground = false
    private var destroyed = false
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sarvam = SarvamClient(prefs)
        player = VoicePlayer(this, sarvam)
        actions = PhoneActions(this, prefs)
        brain = ClaudeBrain(prefs, actions)
        overlay = OverlayManager(this, prefs)

        overlay.onTap = { if (overlay.state == OrbState.SPEAKING) player.stop() else cancelSession() }
        overlay.onDismiss = { cancelSession() }
        player.onLevel = { overlay.setLevel(it) }
        brain.listener = object : ClaudeBrain.Listener {
            override fun onToolStart(id: String, name: String, input: JSONObject) =
                overlay.addChip(id, ToolLabels.label(name, input))
            override fun onToolEnd(id: String, ok: Boolean) = overlay.finishChip(id, ok)
            override fun onModelSwitched(model: BrainModel) {
                overlay.addChip("model-${model.id}", "Using ${model.title}")
                overlay.finishChip("model-${model.id}", true)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TALK -> if (ensureForeground()) startSession()
            ACTION_RELOAD -> {
                releaseWakeWord()
                if (foreground && session?.isActive != true) startWakeWord()
            }
            else -> if (ensureForeground()) startWakeWord()
        }
        return START_STICKY
    }

    // ---------- Foreground notification ----------

    private fun ensureForeground(): Boolean {
        if (foreground) return true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Miss Minutes", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getService(this, 1, intent(this, ACTION_TALK), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Miss Minutes is ready")
            .setContentText("Say \"${wakeWordName(this)}\"")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            foreground = true
            running = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start in the foreground", e)
            stopSelf()
            false
        }
    }

    // ---------- Wake word ----------

    private fun startWakeWord() {
        if (destroyed) return
        if (prefs.picovoiceKey.isBlank()) {
            toast("Add your Picovoice key in the Miss Minutes app")
            return
        }
        try {
            if (porcupine == null) {
                val builder = PorcupineManager.Builder()
                    .setAccessKey(prefs.picovoiceKey)
                    .setSensitivity(prefs.wakeSensitivity)
                val custom = copyKeywordFile()
                if (custom != null) builder.setKeywordPath(custom)
                else builder.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                porcupine = builder.build(applicationContext, PorcupineManagerCallback {
                    scope.launch { startSession() }
                })
            }
            porcupine?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Wake word failed", e)
            toast("Wake word error: ${e.message}")
        }
    }

    private fun stopWakeWord() {
        runCatching { porcupine?.stop() }
    }

    private fun releaseWakeWord() {
        runCatching { porcupine?.stop() }
        runCatching { porcupine?.delete() }
        porcupine = null
    }

    private fun copyKeywordFile(): String? {
        if (!hasCustomWakeWord(this)) return null
        val out = File(filesDir, KEYWORD_FILE)
        assets.open(KEYWORD_FILE).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out.absolutePath
    }

    // ---------- One conversation ----------

    private fun startSession() {
        if (session?.isActive == true) return
        session = scope.launch { runSession() }
    }

    private fun cancelSession() {
        recorder.cancel()
        player.stop()
        session?.cancel()
    }

    private suspend fun runSession() {
        stopWakeWord()
        overlay.show()
        overlay.setState(OrbState.LISTENING)
        haptic()
        requestAudioFocus()
        delay(180)
        try {
            var rounds = 0
            var waitMs = 6000L
            while (true) {
                overlay.setState(OrbState.LISTENING)
                overlay.showHint(if (rounds == 0) "Listening…" else "Go ahead…")
                val wav = withContext(Dispatchers.IO) {
                    recorder.record(waitMs) { overlay.setLevel(it) }
                } ?: break

                overlay.setState(OrbState.THINKING)
                overlay.showHint("…")
                val heard = sarvam.transcribe(wav)
                if (heard.isBlank()) break
                overlay.showHeard(heard)

                actions.quietAfterAction = false
                val reply = brain.handle(heard)
                if (actions.quietAfterAction) {
                    overlay.setState(OrbState.SPEAKING)
                    overlay.showReply(reply)
                    delay(1400)
                    break
                }
                player.speak(reply) {
                    overlay.setState(OrbState.SPEAKING)
                    overlay.showReply(reply)
                }

                rounds++
                val asked = reply.trimEnd().endsWith("?")
                if ((asked || prefs.keepListening) && rounds < MAX_ROUNDS) {
                    waitMs = if (asked) 6000L else 4000L
                    continue
                }
                delay(500)
                break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Conversation failed", e)
            val message = friendlyError(e)
            overlay.setState(OrbState.SPEAKING)
            overlay.showReply(message)
            runCatching { player.speak(message) }
            delay(1200)
        } finally {
            withContext(NonCancellable) {
                overlay.hide()
                player.stop()
                abandonAudioFocus()
                startWakeWord()
            }
        }
    }

    private fun friendlyError(e: Exception): String = when {
        e is ClaudeBrain.ClaudeException && e.code == 401 -> "Your Claude API key isn't working. Check it in the Miss Minutes app."
        e is ClaudeBrain.ClaudeException && e.code == 403 -> "Your Claude account can't use this model. Pick another one in the app."
        e is ClaudeBrain.ClaudeException && e.code == 429 -> "Too many requests to Claude. Try again in a minute."
        e is ClaudeBrain.ClaudeException && e.code == 529 -> "Claude is busy right now. Try again shortly."
        e is SarvamClient.SarvamException && e.code in 401..403 -> "Your Sarvam API key isn't working. Check it in the app."
        e is UnknownHostException || e is SocketTimeoutException -> "I can't reach the internet right now."
        else -> "Something went wrong: ${e.message?.take(80) ?: "unknown error"}"
    }

    // ---------- Sound and touch feedback ----------

    private fun requestAudioFocus() {
        val am = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        am.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun haptic() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        destroyed = true
        running = false
        recorder.cancel()
        session?.cancel()
        releaseWakeWord()
        overlay.hide()
        player.shutdown()
        abandonAudioFocus()
        scope.cancel()
        super.onDestroy()
    }
}
