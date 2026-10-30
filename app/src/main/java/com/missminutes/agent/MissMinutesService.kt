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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
        /** From a screen of the app: refresh the microphone permission and start the wake word afresh. */
        const val ACTION_REFRESH = "refresh"
        /** Say [EXTRA_TEXT] out loud (a reminder or a watched notification), then listen for a reply. */
        const val ACTION_ANNOUNCE = "announce"
        /** Handle [EXTRA_TEXT] as if the user had said it (used by the debug build's test hook). */
        const val ACTION_ASK = "ask"
        const val EXTRA_TEXT = "text"
        const val EXTRA_AGENT = "agent"
        private const val CHANNEL = "miss_minutes"
        private const val NOTIF_ID = 7
        private const val MIC_NOTIF_ID = 79
        private const val TAG = "MissMinutes"
        // Claude usually starts answering within 2 s; a filler before that just makes every reply say "Let me check".
        private const val FILLER_AFTER_MS = 2200L
        /** Pico Live's shorter pause before she decides you've finished (the classic way waits 850 ms). */
        private const val LIVE_END_SILENCE_MS = 600L
        private val GOODBYES = listOf(
            "bye", "bye bye", "good night", "goodnight", "that's all", "thats all", "that's it", "see you", "tata",
            "போதும்", "பை", "போயிட்டு வரேன்", "pothum", "podhum",
        )
        private const val MAX_ROUNDS = 6
        private val CONFIRM_PHRASES = listOf(
            "shall i", "should i", "can i", "do you want me to", "want me to", "is that ok", "is that okay",
            "send it?", "go ahead?", "confirm",
        )
        /** What speech-to-text makes of a cough or room noise; not a request. */
        private val NOISE_WORDS = setOf(
            "hmm", "hm", "mm", "mmm", "um", "umm", "uh", "ah", "oh", "ha", "huh",
            "ம்", "ம்ம்", "ம்ம்ம்", "ஹ்ம்", "ஆ", "ஓ", "ஹா",
        )

        @Volatile var running = false

        fun intent(ctx: Context, action: String) =
            Intent(ctx, MissMinutesService::class.java).setAction(action)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayManager
    private lateinit var sarvam: SarvamClient
    private lateinit var player: VoicePlayer
    private lateinit var actions: PhoneActions
    private lateinit var brain: ClaudeBrain
    private lateinit var earcons: Earcons
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
        PicoLog.init(this)
        prefs = Prefs(this)
        sarvam = SarvamClient(prefs)
        player = VoicePlayer(this, sarvam)
        actions = PhoneActions(this, prefs)
        brain = ClaudeBrain(prefs, actions)
        earcons = Earcons(this, prefs)
        overlay = OverlayManager(this, prefs)

        overlay.onTap = {
            when (overlay.state) {
                OrbState.SPEAKING -> player.stop()      // skip the rest of what she's saying
                OrbState.LISTENING -> recorder.finish() // "I'm done talking"
                else -> cancelSession()
            }
        }
        overlay.onDismiss = { cancelSession() }
        // Pico Camera: the button opens the window (it starts listening by itself); the ghost moves into its corner.
        overlay.onCamera = { PicoCamera.openWindow(this) }
        PicoCamera.onOpenChanged = { open -> mainHandler.post { overlay.docked = open } }
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
            private val names = HashMap<String, String>()
            override fun onToolStart(id: String, name: String, input: JSONObject) {
                names[id] = name
                overlay.setActivity(ToolLabels.label(name, input))
                overlay.toolStarted(name)
            }
            override fun onToolEnd(id: String, ok: Boolean) {
                val name = names.remove(id) ?: return
                overlay.toolEnded(name, ok)
                if (!ok) earcons.play(Earcons.Cue.ERROR)
                else if (name in OverlayManager.ACTION_TOOLS) earcons.play(Earcons.Cue.SUCCESS)
            }
            override fun onModelSwitched(model: BrainModel) = overlay.setActivity("Using ${model.title}")
            // Arrives while Claude's reply streams in (a background thread): her face follows the reply's feeling.
            override fun onMood(mood: String) {
                PicoLog.i("Mood: $mood")
                earcons.feel(mood)
                mainHandler.post { overlay.setEmotion(Mood.ofTag(mood)) }
            }
            // An agent took over (or handed back): its voice and name from the next thing said.
            override fun onAgentChanged(agent: Agent?) {
                PicoLog.i("Agent: ${agent?.name ?: "main"}")
                sarvam.speakerOverride = agent?.voice
                // Its own voice needs its own "one sec…" recordings, or it waits in silence while it thinks.
                if (agent?.voice != null && prefs.sarvamKey.isNotBlank()) scope.launch { player.prefetch(Fillers.all(), prune = false) }
                mainHandler.post {
                    overlay.speakerName = agent?.name ?: prefs.assistantName
                    overlay.ghostTheme = GhostTheme.of(agent?.colour ?: prefs.ghostTheme)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.wantRunning = false
                KeepAlive.cancel(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> if (ensureForeground()) {
                refreshMicrophone()
                if (session?.isActive != true) { stopWakeWord(); startWakeWord() }
            }
            ACTION_TALK -> if (ensureForeground()) {
                refreshMicrophone()
                // "Talk" on an agent in the app starts the conversation with that agent.
                Agents.byId(prefs, intent.getStringExtra(EXTRA_AGENT))?.let { if (session?.isActive != true) brain.agent = it }
                startSession()
            }
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
            else -> if (ensureForeground()) {
                // Start (or a restart): remember it should stay on, and check back now and then.
                prefs.wantRunning = true
                KeepAlive.schedule(this)
                startWakeWord()
                prefetchGreetings()
            }
        }
        return START_STICKY
    }

    // ---------- Foreground notification ----------

    /** The quiet, always-there notification: what to say, plus Talk and Stop. */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // Through an invisible screen, not straight to the service: from the background Android may give the
        // service a silent microphone, which is why Talk sometimes heard nothing until the app was opened.
        val talk = PendingIntent.getActivity(this, 1, Intent(this, TalkActivity::class.java).setAction(ACTION_TALK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_minutes)
            .setColor(Palette.AMBER)
            .setContentTitle("Say “${WakeWord.display(prefs.wakePhrase)}”")
            .setContentText("${prefs.assistantName} is listening for you. Tap Talk to speak now.")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), "Talk", talk).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_power), "Stop", stop).build())
            .build()
    }

    /**
     * Asks Android again for the microphone. Called while a screen of the app is open, it lifts the background limit
     * on the microphone for as long as the service runs.
     */
    private fun refreshMicrophone() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, buildNotification())
        }.onFailure { PicoLog.w("Microphone refresh failed", it) }
        micWarned = false
        getSystemService(NotificationManager::class.java).cancel(MIC_NOTIF_ID)
    }

    // ---------- Microphone health ----------

    private var micWarned = false
    private var healthJob: kotlinx.coroutines.Job? = null

    /**
     * Every minute while waiting for the wake word: if the listener has stopped, start it again; if Android has
     * silenced the microphone, say so with a notification that fixes it in one tap (it used to fail silently).
     */
    private fun startHealthChecks() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            var quietMinutes = 0
            while (true) {
                delay(60_000)
                if (destroyed || session?.isActive == true || wakeWord == null) continue
                val configs = runCatching { getSystemService(AudioManager::class.java).activeRecordingConfigurations }.getOrNull()
                    ?: continue
                when {
                    configs.isEmpty() -> {
                        PicoLog.w("Mic check: the wake word listener had stopped; starting it again")
                        stopWakeWord(); startWakeWord()
                    }
                    Build.VERSION.SDK_INT >= 29 && configs.any { it.isClientSilenced } -> {
                        PicoLog.w("Mic check: Android has silenced the microphone")
                        if (!micWarned) { micWarned = true; warnMicBlocked() }
                    }
                    ++quietMinutes % 30 == 0 -> PicoLog.i("Mic check: listening fine")
                }
            }
        }
    }

    private fun warnMicBlocked() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pico_mic", "Microphone", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when Android has stopped your assistant hearing you"
            setSound(null, null)
        })
        val fix = PendingIntent.getActivity(this, 11, Intent(this, TalkActivity::class.java).setAction(ACTION_REFRESH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(MIC_NOTIF_ID, Notification.Builder(this, "pico_mic")
            .setSmallIcon(R.drawable.ic_stat_minutes)
            .setColor(Palette.AMBER)
            .setContentTitle("${prefs.assistantName} can't hear you")
            .setContentText("Android paused the microphone in the background. Tap to fix.")
            .setContentIntent(fix)
            .setAutoCancel(true)
            .build())
    }

    private fun ensureForeground(): Boolean {
        if (foreground) return true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while your assistant is listening for the wake word"
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
            KeepAlive.clearStopped(this)
            true
        } catch (e: Exception) {
            PicoLog.e("Could not start in the foreground", e)
            // Usually Android refusing the microphone from the background: ask for a tap instead.
            if (prefs.wantRunning) KeepAlive.notifyStopped(this, prefs)
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
                PicoLog.i("Wake word listening for \"${prefs.wakePhrase}\"")
                startHealthChecks()
            } catch (e: Exception) {
                PicoLog.e("Wake word failed", e)
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
                PicoLog.e("Wake word failed", e)
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
        // The look and name of whoever is talking (an agent when started from its Talk button), read fresh each time.
        overlay.ghostTheme = GhostTheme.of(brain.agent?.colour ?: prefs.ghostTheme)
        overlay.speakerName = brain.agent?.name ?: prefs.assistantName
        overlay.show()
        overlay.setState(if (intro != null) OrbState.SPEAKING else OrbState.LISTENING)
        if (intro != null) {
            overlay.flash(Mood.NOTIFY, 2500) // a reminder or alert arriving
            earcons.play(Earcons.Cue.NOTIFY)
        } else if (typed == null) {
            overlay.flash(Mood.GREETING, 1500) // a little wave hello
            earcons.play(Earcons.Cue.WAKE)
        }
        haptic()
        requestAudioFocus()
        // Open the connections to Claude and Sarvam while she greets you, so the first answer skips the handshakes.
        scope.launch { brain.warmUp() }
        scope.launch { sarvam.warmUp() }
        scope.launch(Dispatchers.IO) { runCatching { Place.refresh(this@MissMinutesService, prefs) } }
        delay(180)
        try {
            var rounds = 0
            var waitMs = 6000L
            var alreadySaid: ByteArray? = null
            // Claude didn't hear the announcement, so the first reply to it carries it along as context.
            var context = intro
            // What she said last: if the mic picks her own voice back up, it isn't you talking.
            var lastSaid: String? = intro
            if (intro != null) {
                alreadySaid = speak(intro) { overlay.setState(OrbState.SPEAKING) }
                waitMs = 5000L
            } else if (typed == null && prefs.greetOnWake) {
                // "சொல்லுங்க Arun!" — from a cached recording, so it's instant. Talking over it is fine.
                val greeting = brain.agent?.let { "${it.name} here. சொல்லுங்க?" } ?: Greetings.next(prefs)
                lastSaid = greeting
                alreadySaid = speak(greeting, cache = true) { overlay.setState(OrbState.SPEAKING) }
            }
            while (true) {
                overlay.setState(OrbState.LISTENING)
                var heard = takeAnswer()
                val timing = Timing()
                if (heard == null) {
                    // Pico Live: Sarvam hears you while you talk, so the words are ready the moment you stop.
                    val stream = if (prefs.picoLive) sarvam.listen() else null
                    try {
                        val pause = if (prefs.picoLive) LIVE_END_SILENCE_MS else VoiceRecorder.END_SILENCE_MS
                        val wav = withContext(Dispatchers.IO) {
                            recorder.record(waitMs, alreadySaid, pause, stream?.let { s -> { pcm: ByteArray, n: Int -> s.send(pcm, n) } }) {
                                overlay.setLevel(it)
                            }
                        }
                        alreadySaid = null
                        timing.stopped = System.currentTimeMillis()
                        if (wav != null && pendingAnswer == null) earcons.play(Earcons.Cue.HEARD) // "got it"
                        heard = takeAnswer() ?: wav?.let {
                            overlay.setState(OrbState.THINKING)
                            if (stream == null) sarvam.transcribe(it).also { timing.via = "upload" }
                            else coroutineScope {
                                // The live words are usually ready within 300 ms; if not, the upload is already on its way.
                                val upload = async { delay(500); sarvam.transcribe(it) }
                                val live = stream.finish()
                                if (live != null) { upload.cancel(); timing.via = "live"; live }
                                else { timing.via = "upload"; upload.await() }
                            }
                        }
                        timing.heard = System.currentTimeMillis()
                    } finally {
                        stream?.close()
                    }
                }
                if (heard.isNullOrBlank() || isNoise(heard)) break
                if (isEcho(heard, lastSaid) && !isSwitchCommand(heard)) {
                    // Her own voice came back through the mic (usually on the loudspeaker): answering it would
                    // make her say the same thing twice. Ignore it and keep listening for you.
                    PicoLog.i("Ignored her own echo: $heard")
                    lastSaid = null
                    alreadySaid = null
                    waitMs = 5000L
                    continue
                }
                PicoLog.i("Heard: $heard")

                overlay.answerHeard()
                overlay.setState(OrbState.THINKING)
                actions.quietAfterAction = false
                actions.endAfterReply = false
                val turn = replyAndSpeak(heard, context, timing)
                timing.log()
                PicoLog.i("Said: ${turn.reply}")
                Recent.add(prefs, heard, turn.reply, brain.agent?.name)
                if (actions.endAfterReply) break // music is playing: don't listen to the song
                lastSaid = turn.reply
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
                if (isGoodbye(heard)) break // "okay bye": she's said goodbye back, so don't keep listening
                if (asked || prefs.keepListening || prefs.picoLive) { // Pico Live: listen for your reply
                    waitMs = if (asked) 6000L else 4000L
                    continue
                }
                delay(500)
                break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PicoLog.e("Conversation failed", e)
            earcons.play(Earcons.Cue.ERROR)
            val message = friendlyError(e)
            overlay.setState(OrbState.SPEAKING)
            overlay.showPanel(message, OverlayManager.PanelKind.PROBLEM)
            runCatching { player.speak(message) }
            delay(2500)
        } finally {
            // A conversation that ended by itself gets a wave goodbye; one you swiped away just closes.
            val ended = currentCoroutineContext().isActive
            withContext(NonCancellable) {
                if (ended) {
                    overlay.flash(Mood.GOODBYE, 1200)
                    delay(700)
                }
                overlay.hide()
                player.stop()
                abandonAudioFocus()
                brain.agent = null // each conversation starts with the main assistant
                if (actions.musicStarted) {
                    actions.musicStarted = false
                    scope.launch { delay(700); actions.resumeMusicIfPaused() }
                }
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

    /** How long each step of a turn took, from when she decided you'd stopped talking. Logged to tune speed. */
    private class Timing {
        var stopped = 0L
        var heard = 0L
        var firstWords = 0L
        var firstSound = 0L
        var via = ""

        fun log() {
            if (stopped == 0L) return // a typed or tapped answer: nothing was timed
            fun at(t: Long) = if (t == 0L) "-" else "+${t - stopped}ms"
            PicoLog.i("Timing ($via): heard ${at(heard)}, first words ${at(firstWords)}, first sound ${at(firstSound)}")
        }
    }

    /**
     * Asks Claude and speaks each sentence the moment it's written, instead of waiting for the whole answer.
     * If Claude is slow to start, a short cached filler ("ஒரு நிமிஷம்…") covers the silence.
     * If you talk over her, the rest of the answer is dropped (actions still finish).
     */
    private suspend fun replyAndSpeak(heard: String, context: String?, timing: Timing): Turn = coroutineScope {
        val lines = Channel<Pair<String, Boolean>>(Channel.UNLIMITED) // text, from cache
        val order = Any()
        var started = false
        var cutIn: ByteArray? = null

        val speaker = launch {
            // She starts on the first phrase, so a question can arrive in parts ("Do you want me to send it," then
            // "to Ravi?"): collect the parts until the sentence ends, and show the whole question in the cloud.
            val sentence = StringBuilder()
            for ((line, cached) in lines) {
                if (cutIn != null || actions.quietAfterAction) continue
                sentence.append(line).append(' ')
                val ended = line.trimEnd().lastOrNull()?.let { it in ".!?…।" } ?: false
                val question = if (line.trimEnd().endsWith("?")) sentence.toString().trim() else null
                if (ended) sentence.setLength(0)
                if (line.none { it.isLetterOrDigit() }) { // only punctuation left (a name taken out): nothing to say
                    if (question != null) showQuestion(question)
                    continue
                }
                PicoCamera.onCaption?.invoke(sentence.toString().trim().ifEmpty { line })
                val heardOver = speak(line, cache = cached) {
                    if (timing.firstSound == 0L) timing.firstSound = System.currentTimeMillis()
                    overlay.setState(OrbState.SPEAKING)
                    if (question != null) showQuestion(question)
                }
                if (heardOver != null) cutIn = heardOver
            }
        }
        val filler = launch {
            if (prefs.quickReplies && QuickRouter.isQuick(heard)) return@launch // Haiku answers fast enough
            delay(FILLER_AFTER_MS)
            val line = Fillers.pick(heard)
            if (!player.isCached(line)) return@launch
            synchronized(order) { if (!started) { started = true; lines.trySend(line to true) } }
        }

        val reply = try {
            brain.handle(heard, context) { sentence ->
                if (timing.firstWords == 0L) timing.firstWords = System.currentTimeMillis()
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
                    bargeIn.listen({ player.outputRms() }) { PicoLog.i("Interrupted while saying: ${text.take(60)}"); player.stop(); haptic() }
                }
            }
        } finally {
            bargeIn?.cancel()
        }
        listening?.await()
    }

    /** Only punctuation or sounds like "hmm": speech-to-text heard noise, not words. */
    /** You're ending the conversation ("okay bye", "good night", "that's all", "போதும்"). */
    private fun isGoodbye(heard: String): Boolean {
        val t = heard.lowercase()
        return GOODBYES.any { Regex("(^|[^\\p{L}])${Regex.escape(it)}([^\\p{L}]|$)").containsMatchIn(t) }
    }

    private fun showQuestion(question: String) {
        val kind = if (isConfirmation(question)) OverlayManager.PanelKind.CONFIRM else OverlayManager.PanelKind.QUESTION
        overlay.showPanel(question.replace(Regex("\\s+([,.!?])"), "$1"), kind)
    }

    /** Most of what was heard is words she just said herself. */
    private fun isEcho(heard: String, said: String?): Boolean {
        if (said.isNullOrBlank()) return false
        fun words(t: String) = t.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
            .split(' ').filter { it.length > 1 }
        val heardWords = words(heard)
        if (heardWords.isEmpty()) return false
        val saidWords = words(said).flatMap { it.split('-') }.toSet()
        // One or two words she just said ("All." from her own "all-day") is her echo, not a new question.
        if (heardWords.size <= 2) return heardWords.all { it in saidWords }
        return heardWords.count { it in saidWords } >= heardWords.size * 0.7
    }

    /** "Back to Pico", "talk to Chef": a command to switch, never an echo even if she just said those words. */
    private fun isSwitchCommand(heard: String): Boolean {
        val t = heard.lowercase()
        return Regex("\\b(back to|switch to|talk to|go back)\\b").containsMatchIn(t) || ClaudeBrain.soundsLike(t, prefs.assistantName)
    }

    private fun isNoise(text: String): Boolean {
        val words = text.lowercase().replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
            .split(' ').filter { it.isNotEmpty() }
        return words.isEmpty() || words.all { it in NOISE_WORDS }
    }

    /** Her question asks you to approve something ("…அனுப்பட்டுமா?", "Shall I call Amma?"). */
    private fun isConfirmation(reply: String): Boolean {
        val q = reply.trim()
        if (Regex("""(ட்டுமா|டுமா|லாமா)\s*\?$""").containsMatchIn(q)) return true
        val lower = q.lowercase()
        return CONFIRM_PHRASES.any { it in lower }
    }

    private fun friendlyError(e: Exception): String = when {
        e is ClaudeBrain.ClaudeException && e.code == 401 -> "Your Claude API key isn't working. Check it in the app's Settings."
        e is ClaudeBrain.ClaudeException && e.code == 403 -> "Your Claude account can't use this model. Pick another one in the Assistant tab."
        e is ClaudeBrain.ClaudeException && e.code == 429 -> "Too many requests to Claude. Try again in a minute."
        e is ClaudeBrain.ClaudeException && e.code == 529 -> "Claude is busy right now. Try again shortly."
        e is SarvamClient.SarvamException && e.code in 401..403 -> "Your Sarvam API key isn't working. Check it in the app's Settings."
        e is UnknownHostException || e is SocketTimeoutException -> "I can't reach the internet right now."
        else -> "Something went wrong. Let's try again." // the details are in the log, not read out
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        PicoLog.i("App swiped away from recents; still running")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        PicoLog.i(if (prefs.wantRunning) "Service closed by Android (you didn't tap Stop)" else "Service stopped")
        destroyed = true
        running = false
        if (prefs.wantRunning) KeepAlive.check(applicationContext, prefs, "closed")
        recorder.cancel()
        session?.cancel()
        wakeLoading?.cancel()
        wakeWord?.release()
        wakeWord = null
        overlay.hide()
        player.shutdown()
        earcons.release()
        abandonAudioFocus()
        scope.cancel()
        super.onDestroy()
    }
}
