package com.missminutes.agent

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The app screen. First launch shows a welcome board; after that: the dial with start and talk, a setup
 * checklist until everything is ready, then cards for the wake word, brain, voice, behaviour, reminders,
 * memory, keys and permissions.
 */
class MainActivity : Activity() {

    private class PermissionRow(val label: String, val detail: String, val on: Boolean, val fix: () -> Unit)

    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val motion = ValueAnimator.areAnimatorsEnabled()

    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var dial: MinuteDialView
    private lateinit var statusChip: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroDetail: TextView
    private lateinit var startButton: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var wakeField: EditText
    private lateinit var wakeMessage: TextView
    private lateinit var modelBox: LinearLayout
    private lateinit var remindersBox: LinearLayout
    private lateinit var memoryBox: LinearLayout
    private lateinit var keysCard: LinearLayout
    private lateinit var permsCard: LinearLayout
    private lateinit var permsBox: LinearLayout
    private lateinit var claudeKey: EditText
    private lateinit var sarvamKey: EditText
    private lateinit var country: EditText
    private var welcome: View? = null
    private var testPlayer: VoicePlayer? = null

    private val runtimePermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Palette.INK)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18), dpi(8), dpi(18), dpi(40))
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }
        buildHero()
        buildSetup()
        buildWakeWord()
        buildBrain()
        buildVoice()
        buildBehaviour()
        buildReminders()
        buildMemory()
        buildKeys()
        buildPermissions()
        buildFooter()

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true // cards stop at the status bar instead of sliding under the clock
            addView(content)
        }
        root = FrameLayout(this).apply { addView(scroll) }
        applyInsets(scroll)
        setContentView(root)

        if (!prefs.welcomed) showWelcome() else animateIn()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshSetup()
        refreshPermissions()
        refreshReminders()
        refreshMemory()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
        refreshSetup()
    }

    override fun onDestroy() {
        scope.cancel()
        testPlayer?.shutdown()
        dial.stop()
        super.onDestroy()
    }

    private fun applyInsets(v: View) {
        v.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }
    }

    // ---------- Welcome board ----------

    private fun showWelcome() {
        val board = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(28), dpi(24), dpi(28), dpi(28))
        }
        val bigDial = MinuteDialView(this).apply { state = OrbState.LISTENING }
        board.addView(bigDial, LinearLayout.LayoutParams(dpi(220), dpi(220)).apply { topMargin = dpi(12) })
        board.addView(text("Hi, I'm Miss Minutes", 32f, Fonts.BOLD, Palette.CREAM).apply {
            gravity = Gravity.CENTER
        }, wrap(top = 8))
        board.addView(text(
            "Your voice assistant for Tamil, English and Tanglish. Say the word and I'll message, call, " +
                "remind and look things up for you.",
            16f, Fonts.REGULAR, Palette.MUTED
        ).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) }, wrap(top = 10))

        val features = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            Triple(Palette.AMBER, "Speaks your language", "Tamil, English or a mix of both, the way you talk."),
            Triple(Palette.TEAL, "Uses your apps", "WhatsApp, calls, reminders, alarms, music and settings."),
            Triple(Palette.CORAL, "Yours to change", "Pick her wake word, voice and brain. Keys stay encrypted here."),
        ).forEach { (color, title, detail) ->
            features.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpi(16), dpi(14), dpi(16), dpi(14))
                background = surface(radius = 20f, color = Palette.DIAL)
                addView(View(this@MainActivity).apply { background = oval(color) },
                    LinearLayout.LayoutParams(dpi(10), dpi(10)).apply { topMargin = dpi(7); marginEnd = dpi(14) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(title, 16f, Fonts.SEMIBOLD, Palette.CREAM))
                    addView(text(detail, 14f, Fonts.REGULAR, Palette.MUTED), wrap(top = 2))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, full(top = 10))
        }
        board.addView(features, full(top = 26))
        board.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        board.addView(button("Get started", ButtonStyle.FILLED) { dismissWelcome() }, full(top = 28))
        board.addView(text("Setup takes about two minutes.", 13f, Fonts.REGULAR, Palette.MUTED).apply {
            gravity = Gravity.CENTER
        }, full(top = 12))

        val page = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Palette.INK)
            addView(board)
        }
        applyInsets(page)
        root.addView(page)
        welcome = page
        bigDial.start(withIntro = true)
        scroll.visibility = View.INVISIBLE
        if (motion) {
            board.alpha = 0f
            board.translationY = dp(20f)
            board.animate().alpha(1f).translationY(0f).setInterpolator(DecelerateInterpolator(2f)).setDuration(420).start()
        }
    }

    private fun dismissWelcome() {
        prefs.welcomed = true
        val page = welcome ?: return
        welcome = null
        scroll.visibility = View.VISIBLE
        animateIn()
        if (motion) {
            page.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(page) }.start()
        } else {
            root.removeView(page)
        }
    }

    /** Cards rise in one after another the first time the screen shows. */
    private fun animateIn() {
        if (!motion) return
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            child.alpha = 0f
            child.translationY = dp(18f)
            child.animate().alpha(1f).translationY(0f).setStartDelay(40L * minOf(i, 6))
                .setInterpolator(DecelerateInterpolator(2f)).setDuration(360).start()
        }
    }

    // ---------- Hero ----------

    private fun buildHero() {
        val card = card(top = 8)
        card.gravity = Gravity.CENTER_HORIZONTAL
        dial = MinuteDialView(this)
        card.addView(dial, LinearLayout.LayoutParams(dpi(168), dpi(168)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        statusChip = text("", 13f, Fonts.SEMIBOLD, Palette.CREAM).apply {
            setPadding(dpi(12), dpi(5), dpi(12), dpi(5))
        }
        card.addView(statusChip, wrap(top = 4).apply { gravity = Gravity.CENTER_HORIZONTAL })
        heroTitle = text("", 24f, Fonts.SEMIBOLD, Palette.CREAM).apply { gravity = Gravity.CENTER }
        card.addView(heroTitle, full(top = 12))
        heroDetail = text("", 14.5f, Fonts.REGULAR, Palette.MUTED).apply { gravity = Gravity.CENTER }
        card.addView(heroDetail, full(top = 4))
        startButton = button("", ButtonStyle.FILLED) { toggleRunning() }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startButton, LinearLayout.LayoutParams(0, dpi(52), 1f))
            addView(button("Talk now", ButtonStyle.OUTLINE) { talkNow() },
                LinearLayout.LayoutParams(0, dpi(52), 1f).apply { marginStart = dpi(10) })
        }, full(top = 20))
    }

    // ---------- Setup checklist ----------

    private fun buildSetup() {
        setupCard = card()
        header(setupCard, "Finish setting up", null)
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupCard.addView(setupBox, full(top = 6))
    }

    private fun refreshSetup() {
        val keys = prefs.claudeKey.isNotBlank() && prefs.sarvamKey.isNotBlank()
        val missing = permissionRows().count { !it.on }
        val running = MissMinutesService.running
        val steps = listOf(
            Triple(keys, "Add your API keys", "Claude for thinking, Sarvam for her voice") to { scrollTo(keysCard) },
            Triple(missing == 0, "Allow permissions",
                if (missing == 0) "All set" else "$missing still to turn on") to { scrollTo(permsCard) },
            Triple(running, "Wake her up", "Then just say “${WakeWord.display(prefs.wakePhrase)}”") to { toggleRunning() },
        )
        val done = steps.count { it.first.first }
        setupCard.visibility = if (done == steps.size) View.GONE else View.VISIBLE
        setupBox.removeAllViews()
        setupBox.addView(progress(done, steps.size), full(top = 4))
        steps.forEachIndexed { i, (step, onTap) ->
            val (ok, title, detail) = step
            setupBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(56)
                setPadding(0, dpi(8), 0, dpi(8))
                addView(text(if (ok) "✓" else "${i + 1}", 14f, Fonts.SEMIBOLD, if (ok) Palette.INK else Palette.CREAM).apply {
                    gravity = Gravity.CENTER
                    background = oval(if (ok) Palette.TEAL else Palette.SURFACE_HI)
                }, LinearLayout.LayoutParams(dpi(30), dpi(30)).apply { marginEnd = dpi(14) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(title, 16f, Fonts.MEDIUM, if (ok) Palette.MUTED else Palette.CREAM))
                    addView(text(detail, 13.5f, Fonts.REGULAR, Palette.MUTED))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (!ok) {
                    addView(text("›", 22f, Fonts.MEDIUM, Palette.AMBER))
                    background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 30)), null, ColorDrawable(Color.WHITE))
                    setOnClickListener { onTap() }
                }
            }, full())
        }
    }

    private fun progress(done: Int, total: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text("$done of $total done", 13f, Fonts.MEDIUM, Palette.MUTED))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = surface(radius = 99f, color = Palette.SURFACE_HI)
            clipToOutline = true
            addView(View(this@MainActivity).apply { background = surface(radius = 99f, color = Palette.AMBER) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, done.toFloat()))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (total - done).toFloat()))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(6)).apply { topMargin = dpi(8) })
    }

    // ---------- Wake word ----------

    private fun buildWakeWord() {
        val card = card()
        header(card, "Wake word", "What you say to wake her. Change it any time; there's nothing to train.")
        wakeField = input(WakeWord.display(prefs.wakePhrase), "Hey Miss Minutes",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { saveWakePhrase(); true } else false
            }
        }
        card.addView(wakeField, full(top = 12))
        wakeMessage = text("Use English letters, two to four words. For example: Hey Jarvis, Ok Minnu, Hello Buddy.",
            13.5f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }
        card.addView(wakeMessage, full(top = 8))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Save wake word", ButtonStyle.FILLED) { saveWakePhrase() },
                LinearLayout.LayoutParams(0, dpi(48), 1f))
            addView(button("Reset", ButtonStyle.TEXT) {
                wakeField.setText(WakeWord.display(WakeWord.DEFAULT_PHRASE))
                saveWakePhrase()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48)).apply { marginStart = dpi(8) })
        }, full(top = 14))

        card.addView(text("Sensitivity", 15f, Fonts.MEDIUM, Palette.CREAM), full(top = 22))
        card.addView(SeekBar(this).apply {
            max = 60
            progress = ((prefs.wakeSensitivity - 0.3f) * 100).roundToInt().coerceIn(0, 60)
            progressTintList = ColorStateList.valueOf(Palette.AMBER)
            thumbTintList = ColorStateList.valueOf(Palette.AMBER)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.SURFACE_HI)
            setPadding(dpi(4), dpi(12), dpi(4), dpi(12))
            contentDescription = "Wake word sensitivity"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.wakeSensitivity = 0.3f + (seekBar?.progress ?: 35) / 100f
                    reloadService()
                }
            })
        }, full(top = 2))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text("Fewer false wakes", 12.5f, Fonts.REGULAR, Palette.MUTED),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("Wakes faster", 12.5f, Fonts.REGULAR, Palette.MUTED))
        }, full())
    }

    private fun saveWakePhrase() {
        val raw = wakeField.text.toString()
        WakeWord.shapeProblem(raw)?.let { showWakeMessage(it, Palette.CORAL); return }
        val phrase = WakeWord.normalize(raw)
        showWakeMessage("Checking the words…", Palette.MUTED)
        scope.launch {
            val unknown = withContext(Dispatchers.IO) { WakeWord.unknownWords(applicationContext, phrase) }
            if (!unknown.isNullOrEmpty()) {
                val words = unknown.joinToString(", ") { "“$it”" }
                showWakeMessage("She doesn't know $words. Try another spelling or a different word.", Palette.CORAL)
                return@launch
            }
            prefs.wakePhrase = phrase
            wakeField.setText(WakeWord.display(phrase))
            wakeField.clearFocus()
            val shown = "“${WakeWord.display(phrase)}”"
            showWakeMessage(
                if (unknown == null) "Saved. Say $shown to wake her. (The words are checked when she starts for the first time.)"
                else "Saved. Say $shown to wake her.",
                Palette.TEAL
            )
            reloadService()
            refreshStatus()
            refreshSetup()
        }
    }

    private fun showWakeMessage(message: String, color: Int) {
        wakeMessage.text = message
        wakeMessage.setTextColor(color)
    }

    // ---------- Brain ----------

    private fun buildBrain() {
        val card = card()
        header(card, "Brain", "The Claude model that thinks and acts for her. If one isn't available, she moves to the next.")
        modelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(modelBox, full(top = 8))
        refreshModels()
    }

    private fun refreshModels() {
        modelBox.removeAllViews()
        BrainModel.entries.forEach { m ->
            val selected = prefs.model == m.id
            modelBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
                minimumHeight = dpi(56)
                val rowShape = surface(radius = 16f, color = if (selected) Palette.SURFACE_HI else Color.TRANSPARENT).apply {
                    if (selected) setStroke(dpi(1), Palette.alpha(Palette.AMBER, 140))
                }
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 30)), rowShape, null)
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (selected) Palette.AMBER else Color.TRANSPARENT)
                        setStroke(dpi(2), if (selected) Palette.AMBER else Palette.MUTED)
                    }
                }, LinearLayout.LayoutParams(dpi(20), dpi(20)).apply { marginEnd = dpi(14) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(m.title, 16f, Fonts.MEDIUM, Palette.CREAM))
                    addView(text(m.blurb, 13.5f, Fonts.REGULAR, Palette.MUTED), wrap(top = 1))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                isClickable = true
                contentDescription = "${m.title}. ${m.blurb}" + if (selected) ". Selected" else ""
                setOnClickListener { prefs.model = m.id; refreshModels() }
            }, full(top = 4))
        }
    }

    // ---------- Voice ----------

    private fun buildVoice() {
        val card = card()
        header(card, "Voice", "The Sarvam voice she speaks Tamil and English with.")
        val voices = Voices.sarvam
        val spinner = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voices) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                    style(super.getView(position, convertView, parent) as TextView)
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
                    style(super.getDropDownView(position, convertView, parent) as TextView)
                private fun style(v: TextView) = v.apply {
                    typeface = Fonts.get(context, Fonts.REGULAR)
                    textSize = 15.5f
                    setTextColor(Palette.CREAM)
                    setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
                }
            }
            background = surface(radius = 14f, color = Palette.SURFACE_HI)
            setPopupBackgroundDrawable(surface(radius = 16f, color = Palette.SURFACE_HI))
            setSelection(voices.indexOf(prefs.sarvamSpeaker).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.sarvamSpeaker = voices[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(spinner, LinearLayout.LayoutParams(0, dpi(52), 1f))
            addView(button("Test voice", ButtonStyle.OUTLINE) { testVoice() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(52)).apply { marginStart = dpi(10) })
        }, full(top = 12))
        card.addView(text("Names with a style, like shalini_en_companion, use Sarvam's faster model.",
            13f, Fonts.REGULAR, Palette.MUTED), full(top = 10))
    }

    // ---------- Behaviour ----------

    private fun buildBehaviour() {
        val card = card()
        header(card, "Behaviour", null)
        card.addView(toggle("Ask before sending", "She reads a message or call back and waits for your yes.",
            prefs.confirmBeforeSending) { prefs.confirmBeforeSending = it })
        card.addView(toggle("Search the web", "Live answers: weather, news, scores, prices.",
            prefs.webSearch) { prefs.webSearch = it })
        card.addView(toggle("Keep listening", "Carry on talking after each reply without the wake word.",
            prefs.keepListening) { prefs.keepListening = it })
        card.addView(toggle("Let me interrupt her", "Start talking while she speaks and she stops to listen.",
            prefs.bargeIn) { prefs.bargeIn = it })
        card.addView(toggle("Screen glow", "A thin light round the screen while she works in your apps.",
            prefs.edgeGlow) { prefs.edgeGlow = it })
    }

    // ---------- Reminders and alerts ----------

    private fun buildReminders() {
        val card = card()
        header(card, "Reminders & alerts", "Set them by voice. She says them out loud and posts a notification.")
        remindersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(remindersBox, full(top = 8))
    }

    private fun refreshReminders() {
        remindersBox.removeAllViews()
        val reminders = Reminders.all(prefs)
        val watches = Watches.active(prefs)
        if (reminders.isEmpty() && watches.isEmpty()) {
            remindersBox.addView(empty("Nothing set. Try “remind me at 6 to call Amma” or “tell me when Ravi messages”."))
            return
        }
        reminders.forEach { r ->
            remindersBox.addView(listRow(r.text, Reminders.describe(r.at), "Cancel") {
                Reminders.cancel(this, prefs, r.text)
                refreshReminders()
            })
        }
        watches.forEach { w ->
            remindersBox.addView(listRow("Alert me about ${w.describe().substringBefore(", until")}",
                "Until ${Reminders.describe(w.until)}", "Stop") {
                Watches.remove(prefs, w)
                refreshReminders()
            })
        }
    }

    // ---------- Memory ----------

    private fun buildMemory() {
        val card = card()
        header(card, "Memory", "Things she remembers about you. Say “remember that…” to add more.")
        memoryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(memoryBox, full(top = 8))
    }

    private fun refreshMemory() {
        memoryBox.removeAllViews()
        val items = prefs.memories
        if (items.isEmpty()) {
            memoryBox.addView(empty("Nothing yet."))
            return
        }
        items.forEach { fact ->
            memoryBox.addView(listRow(fact, null, "Remove") {
                prefs.memories = prefs.memories - fact
                refreshMemory()
            })
        }
        memoryBox.addView(button("Clear all", ButtonStyle.TEXT) {
            prefs.memories = emptyList()
            refreshMemory()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48)).apply { topMargin = dpi(4) })
    }

    // ---------- Keys ----------

    private fun buildKeys() {
        keysCard = card()
        header(keysCard, "Keys", "Stored encrypted on this phone. Don't share the app with your keys inside.")
        keysCard.addView(label("Claude API key"), full(top = 14))
        claudeKey = input(prefs.claudeKey, "sk-ant-…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        keysCard.addView(claudeKey, full(top = 6))
        keysCard.addView(label("Sarvam API key"), full(top = 14))
        sarvamKey = input(prefs.sarvamKey, "sk_…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        keysCard.addView(sarvamKey, full(top = 6))
        keysCard.addView(label("Country code for phone numbers"), full(top = 14))
        country = input(prefs.countryCode, "91", InputType.TYPE_CLASS_PHONE)
        keysCard.addView(country, full(top = 6))
        keysCard.addView(button("Save keys", ButtonStyle.OUTLINE) {
            saveKeys()
            toast("Keys saved")
            reloadService()
            refreshSetup()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(16) })
    }

    // ---------- Permissions ----------

    private fun buildPermissions() {
        permsCard = card()
        header(permsCard, "Permissions", "Tap anything marked Turn on.")
        permsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permsCard.addView(permsBox, full(top = 6))
        permsCard.addView(text(
            "If Android says Restricted setting: open App info for Miss Minutes, tap ⋮, choose Allow restricted settings, then try again.",
            13f, Fonts.REGULAR, Palette.MUTED
        ).apply { setLineSpacing(0f, 1.2f) }, full(top = 10))
    }

    private fun permissionRows(): List<PermissionRow> {
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val askRuntime: () -> Unit = { requestPermissions(runtimePermissions, 1) }
        return listOf(
            PermissionRow("Microphone", "To hear you", has(Manifest.permission.RECORD_AUDIO), askRuntime),
            PermissionRow("Contacts", "To find who to message or call", has(Manifest.permission.READ_CONTACTS), askRuntime),
            PermissionRow("Phone calls", "To call people for you", has(Manifest.permission.CALL_PHONE), askRuntime),
            PermissionRow("SMS", "To send text messages", has(Manifest.permission.SEND_SMS), askRuntime),
            PermissionRow("Notifications", "For reminders and her status",
                Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS), askRuntime),
            PermissionRow("Display over other apps", "Her orb at the bottom of the screen", Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            PermissionRow("Accessibility", "To tap, type and scroll in your apps", accessibilityOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            PermissionRow("Notification access", "To read messages, reply and alert you", notificationAccessOn()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            PermissionRow("Run in the background", "So she can hear the wake word", batteryUnrestricted()) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            },
        )
    }

    private fun refreshPermissions() {
        permsBox.removeAllViews()
        permissionRows().forEach { row ->
            permsBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(56)
                setPadding(0, dpi(8), 0, dpi(8))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(row.label, 15.5f, Fonts.MEDIUM, Palette.CREAM))
                    addView(text(row.detail, 13f, Fonts.REGULAR, Palette.MUTED))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(chip(if (row.on) "On" else "Turn on", if (row.on) Palette.TEAL else Palette.AMBER, filled = !row.on))
                if (!row.on) {
                    background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 30)), null, ColorDrawable(Color.WHITE))
                    setOnClickListener { row.fix() }
                }
            }, full())
        }
    }

    private fun buildFooter() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        content.addView(text("Miss Minutes $version", 12.5f, Fonts.REGULAR, Palette.alpha(Palette.MUTED, 170)).apply {
            gravity = Gravity.CENTER
        }, full(top = 28))
    }

    // ---------- Actions ----------

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleRunning() {
        if (MissMinutesService.running) {
            startService(MissMinutesService.intent(this, MissMinutesService.ACTION_STOP))
        } else {
            saveKeys()
            if (!hasMic()) { requestPermissions(runtimePermissions, 1); toast("Allow the microphone, then tap Start"); return }
            startForegroundService(MissMinutesService.intent(this, MissMinutesService.ACTION_START))
        }
        heroTitle.postDelayed({ refreshStatus(); refreshSetup() }, 700)
    }

    private fun talkNow() {
        saveKeys()
        if (!hasMic()) { requestPermissions(runtimePermissions, 1); return }
        startForegroundService(MissMinutesService.intent(this, MissMinutesService.ACTION_TALK))
        heroTitle.postDelayed({ refreshStatus(); refreshSetup() }, 700)
    }

    private fun reloadService() {
        if (MissMinutesService.running) startService(MissMinutesService.intent(this, MissMinutesService.ACTION_RELOAD))
    }

    private fun saveKeys() {
        prefs.claudeKey = claudeKey.text.toString()
        prefs.sarvamKey = sarvamKey.text.toString()
        prefs.countryCode = country.text.toString().filter { it.isDigit() }.ifBlank { "91" }
    }

    private fun testVoice() {
        saveKeys()
        scope.launch {
            try {
                val player = testPlayer ?: VoicePlayer(this@MainActivity, SarvamClient(prefs)).also { testPlayer = it }
                dial.state = OrbState.SPEAKING
                player.onLevel = { dial.setLevel(it) }
                dial.start(withIntro = false)
                scroll.smoothScrollTo(0, 0)
                player.speak("வணக்கம்! I'm Miss Minutes. சொல்லுங்க, என்ன பண்ணணும்?", fallback = false)
            } catch (e: Exception) {
                toast(e.message ?: "Couldn't play the voice")
            } finally {
                refreshStatus()
            }
        }
    }

    private fun scrollTo(target: View) {
        scroll.smoothScrollTo(0, (target.top - dpi(12)).coerceAtLeast(0))
    }

    // ---------- Refresh ----------

    private fun refreshStatus() {
        val on = MissMinutesService.running
        val phrase = WakeWord.display(prefs.wakePhrase)
        statusChip.text = if (on) "●  Listening" else "○  Asleep"
        statusChip.setTextColor(if (on) Palette.AMBER else Palette.MUTED)
        statusChip.background = surface(radius = 99f, color = Palette.alpha(if (on) Palette.AMBER else Palette.MUTED, 30))
        heroTitle.text = if (on) "Say “$phrase”" else "She's asleep"
        heroDetail.text = if (on) "or tap Talk now to speak straight away" else "Start her to use the wake word, or tap Talk now"
        startButton.text = if (on) "Stop" else "Start"
        dial.state = OrbState.IDLE
        dial.setLevel(0f)
        if (on) dial.start(withIntro = false) else dial.stop()
    }

    private fun accessibilityOn() =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true

    private fun notificationAccessOn() = getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(this, NotificationReader::class.java))

    private fun batteryUnrestricted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ---------- Building blocks ----------

    private enum class ButtonStyle { FILLED, OUTLINE, TEXT }

    private fun text(value: String, size: Float, weight: Int, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        typeface = Fonts.get(this@MainActivity, weight)
        setTextColor(color)
    }

    private fun label(value: String) = text(value, 14f, Fonts.MEDIUM, Palette.MUTED)

    private fun empty(value: String) = text(value, 14.5f, Fonts.REGULAR, Palette.MUTED).apply {
        setLineSpacing(0f, 1.2f)
        setPadding(0, dpi(6), 0, dpi(6))
    }

    private fun surface(radius: Float, color: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius)
        setColor(color)
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** A rounded section card added to the page; returns it so the section can fill it. */
    private fun card(top: Int = 14): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(20), dpi(20), dpi(20), dpi(20))
        background = surface(radius = 26f, color = Palette.DIAL).apply {
            setStroke(dpi(1), Palette.alpha(Palette.CREAM, 16))
        }
        layoutTransition = LayoutTransition()
        content.addView(this, full(top = top))
    }

    private fun header(card: LinearLayout, title: String, subtitle: String?) {
        card.addView(text(title, 20f, Fonts.SEMIBOLD, Palette.CREAM))
        if (subtitle != null) {
            card.addView(text(subtitle, 14f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }, full(top = 4))
        }
    }

    private fun button(label: String, style: ButtonStyle, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        typeface = Fonts.get(this@MainActivity, Fonts.SEMIBOLD)
        setTextColor(if (style == ButtonStyle.FILLED) Palette.INK else if (style == ButtonStyle.TEXT) Palette.AMBER else Palette.CREAM)
        setPadding(dpi(20), 0, dpi(20), 0)
        minimumHeight = dpi(48)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(99f)
            when (style) {
                ButtonStyle.FILLED -> setColor(Palette.AMBER)
                ButtonStyle.OUTLINE -> { setColor(Color.TRANSPARENT); setStroke(dpi(1), Palette.alpha(Palette.CREAM, 80)) }
                ButtonStyle.TEXT -> setColor(Color.TRANSPARENT)
            }
        }
        val ripple = if (style == ButtonStyle.FILLED) Palette.alpha(Palette.INK, 50) else Palette.alpha(Palette.CREAM, 40)
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, if (style == ButtonStyle.TEXT) oval(Color.WHITE) else null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, color: Int, filled: Boolean) = text(label, 13f, Fonts.SEMIBOLD, if (filled) Palette.INK else color).apply {
        setPadding(dpi(12), dpi(6), dpi(12), dpi(6))
        background = surface(radius = 99f, color = if (filled) color else Palette.alpha(color, 34))
    }

    private fun toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Palette.AMBER, Palette.MUTED))
            trackTintList = ColorStateList(states, intArrayOf(Palette.alpha(Palette.AMBER, 110), Palette.SURFACE_HI))
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(60)
            setPadding(0, dpi(10), 0, dpi(10))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f, Fonts.MEDIUM, Palette.CREAM))
                addView(text(detail, 13.5f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.15f) }, wrap(top = 2))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12) })
            addView(sw)
            background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 24)), null, ColorDrawable(Color.WHITE))
            setOnClickListener { sw.toggle() }
        }
    }

    private fun listRow(title: String, detail: String?, action: String, onAction: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpi(52)
        setPadding(0, dpi(6), 0, dpi(6))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15.5f, Fonts.REGULAR, Palette.CREAM).apply { setLineSpacing(0f, 1.15f) })
            if (detail != null) addView(text(detail, 13f, Fonts.MEDIUM, Palette.AMBER), wrap(top = 2))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(8) })
        addView(button(action, ButtonStyle.TEXT, onAction).apply {
            textSize = 14f
            setTextColor(Palette.MUTED)
            setPadding(dpi(14), 0, dpi(14), 0)
            contentDescription = "$action: $title"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48)))
    }

    private fun input(value: String, hintText: String, type: Int) = EditText(this).apply {
        setText(value)
        setSingleLine()
        textSize = 16f
        typeface = Fonts.get(this@MainActivity, Fonts.REGULAR)
        setTextColor(Palette.CREAM)
        setHintTextColor(Palette.alpha(Palette.MUTED, 140))
        hint = hintText
        inputType = type
        minHeight = dpi(52)
        setPadding(dpi(16), dpi(12), dpi(16), dpi(12))
        background = surface(radius = 14f, color = Palette.SURFACE_HI).apply {
            setStroke(dpi(1), Palette.alpha(Palette.CREAM, 26))
        }
        setOnFocusChangeListener { _, focused ->
            (background as? GradientDrawable)?.setStroke(dpi(if (focused) 2 else 1),
                if (focused) Palette.AMBER else Palette.alpha(Palette.CREAM, 26))
        }
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dpi(top) }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dpi(top) }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
