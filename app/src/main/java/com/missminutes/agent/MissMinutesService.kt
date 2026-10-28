package com.missminutes.agent

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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
        /** Say [EXTRA_TEXT] out loud (a reminder or a watched notification), then listen for a reply. */
        const val ACTION_ANNOUNCE = "announce"
        /** Handle [EXTRA_TEXT] as if the user had said it (used by the debug build's test hook). */
        const val ACTION_ASK = "ask"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "miss_minutes"
        private const val NOTIF_ID = 7
        private const val TAG = "MissMinutes"
        private const val FILLER_AFTER_MS = 1100L
        private const val MAX_ROUNDS = 6
        private val CONFIRM_PHRASES = listOf(
            "shall i", "should i", "can i", "do you want me to", "want me to", "is that ok", "is that okay",
            "send it?", "go ahead?", "confirm",
        )

        @Volatile var running = false

        fun intent(ctx: Context, action: String) =
            Intent(ctx, MissMinutesService::class.java).setAction(action)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayManager
    private lateinit var sarvam: SarvamClient
    private lateinit var player: VoicePlayer
    private lateinit var actions: PhoneActions
    private lateinit var brain: ClaudeBrain
    private val recorder = VoiceRecorder()

    private var wakeWord: WakeWord? = null
    private var wakeLoading: Job? = null
    private var session: Job? = null
    private var foreground = false
    private var destroyed = false
    private var focusRequest: AudioFocusRequest? = null
    /** Announcements that arrived while she was busy; spoken once the conversation ends. */
    private val queued = ArrayDeque<String>()
    /** An answer given by tapping Yes on the confirmation panel instead of saying it. */
    @Volatile private var pendingAnswer: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sarvam = SarvamClient(prefs)
        player = VoicePlayer(this, sarvam)
        actions = PhoneActions(this, prefs)
        brain = ClaudeBrain(prefs, actions)
        overlay = OverlayManager(this, prefs)

        overlay.onTap = {
            when (overlay.state) {
                OrbState.SPEAKING -> player.stop()      // skip the rest of what she's saying
                OrbState.LISTENING -> recorder.finish() // "I'm done talking"
                else -> cancelSession()
            }
        }
        overlay.onDismiss = { cancelSession() }
        overlay.onAnswer = { yes ->
            if (yes) {
                pendingAnswer = "yes"
                haptic()
                player.stop()
                recorder.cancel()
            } else {
                cancelSession()
            }
        }
        player.onLevel = { overlay.setLevel(it) }
        brain.listener = object : ClaudeBrain.Listener {
            override fun onToolStart(id: String, name: String, input: JSONObject) =
                overlay.setActivity(ToolLabels.label(name, input))
            override fun onToolEnd(id: String, ok: Boolean) {}
            override fun onModelSwitched(model: BrainModel) = overlay.setActivity("Using ${model.title}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TALK -> if (ensureForeground()) startSession()
            ACTION_ANNOUNCE -> intent.getStringExtra(EXTRA_TEXT)?.let { text ->
                if (ensureForeground()) {
                    if (session?.isActive == true) queued.addLast(text) else startSession(intro = text)
                }
            }
            ACTION_ASK -> intent.getStringExtra(EXTRA_TEXT)?.let { text ->
                if (ensureForeground()) startSession(typed = text)
            }
            ACTION_RELOAD -> {
                stopWakeWord()
                if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
                prefetchGreetings()
                if (foreground && session?.isActive != true) startWakeWord()
            }
            else -> if (ensureForeground()) { startWakeWord(); prefetchGreetings() }
        }
        return START_STICKY
    }

    // ---------- Foreground notification ----------

    /** The quiet, always-there notification: what to say, plus Talk and Stop. */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getService(this, 1, intent(this, ACTION_TALK), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_minutes)
            .setColor(Palette.AMBER)
            .setContentTitle("Say “${WakeWord.display(prefs.wakePhrase)}”")
            .setContentText("Miss Minutes is listening for you. Tap Talk to speak now.")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), "Talk", talk).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_power), "Stop", stop).build())
            .build()
    }

    private fun ensureForeground(): Boolean {
        if (foreground) return true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Miss Minutes", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while she's listening for the wake word"
            setShowBadge(false)
        })
        val notification = buildNotification()
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
        wakeWord?.let { ww ->
            try {
                ww.start(prefs.wakePhrase, prefs.wakeSensitivity)
            } catch (e: Exception) {
                Log.e(TAG, "Wake word failed", e)
                toast("Wake word error: ${e.message}")
            }
            return
        }
        if (wakeLoading?.isActive == true) return
        // The model loads (and downloads, the first time) in the background, then listening starts.
        wakeLoading = scope.launch {
            try {
                if (!WakeWord.modelReady(this@MissMinutesService)) {
                    toast("Downloading the wake word model (about 40 MB, one time only)…")
                }
                val loaded = withContext(Dispatchers.IO) { WakeWord.load(applicationContext) }
                loaded.onWake = { startSession() }
                wakeWord = loaded
                WakeWord.shared = loaded
                if (session?.isActive != true) startWakeWord()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Wake word failed", e)
                toast("Wake word error: ${e.message}")
            }
        }
    }

    private fun stopWakeWord() {
        wakeWord?.stop()
    }

    // ---------- One conversation ----------

    /** [intro]: something she says first (a reminder or alert). [typed]: a request given as text instead of voice. */
    private fun startSession(intro: String? = null, typed: String? = null) {
        if (session?.isActive == true) return
        session = scope.launch { runSession(intro, typed) }
    }

    private fun cancelSession() {
        recorder.cancel()
        player.stop()
        session?.cancel()
    }

    private suspend fun runSession(intro: String? = null, typed: String? = null) {
        stopWakeWord()
        pendingAnswer = typed
        overlay.show()
        overlay.setState(if (intro != null) OrbState.SPEAKING else OrbState.LISTENING)
        haptic()
        requestAudioFocus()
        delay(180)
        try {
            var rounds = 0
            var waitMs = 6000L
            var alreadySaid: ByteArray? = null
            // Claude didn't hear the announcement, so the first reply to it carries it along as context.
            var context = intro
            if (intro != null) {
                alreadySaid = speak(intro) { overlay.setState(OrbState.SPEAKING) }
                waitMs = 5000L
            } else if (typed == null && prefs.greetOnWake) {
                // "சொல்லுங்க Arun!" — from a cached recording, so it's instant. Talking over it is fine.
                alreadySaid = speak(Greetings.next(prefs), cache = true) { overlay.setState(OrbState.SPEAKING) }
            }
            while (true) {
                overlay.setState(OrbState.LISTENING)
                var heard = takeAnswer()
                if (heard == null) {
                    val wav = withContext(Dispatchers.IO) {
                        recorder.record(waitMs, alreadySaid) { overlay.setLevel(it) }
                    }
                    alreadySaid = null
                    heard = takeAnswer() ?: wav?.let {
                        overlay.setState(OrbState.THINKING)
                        sarvam.transcribe(it)
                    }
                }
                if (heard.isNullOrBlank()) break

                overlay.hidePanel()
                overlay.setState(OrbState.THINKING)
                actions.quietAfterAction = false
                val turn = replyAndSpeak(heard, context)
                context = null
                if (actions.quietAfterAction) {
                    overlay.setState(OrbState.SPEAKING)
                    delay(900)
                    break
                }
                val reply = turn.reply
                val cutIn = turn.cutIn
                val asked = reply.trimEnd().endsWith("?")

                rounds++
                if (rounds >= MAX_ROUNDS) break
                if (cutIn != null || pendingAnswer != null) { // you talked over her, or tapped Yes
                    alreadySaid = cutIn
                    waitMs = 6000L
                    continue
                }
                if (asked || prefs.keepListening) {
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
            overlay.showPanel(message, OverlayManager.PanelKind.PROBLEM)
            runCatching { player.speak(message) }
            delay(2500)
        } finally {
            withContext(NonCancellable) {
                overlay.hide()
                player.stop()
                abandonAudioFocus()
                startWakeWord()
            }
            if (queued.isNotEmpty()) scope.launch {
                delay(400)
                queued.removeFirstOrNull()?.let { startSession(intro = it) }
            }
        }
    }

    /**
     * Downloads every greeting and filler for the current name and voice, so they never wait on the network.
     * (The cache keeps only what's listed here, so both lists go together.)
     */
    private fun prefetchGreetings() {
        if (prefs.sarvamKey.isBlank()) return
        val lines = (if (prefs.greetOnWake) Greetings.all(prefs) else emptyList()) + Fillers.all()
        scope.launch { player.prefetch(lines) }
    }

    private class Turn(val reply: String, val cutIn: ByteArray?)

    /**
     * Asks Claude and speaks each sentence the moment it's written, instead of waiting for the whole answer.
     * If Claude is slow to start, a short cached filler ("ஒரு நிமிஷம்…") covers the silence.
     * If you talk over her, the rest of the answer is dropped (actions still finish).
     */
    private suspend fun replyAndSpeak(heard: String, context: String?): Turn = coroutineScope {
        val lines = Channel<Pair<String, Boolean>>(Channel.UNLIMITED) // text, from cache
        val order = Any()
        var started = false
        var cutIn: ByteArray? = null

        val speaker = launch {
            for ((line, cached) in lines) {
                if (cutIn != null || actions.quietAfterAction) continue
                val asked = line.trimEnd().endsWith("?")
                val heardOver = speak(line, cache = cached) {
                    overlay.setState(OrbState.SPEAKING)
                    if (asked) {
                        val kind = if (isConfirmation(line)) OverlayManager.PanelKind.CONFIRM
                                   else OverlayManager.PanelKind.QUESTION
                        overlay.showPanel(line, kind)
                    }
                }
                if (heardOver != null) cutIn = heardOver
            }
        }
        val filler = launch {
            delay(FILLER_AFTER_MS)
            val line = Fillers.pick(heard)
            if (!player.isCached(line)) return@launch
            synchronized(order) { if (!started) { started = true; lines.trySend(line to true) } }
        }

        val reply = try {
            brain.handle(heard, context) { sentence ->
                synchronized(order) { started = true; lines.trySend(sentence to false) }
            }
        } finally {
            filler.cancel()
            lines.close()
        }
        speaker.join()
        if (actions.quietAfterAction) player.stop()
        Turn(reply, cutIn)
    }

    private fun takeAnswer(): String? = pendingAnswer?.also { pendingAnswer = null }

    /**
     * Speaks [text]. With barge-in on, the mic listens while she talks: if you start speaking she stops
     * straight away, and the audio of your first words is returned so the next recording can carry on from it.
     */
    private suspend fun speak(text: String, cache: Boolean = false, onStart: () -> Unit): ByteArray? = coroutineScope {
        val bargeIn = if (prefs.bargeIn) BargeIn() else null
        var listening: Deferred<ByteArray?>? = null
        try {
            player.speak(text, cache = cache) {
                onStart()
                if (bargeIn != null) listening = this@coroutineScope.async(Dispatchers.IO) {
                    bargeIn.listen { player.stop(); haptic() }
                }
            }
        } finally {
            bargeIn?.cancel()
        }
        listening?.await()
    }

    /** Her question asks you to approve something ("…அனுப்பட்டுமா?", "Shall I call Amma?"). */
    private fun isConfirmation(reply: String): Boolean {
        val q = reply.trim()
        if (Regex("""(ட்டுமா|டுமா|லாமா)\s*\?$""").containsMatchIn(q)) return true
        val lower = q.lowercase()
        return CONFIRM_PHRASES.any { it in lower }
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
        wakeLoading?.cancel()
        wakeWord?.release()
        wakeWord = null
        overlay.hide()
        player.shutdown()
        abandonAudioFocus()
        scope.cancel()
        super.onDestroy()
    }
}
