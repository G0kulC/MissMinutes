package com.missminutes.agent

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The app screen. The first launch runs the setup ([Onboarding]); after that, five tabs: Home (start, talk,
 * reminders), Assistant (name, voice, colour, wake word, brain, behaviour), Agents, Memory, and Settings (profile,
 * backup, privacy, keys, permissions). Nothing here names the assistant directly: it all comes from [Assistant].
 */
class MainActivity : Activity() {

    private class PermissionRow(val label: String, val detail: String, val on: Boolean, val optional: Boolean = false, val fix: () -> Unit)

    private lateinit var prefs: Prefs
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val motion = ValueAnimator.areAnimatorsEnabled()
    private val me get() = Assistant(prefs)

    /** The app's sections, shown one at a time with a bar at the bottom to switch. */
    private enum class Tab(val title: String, val icon: Int) {
        HOME("Home", R.drawable.ic_tab_home),
        ASSISTANT("Assistant", R.drawable.ic_tab_assistant),
        AGENTS("Agents", R.drawable.ic_tab_agents),
        MEMORY("Memory", R.drawable.ic_tab_memory),
        SETTINGS("Settings", R.drawable.ic_tab_settings),
    }

    private fun subtitle(tab: Tab): String? {
        val n = prefs.assistantName
        return when (tab) {
            Tab.HOME -> null
            Tab.ASSISTANT -> "How $n looks, sounds, wakes and thinks"
            Tab.AGENTS -> "Helpers for one job each, with their own voice"
            Tab.MEMORY -> "Details $n uses to personalise answers, and your routines"
            Tab.SETTINGS -> "Your profile, backup, privacy and keys"
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pages: Map<Tab, LinearLayout>
    private val subtitles = HashMap<Tab, TextView>()
    /** The page that build functions add their cards to. */
    private lateinit var content: LinearLayout
    private var currentTab = Tab.HOME
    private val tabButtons = HashMap<Tab, LinearLayout>()
    private lateinit var dial: GhostView
    private lateinit var statusChip: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroDetail: TextView
    private lateinit var startButton: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var assistantField: EditText
    private lateinit var assistantMessage: TextView
    private lateinit var presentationBox: LinearLayout
    private lateinit var voiceBox: LinearLayout
    private lateinit var wakeField: EditText
    private lateinit var wakeMessage: TextView
    private lateinit var modelBox: LinearLayout
    private lateinit var remindersBox: LinearLayout
    private lateinit var agentsBox: LinearLayout
    private lateinit var memoryBox: LinearLayout
    private lateinit var memorySearch: EditText
    private lateinit var importsBox: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var awayBox: LinearLayout
    private lateinit var greeting: TextView
    private lateinit var askField: EditText
    private lateinit var subPages: Map<SubPage, LinearLayout>
    private lateinit var hubBox: LinearLayout
    /** The Assistant sub-page that's open over the tabs, if any. */
    private var openSub: View? = null
    private lateinit var glow: View
    private lateinit var routinesBox: LinearLayout
    private lateinit var nameField: EditText
    private lateinit var cityField: EditText
    private lateinit var country: EditText
    private lateinit var backupStatus: TextView
    private lateinit var keysCard: LinearLayout
    private lateinit var permsCard: LinearLayout
    private lateinit var permsBox: LinearLayout
    private lateinit var claudeKey: EditText
    private lateinit var sarvamKey: EditText
    /** The setup or agent flow covering the tabs, if one is open. */
    private var flow: StepScreen? = null
    private var testPlayer: VoicePlayer? = null
    private var testSarvam: SarvamClient? = null

    private val runtimePermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PicoLog.init(this)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Palette.INK)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        // Each tab is its own page of cards; the build functions add their card to whichever page is [content].
        pages = Tab.entries.associateWith { page(it) }
        content = pages.getValue(Tab.HOME); buildHero(); buildSetup(); buildAway(); buildReminders(); buildRecent()
        subPages = SubPage.entries.associateWith { LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18), 0, dpi(18), dpi(28))
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        } }
        content = subPages.getValue(SubPage.PERSONALITY); buildMyAssistant()
        content = subPages.getValue(SubPage.VOICE); buildVoice()
        content = subPages.getValue(SubPage.COLOUR); buildLook()
        content = subPages.getValue(SubPage.WAKE); buildWakeWord()
        content = subPages.getValue(SubPage.BRAIN); buildBrain()
        content = subPages.getValue(SubPage.BEHAVIOUR); buildBehaviour()
        content = pages.getValue(Tab.ASSISTANT); buildAssistantHub()
        content = pages.getValue(Tab.AGENTS); buildAgents()
        content = pages.getValue(Tab.MEMORY); buildMemory(); buildImportExport(); buildRoutines()
        content = pages.getValue(Tab.SETTINGS); buildProfile(); buildPermissions(); buildBackup(); buildPrivacy(); buildKeys(); buildHelp(); buildAbout(); buildDangerZone()

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true // cards stop at the status bar instead of sliding under the clock
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buildTabBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root = FrameLayout(this).apply { addView(column) }
        applyInsets(column)
        setContentView(root)
        selectTab(Tab.HOME, animate = false)

        if (!prefs.welcomed) showOnboarding() else animateIn()
    }

    override fun onResume() {
        super.onResume()
        // Android closed it while the phone slept: with the app open, starting it again is always allowed.
        if (prefs.wantRunning && !MissMinutesService.running && hasMic() && prefs.welcomed) {
            PicoLog.i("Keep-alive (app opened): not running, starting again")
            startForegroundService(MissMinutesService.intent(this, MissMinutesService.ACTION_START))
            heroTitle.postDelayed({ refreshStatus(); refreshSetup() }, 700)
        } else if (MissMinutesService.running && hasMic()) {
            // With the app on screen, Android allows the microphone fully: refresh it for when the app is closed.
            startService(MissMinutesService.intent(this, MissMinutesService.ACTION_REFRESH))
        }
        if (Place.allowed(this)) scope.launch(Dispatchers.IO) { runCatching { Place.refresh(applicationContext, prefs) } }
        refreshStatus()
        refreshSetup()
        refreshPermissions()
        refreshReminders()
        refreshRecent()
        refreshAway()
        refreshAgents()
        refreshMemory()
    }

    override fun onPause() {
        super.onPause()
        if (flow == null) saveProfile(quiet = true) // typed but never pressed Save
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
        refreshSetup()
    }

    override fun onDestroy() {
        scope.cancel()
        testPlayer?.shutdown()
        feelingsPlayer?.release()
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

    // ---------- Tabs ----------

    /** A page of cards for one tab, with the tab's name on top (Home starts straight with the assistant). */
    private fun page(tab: Tab) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(18), dpi(8), dpi(18), dpi(28))
        layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        subtitle(tab)?.let {
            addView(text(tab.title, 28f, Fonts.BOLD, Palette.CREAM), full(top = 14))
            addView(text(it, 14.5f, Fonts.REGULAR, Palette.MUTED).also { s -> subtitles[tab] = s }, full(top = 2))
        }
    }

    private fun refreshTitles() {
        for ((tab, view) in subtitles) view.text = subtitle(tab)
    }

    private fun buildTabBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dpi(6), dpi(6), dpi(6), dpi(6))
        background = GradientDrawable().apply {
            setColor(Palette.DIAL)
            setStroke(dpi(1), Palette.alpha(Palette.CREAM, 14))
        }
        for (tab in Tab.entries) {
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dpi(56)
                contentDescription = tab.title
                // The mask keeps the touch ripple inside the button instead of a big circle over the bar.
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 30)), null,
                    surface(radius = 18f, color = Color.WHITE))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(tab.icon)
                    setPadding(dpi(14), dpi(4), dpi(14), dpi(4))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dpi(56), dpi(30)))
                addView(text(tab.title, 11.5f, Fonts.MEDIUM, Palette.MUTED).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, wrap(top = 3))
                setOnClickListener { selectTab(tab) }
            }
            tabButtons[tab] = item
            addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun selectTab(tab: Tab, animate: Boolean = true) {
        val pageView = pages.getValue(tab)
        if (scroll.getChildAt(0) !== pageView) {
            scroll.removeAllViews()
            scroll.addView(pageView)
            scroll.scrollTo(0, 0)
            if (animate && motion) {
                pageView.alpha = 0f
                pageView.translationY = dp(10f)
                pageView.animate().alpha(1f).translationY(0f).setInterpolator(DecelerateInterpolator(2f)).setDuration(220).start()
            }
        }
        currentTab = tab
        for ((t, item) in tabButtons) {
            val on = t == tab
            val icon = item.getChildAt(0) as ImageView
            val label = item.getChildAt(1) as TextView
            icon.imageTintList = ColorStateList.valueOf(if (on) Palette.AMBER else Palette.MUTED)
            icon.background = if (on) surface(radius = 99f, color = Palette.alpha(Palette.AMBER, 36)) else null
            label.setTextColor(if (on) Palette.CREAM else Palette.MUTED)
            item.isSelected = on
        }
    }

    /** Opens [tab] and scrolls to [target] on it (from the setup checklist). */
    private fun goTo(tab: Tab, target: View) {
        selectTab(tab)
        scroll.post { scroll.smoothScrollTo(0, (target.top - dpi(12)).coerceAtLeast(0)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back steps back through an open flow, then goes to Home, then leaves the app.
        val open = flow
        when {
            openSub != null -> closeSubPage()
            open != null -> open.back()
            currentTab != Tab.HOME -> selectTab(Tab.HOME)
            else -> super.onBackPressed()
        }
    }

    // ---------- Flows: first-time setup and agents ----------

    private fun showOnboarding() {
        showFlow(Onboarding.steps(this, prefs), { "Finish" }, cancellable = false,
            startAt = prefs.setupStep, onStep = { prefs.setupStep = it }) {
            prefs.welcomed = true
            prefs.setupStep = 0
            reloadService()
            recreate() // every card picks up the new name, voice and colour
        }
    }

    /** Covers the tabs with a one-question-per-screen flow; [onDone] runs only when it's finished. */
    private fun showFlow(steps: List<StepScreen.Step>, finishLabel: () -> String, cancellable: Boolean,
                         startAt: Int = 0, onStep: (Int) -> Unit = {}, onDone: () -> Unit) {
        lateinit var screen: StepScreen
        screen = StepScreen(this, steps, finishLabel, cancellable, startAt, onStep) { finished ->
            hideKeyboard(screen.view)
            closeFlow()
            if (finished) onDone()
        }
        applyInsets(screen.view)
        root.addView(screen.view)
        screen.view.requestApplyInsets() // added after the first layout, so ask for the status bar's height again
        flow = screen
        column.visibility = View.INVISIBLE
        if (motion) {
            screen.view.alpha = 0f
            screen.view.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun closeFlow() {
        val open = flow ?: return
        flow = null
        column.visibility = View.VISIBLE
        root.removeView(open.view)
    }

    /** Cards rise in one after another the first time the screen shows. */
    private fun animateIn() {
        if (!motion) return
        val shown = pages.getValue(currentTab)
        for (i in 0 until shown.childCount) {
            val child = shown.getChildAt(i)
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
        greeting = text("", 15f, Fonts.MEDIUM, Palette.CREAM).apply { gravity = Gravity.CENTER }
        card.addView(greeting, full())
        dial = GhostView(this).apply { theme = GhostTheme.of(prefs.ghostTheme) }
        glow = View(this)
        paintGlow(GhostTheme.of(prefs.ghostTheme))
        card.addView(FrameLayout(this).apply {
            addView(glow, FrameLayout.LayoutParams(dpi(220), dpi(220), Gravity.CENTER))
            addView(dial, FrameLayout.LayoutParams(dpi(168), dpi(168), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dpi(220), dpi(200)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        statusChip = text("", 13f, Fonts.SEMIBOLD, Palette.CREAM)
        heroTitle = text("", 20f, Fonts.SEMIBOLD, Palette.CREAM).apply { gravity = Gravity.CENTER }
        card.addView(heroTitle, full(top = 6))
        heroDetail = text("", 14.5f, Fonts.REGULAR, Palette.MUTED).apply { gravity = Gravity.CENTER }
        startButton = button("", ButtonStyle.FILLED) { toggleRunning() }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startButton, LinearLayout.LayoutParams(0, dpi(52), 1f))
            addView(button("Talk now", ButtonStyle.OUTLINE) { talkNow() },
                LinearLayout.LayoutParams(0, dpi(52), 1f).apply { marginStart = dpi(10) })
        }, full(top = 20))
        askField = input("", "Or type a request…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            contentDescription = "Type a request"
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_NULL) { sendTyped(); true } else false
            }
        }
        // Send is an arrow inside the box, not a second orange button competing with the main one.
        askField.setPadding(dpi(16), dpi(12), dpi(56), dpi(12))
        card.addView(FrameLayout(this).apply {
            addView(askField, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text("➤", 18f, Fonts.BOLD, Palette.AMBER).apply {
                gravity = Gravity.CENTER
                contentDescription = "Send"
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)), null, oval(Color.WHITE))
                isClickable = true
                setOnClickListener { sendTyped() }
            }, FrameLayout.LayoutParams(dpi(48), dpi(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dpi(4) })
        }, full(top = 12))
        card.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // A soft fade at the edge shows there's more to scroll to.
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dpi(36))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(
                    "Brief me" to "Give me my briefing",
                    "What did I miss?" to "What did I miss?",
                    "Weather" to "What's the weather?",
                    "Play music" to "Play some music I'd like",
                ).forEachIndexed { i, (label, request) ->
                    addView(text(label, 14f, Fonts.MEDIUM, Palette.CREAM).apply {
                        gravity = Gravity.CENTER
                        setPadding(dpi(16), 0, dpi(16), 0)
                        background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)),
                            surface(radius = 99f, color = Palette.SURFACE_HI).apply { setStroke(dpi(1), Palette.alpha(Palette.AMBER, 90)) }, null)
                        isClickable = true
                        contentDescription = "Ask: $request"
                        setOnClickListener { ask(request) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(40)).apply { if (i > 0) marginStart = dpi(8) })
                }
            })
        }, full(top = 12))
    }

    /** A soft light behind the ghost, in its own colour. */
    private fun paintGlow(theme: GhostTheme) {
        glow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(110f)
            colors = intArrayOf(Palette.alpha(theme.swatch, 70), Palette.alpha(theme.swatch, 18), Color.TRANSPARENT)
        }
    }

    /** Sends the typed request: the answer is spoken and shown over your apps, like a spoken one. */
    private fun sendTyped() {
        val request = askField.text.toString().trim()
        if (request.isEmpty()) { askField.requestFocus(); return }
        ask(request)
    }

    /** Asks for [request] as if it were said: answered out loud and over your apps. */
    private fun ask(request: String) {
        saveKeys()
        if (prefs.claudeKey.isBlank()) { toast("Add your Claude key in Settings → Keys first"); goTo(Tab.SETTINGS, keysCard); return }
        startForegroundService(MissMinutesService.intent(this, MissMinutesService.ACTION_ASK)
            .putExtra(MissMinutesService.EXTRA_TEXT, request))
        askField.setText("")
        hideKeyboard(askField)
        askField.clearFocus()
        toast("${me.name} is working on it…")
        heroTitle.postDelayed({ refreshStatus(); refreshSetup(); refreshRecent() }, 700)
        heroTitle.postDelayed({ refreshRecent() }, 12_000)
    }

    // ---------- Not available ----------

    private fun buildAway() {
        val card = card()
        // Title with a settings button on the right.
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Away mode", 20f, Fonts.SEMIBOLD, Palette.CREAM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("⚙", 22f, Fonts.REGULAR, Palette.CREAM).apply {
                gravity = Gravity.CENTER
                contentDescription = "Away mode settings"
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)), null, oval(Color.WHITE))
                isClickable = true
                setOnClickListener { awayOptions() }
            }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        })
        card.addView(text("In a meeting or out? ${me.name} declines calls with a polite text saying when you're back, " +
            "answers chats once, and tells you what you missed. Your starred contacts still ring.", 14f, Fonts.REGULAR, Palette.MUTED)
            .apply { setLineSpacing(0f, 1.2f) }, full(top = 2))
        awayBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(awayBox, full(top = 10))
    }

    private fun refreshAway() {
        awayBox.removeAllViews()
        val state = Away.state(prefs)
        if (state == null) {
            listOf(
                Triple("Meeting · 1 hour", "in a meeting", 60),
                Triple("Going out · 2 hours", "out", 120),
                Triple("Driving", "driving", 0),
                Triple("Until I'm back", "busy", 0),
            ).chunked(2).forEach { row ->
                awayBox.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEachIndexed { i, (label, reason, minutes) ->
                        addView(button(label, ButtonStyle.OUTLINE) { startAway(reason, minutes) }.apply { textSize = 14.5f },
                            LinearLayout.LayoutParams(0, dpi(48), 1f).apply { if (i > 0) marginStart = dpi(10) })
                    }
                }, full(top = 8))
            }
            awayBox.addView(text("Or say “${WakeWord.display(prefs.wakePhrase)}, I'm in a meeting till 4”.", 13.5f, Fonts.REGULAR, Palette.MUTED), full(top = 10))
        } else {
            awayBox.addView(text("Away " + (if (state.until > 0) "until ${Away.clock(state.until)}" else "until you're back") + " · ${state.reason}",
                17f, Fonts.SEMIBOLD, Palette.CREAM), full())
            val events = Away.events(prefs)
            awayBox.addView(text(if (events.isEmpty()) "Nobody has called or messaged yet." else Away.summary(state, events, System.currentTimeMillis()),
                14f, Fonts.REGULAR, Palette.CREAM).apply { setLineSpacing(0f, 1.2f) }, full(top = 6))
            awayBox.addView(button("End now", ButtonStyle.FILLED) {
                val summary = Away.end(this, prefs)
                refreshAway()
                PicoDialog(this).title("Welcome back").message(summary).secondary(null).primary("OK") {}.show()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(12) })
        }
        if (!screeningAllowed()) {
            awayBox.addView(text("To handle calls, ${me.name} needs to be your call screening app.", 13.5f, Fonts.MEDIUM, Palette.CORAL)
                .apply { setLineSpacing(0f, 1.2f) }, full(top = 12))
            awayBox.addView(button("Allow call screening", ButtonStyle.OUTLINE) { askScreening() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(6) })
        }
    }

    private fun startAway(reason: String, minutes: Int) {
        Away.start(this, prefs, reason, if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L)
        refreshAway()
        toast("Away mode on: ${me.name} will handle your calls and messages")
    }

    private fun screeningAllowed() = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true

    private fun askScreening() {
        if (Build.VERSION.SDK_INT < 29) { toast("Call screening needs Android 10 or newer"); return }
        val rm = getSystemService(android.app.role.RoleManager::class.java) ?: return
        startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING), REQUEST_SCREENING)
    }

    /** What Not-available mode does, and the reply it sends. */
    private fun awayOptions() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20), dpi(8), dpi(20), 0) }
        box.removeAllViews()
        box.setPadding(0, 0, 0, 0)
        box.addView(toggle("Text callers back", "A declined caller gets one text saying when you're back.", prefs.awayTextCallers) { prefs.awayTextCallers = it })
        box.addView(toggle("Reply to chats", "One reply per person on WhatsApp, Messages and Telegram. Never in groups.", prefs.awayReplyChats) { prefs.awayReplyChats = it })
        box.addView(toggle("Only reply to my contacts", "No replies to unknown numbers, offers, bank alerts or OTP messages. They're still listed for you.",
            prefs.awayContactsOnly) { prefs.awayContactsOnly = it })
        box.addView(toggle("Starred contacts ring", "People you starred in Contacts always get through.", prefs.awayLetStarred) { prefs.awayLetStarred = it })
        box.addView(toggle("Urgent callers ring", "Anyone who calls again within 5 minutes gets through.", prefs.awayLetRepeat) { prefs.awayLetRepeat = it })
        box.addView(label("Your reply (leave empty for the standard one)"), full(top = 12))
        val field = area(prefs.awayMessage.replace("{until}", "[return time]").replace("{name}", "[my name]"),
            "Hi, I'm busy right now. I'll call you back.")
        box.addView(field, full(top = 6))
        // Tap to add the return time or your name where the cursor is, instead of typing codes.
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("+ Return time" to "[return time]", "+ My name" to "[my name]").forEachIndexed { i, (label, piece) ->
                addView(button(label, ButtonStyle.OUTLINE) {
                    val at = field.selectionStart.coerceAtLeast(0)
                    field.text.insert(at, piece)
                }.apply { textSize = 13.5f; minimumHeight = dpi(40) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(40)).apply { if (i > 0) marginStart = dpi(8) })
            }
        }, full(top = 8))
        val preview = text("", 14f, Fonts.REGULAR, Palette.CREAM).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(dpi(14), dpi(10), dpi(14), dpi(10))
            background = surface(radius = 16f, color = Palette.SURFACE_HI)
        }
        fun template() = field.text.toString().trim().replace("[return time]", "{until}").replace("[my name]", "{name}")
        fun showPreview() {
            val sample = AwayState("in a meeting", 0, System.currentTimeMillis() + 3_600_000)
            preview.text = Away.replyText(prefs, sample, template())
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b4: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = showPreview()
        })
        box.addView(label("What people will get (for a 1-hour meeting)"), full(top = 14))
        box.addView(preview, full(top = 6))
        showPreview()
        PicoDialog(this).title("Away mode settings").content(box)
            .primary("Save") { prefs.awayMessage = template().take(300); refreshAway() }
            .show()
    }

    // ---------- Recent ----------

    /** A chat bubble: yours tinted and on the right, the assistant's plain and on the left. */
    private fun bubble(value: String, mine: Boolean, full: Boolean = false) = text(value, if (mine) 15f else 14.5f, if (mine) Fonts.MEDIUM else Fonts.REGULAR, Palette.CREAM).apply {
        setLineSpacing(0f, 1.15f)
        if (!full) {
            maxLines = if (mine) 3 else 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showHistory() } // a cut-off message opens in full
        }
        maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        setPadding(dpi(14), dpi(9), dpi(14), dpi(9))
        background = GradientDrawable().apply {
            setColor(if (mine) Palette.alpha(Palette.AMBER, 46) else Palette.SURFACE_HI)
            val r = dp(18f); val tail = dp(5f)
            cornerRadii = if (mine) floatArrayOf(r, r, tail, tail, r, r, r, r) else floatArrayOf(tail, tail, r, r, r, r, r, r)
        }
    }

    private fun buildRecent() {
        val card = card()
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Last conversation", 20f, Fonts.SEMIBOLD, Palette.CREAM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("See all", ButtonStyle.TEXT) { showHistory() }.apply { textSize = 14.5f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48)))
        })
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(recentBox, full(top = 4))
    }

    private fun refreshRecent() {
        recentBox.removeAllViews()
        val last = Recent.all(prefs).lastOrNull()
        if (last == null) {
            recentBox.addView(empty("Your conversations will show here. Say “${WakeWord.display(prefs.wakePhrase)}” or type a request above."))
            return
        }
        addExchange(recentBox, last, full = false)
    }

    /** One question and its answer, like a chat: yours on the right, the reply on the left, each with its time. */
    private fun addExchange(box: LinearLayout, t: Turn, full: Boolean) {
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(t.at))
        box.addView(bubble(t.heard, mine = true, full = full), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12); gravity = Gravity.END })
        box.addView(text("You · $time", 11.5f, Fonts.MEDIUM, Palette.MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(3); marginEnd = dpi(6); gravity = Gravity.END })
        box.addView(bubble(t.said, mine = false, full = full), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8); gravity = Gravity.START })
        box.addView(text("${t.agent ?: me.name} · $time", 11.5f, Fonts.MEDIUM, Palette.MUTED), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(3); marginStart = dpi(6) })
    }

    /** Every recent conversation, oldest first, in full; clearing them lives here and asks first. */
    private fun showHistory() {
        val turns = Recent.all(prefs)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (turns.isEmpty()) box.addView(empty("Nothing yet."))
        turns.forEach { addExchange(box, it, full = true) }
        val d = PicoDialog(this).title("Conversations").content(box).secondary(null).primary("Done") {}
        if (turns.isNotEmpty()) d.extra("Clear history") {
            confirm("Clear conversation history?", "Your ${turns.size} recent conversations are removed from this phone. Your memories stay.", "Clear") {
                Recent.clear(prefs)
                refreshRecent()
            }
        }
        d.show()
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
        val missing = permissionRows().count { !it.on && !it.optional }
        val running = MissMinutesService.running
        val steps = listOf(
            Triple(keys, "Connect ${me.name}'s brain and voice", "Add the two keys in Settings") to { goTo(Tab.SETTINGS, keysCard) },
            Triple(missing == 0, "Allow permissions",
                if (missing == 0) "All set" else "$missing still to turn on") to { goTo(Tab.SETTINGS, permsCard) },
            Triple(running, "Turn on ${WakeWord.display(prefs.wakePhrase)}", "Then just say it to talk") to { toggleRunning() },
        )
        val done = steps.count { it.first.first }
        setupCard.visibility = if (done == steps.size) View.GONE else View.VISIBLE
        setupBox.removeAllViews()
        setupBox.addView(text("$done of ${steps.size} done", 13f, Fonts.MEDIUM, Palette.MUTED), full(top = 4))
        setupBox.addView(progressBar(done, steps.size), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(6)).apply { topMargin = dpi(8) })
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

    /** A thin bar, [done] of [total] filled. */
    internal fun progressBar(done: Int, total: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = surface(radius = 99f, color = Palette.SURFACE_HI)
        clipToOutline = true
        addView(View(this@MainActivity).apply { background = surface(radius = 99f, color = Palette.AMBER) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, done.toFloat()))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (total - done).toFloat()))
    }

    // ---------- Assistant hub ----------

    /** The Assistant tab's sections, each on its own page. */
    private enum class SubPage(val title: String, val icon: String) {
        PERSONALITY("Personality", "✦"), VOICE("Voice", "♪"), COLOUR("Colour", "●"),
        WAKE("Wake word", "◉"), BRAIN("Brain", "✳"), BEHAVIOUR("Behaviour", "⚑"),
    }

    private fun subValue(p: SubPage): String = when (p) {
        SubPage.PERSONALITY -> "${prefs.assistantName} · ${me.presentation.title}"
        SubPage.VOICE -> Voices.title(prefs.sarvamSpeaker).substringBefore(" ·")
        SubPage.COLOUR -> GhostTheme.of(prefs.ghostTheme).title
        SubPage.WAKE -> "“${WakeWord.display(prefs.wakePhrase)}”"
        SubPage.BRAIN -> when (BrainModel.byId(prefs.model)) { BrainModel.SMART -> "Smartest"; BrainModel.FAST -> "Fastest"; else -> "Balanced" }
        SubPage.BEHAVIOUR -> if (prefs.confirmBeforeSending) "Asks before sending" else "Sends without asking"
    }

    private fun buildAssistantHub() {
        val card = card()
        hubBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(hubBox)
        refreshHub()
    }

    private fun refreshHub() {
        hubBox.removeAllViews()
        SubPage.entries.forEachIndexed { i, p ->
            if (i > 0) hubBox.addView(View(this).apply { setBackgroundColor(Palette.alpha(Palette.CREAM, 16)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1)))
            hubBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(64)
                setPadding(dpi(4), dpi(8), dpi(4), dpi(8))
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 30)), null, ColorDrawable(Color.WHITE))
                isClickable = true
                contentDescription = "${p.title}: ${subValue(p)}"
                setOnClickListener { openSubPage(p) }
                addView(text(p.icon, 20f, Fonts.BOLD, if (p == SubPage.COLOUR) GhostTheme.of(prefs.ghostTheme).swatch else Palette.AMBER).apply {
                    gravity = Gravity.CENTER
                    background = oval(Palette.SURFACE_HI)
                }, LinearLayout.LayoutParams(dpi(40), dpi(40)).apply { marginEnd = dpi(14) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(p.title, 16.5f, Fonts.MEDIUM, Palette.CREAM))
                    addView(text(subValue(p), 13.5f, Fonts.REGULAR, Palette.MUTED), wrap(top = 1))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text("›", 24f, Fonts.MEDIUM, Palette.MUTED))
            }, full())
        }
    }

    /** Opens a section over the tabs, with a back arrow; system Back closes it too. */
    private fun openSubPage(p: SubPage) {
        val pageBody = subPages.getValue(p)
        (pageBody.parent as? ViewGroup)?.removeView(pageBody)
        // The page's own title is in its top bar, so the card doesn't repeat it ("‹ Brain" then "Brain").
        ((pageBody.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? TextView)?.let { t ->
            if (t.text.toString().equals(p.title, true) || p == SubPage.PERSONALITY) t.visibility = View.GONE
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.INK)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(6), dpi(6), dpi(18), dpi(6))
                addView(text("‹", 30f, Fonts.MEDIUM, Palette.CREAM).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Back"
                    background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)), null, oval(Color.WHITE))
                    isClickable = true
                    setOnClickListener { closeSubPage() }
                }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
                addView(text(p.title, 22f, Fonts.BOLD, Palette.CREAM), wrap().apply { marginStart = dpi(6) })
            }, full())
            addView(ScrollView(this@MainActivity).apply { addView(pageBody) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        applyInsets(screen)
        root.addView(screen)
        screen.requestApplyInsets()
        openSub = screen
        column.visibility = View.INVISIBLE
        if (motion) { screen.alpha = 0f; screen.translationX = dp(40f); screen.animate().alpha(1f).translationX(0f).setDuration(200).start() }
    }

    private fun closeSubPage() {
        val screen = openSub ?: return
        openSub = null
        hideKeyboard(screen)
        (subPages.values.firstOrNull { it.parent != null }?.parent as? ViewGroup)?.removeAllViews()
        root.removeView(screen)
        column.visibility = View.VISIBLE
        refreshHub()
    }

    // ---------- My Assistant ----------

    private fun buildMyAssistant() {
        val card = card()
        header(card, "My assistant", "Name, how ${me.they} ${me.verb("presents", "present")} and how ${me.they} ${me.verb("greets", "greet")} you.")
        card.addView(label("Name"), full(top = 14))
        assistantField = input(prefs.assistantName, Assistant.DEFAULT_NAME, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_NULL) { saveAssistantName(); true } else false
            }
            val outline = onFocusChangeListener
            setOnFocusChangeListener { v, focused ->
                outline?.onFocusChange(v, focused)
                if (!focused && text.toString().trim() != prefs.assistantName) saveAssistantName()
            }
        }
        card.addView(assistantField, full(top = 6))
        assistantMessage = text("Your wake word updates too: Hey ${prefs.assistantName} → Hey Mira.", 13.5f, Fonts.REGULAR, Palette.MUTED)
            .apply { setLineSpacing(0f, 1.2f) }
        card.addView(assistantMessage, full(top = 8))

        card.addView(label("Presents as"), full(top = 18))
        presentationBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(presentationBox, full(top = 4))
        refreshPresentation()

        card.addView(toggle("Greet me when ${me.they} ${me.verb("wakes", "wake")}", "A quick hello like “Hey there!” or “சொல்லுங்க!”, then ${me.they} ${me.verb("listens", "listen")}.",
            prefs.greetOnWake) { prefs.greetOnWake = it; reloadService() }, full(top = 10))
        card.addView(toggle("Use my name in greetings",
            "Now and then, like “Hey ${prefs.userName.ifBlank { "there" }}!”. Turn off to never hear your name in a greeting.",
            prefs.greetWithName) { prefs.greetWithName = it; reloadService() })
        card.addView(button("Hear a greeting", ButtonStyle.OUTLINE) { playSample(Greetings.all(prefs).drop(1).random(), cache = true) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(6) })
    }

    private fun saveAssistantName() {
        val raw = assistantField.text.toString()
        if (Assistant.cleanName(raw).isEmpty()) { showAssistantMessage("Type a name, or ${Assistant.DEFAULT_NAME}.", Palette.CORAL); return }
        hideKeyboard(assistantField)
        assistantField.clearFocus()
        scope.launch {
            val note = Assistant.rename(this@MainActivity, prefs, raw)
            assistantField.setText(prefs.assistantName)
            wakeField.setText(WakeWord.display(prefs.wakePhrase))
            showAssistantMessage(note ?: "Saved. Say “${WakeWord.display(prefs.wakePhrase)}” to wake ${me.them}.",
                if (note == null) Palette.TEAL else Palette.AMBER)
            refreshTitles()
            refreshStatus()
            refreshSetup()
            refreshAgents()
            reloadService()
        }
    }

    private fun showAssistantMessage(message: String, color: Int) {
        assistantMessage.text = message
        assistantMessage.setTextColor(color)
    }

    private fun refreshPresentation() {
        presentationBox.removeAllViews()
        Presentation.entries.forEach { p ->
            presentationBox.addView(radioRow(p.title, p.detail, prefs.presentation == p.name) {
                prefs.presentation = p.name
                refreshPresentation()
                refreshVoice()
            }, full(top = 2))
        }
    }

    // ---------- Wake word ----------

    private fun buildWakeWord() {
        val card = card()
        header(card, "Wake word", "What you say to wake ${me.them}. Change it any time; there's nothing to train.")
        wakeField = input(WakeWord.display(prefs.wakePhrase), "Hey ${prefs.assistantName}",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_NULL) { saveWakePhrase(); true } else false
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
        val levels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(levels, full(top = 4))
        fun renderLevels() {
            levels.removeAllViews()
            val now = prefs.wakeSensitivity
            listOf(Triple("Low", "Fewest false wakes; say it clearly", 0.5f), Triple("Medium", "The best balance for most rooms", 0.65f),
                Triple("High", "Wakes fastest, even when you speak softly", 0.8f)).forEach { (title, about, value) ->
                val picked = kotlin.math.abs(now - value) < 0.075f
                levels.addView(radioRow(title, about, picked) {
                    prefs.wakeSensitivity = value
                    reloadService()
                    renderLevels()
                    toast("Saved")
                }, full(top = 2))
            }
        }
        renderLevels()
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
                showWakeMessage("The offline listener doesn't know $words. Try another spelling or a different word.", Palette.CORAL)
                return@launch
            }
            prefs.wakePhrase = phrase
            wakeField.setText(WakeWord.display(phrase))
            wakeField.clearFocus()
            val shown = "“${WakeWord.display(phrase)}”"
            showWakeMessage(
                if (unknown == null) "Saved. Say $shown to wake ${me.them}. (The words are checked the first time ${me.they} ${me.verb("starts", "start")}.)"
                else "Saved. Say $shown to wake ${me.them}.",
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
        header(card, "Brain", "The Claude model that thinks and acts for ${me.them}. With Quick replies on, simple commands use Haiku. " +
            "If a model isn't available, ${me.they} ${me.verb("moves", "move")} to the next.")
        modelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(modelBox, full(top = 8))
        refreshModels()
    }

    private fun refreshModels() {
        modelBox.removeAllViews()
        BrainModel.entries.forEach { m ->
            val title = when (m) { BrainModel.SMART -> "Smartest"; BrainModel.BALANCED -> "Balanced (Recommended)"; BrainModel.FAST -> "Fastest" }
            val about = when (m) {
                BrainModel.SMART -> "Best for long tasks in your apps. Slower, costs the most."
                BrainModel.BALANCED -> "Quick and very capable: the best everyday choice."
                BrainModel.FAST -> "Quickest replies and lowest cost."
            }
            modelBox.addView(radioRow(title, "$about\n${m.title}", prefs.model == m.id) { prefs.model = m.id; refreshModels(); toast("Saved") }, full(top = 4))
        }
    }

    // ---------- Voice ----------

    private fun buildVoice() {
        val card = card()
        header(card, "Voice", "The Sarvam voice ${me.they} ${me.verb("speaks", "speak")} Tamil and English with. Tap Play to hear one.")
        voiceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(voiceBox, full(top = 8))
        refreshVoice()
    }

    private fun refreshVoice() {
        val options = Voices.forPresentation(me.presentation)
        voiceList(voiceBox, options, prefs.sarvamSpeaker, sameAs = null, speakerName = me.name) { id ->
            if (id != null && id != prefs.sarvamSpeaker) { prefs.sarvamSpeaker = id; reloadService() }
        }
        if (options.none { it.id == prefs.sarvamSpeaker }) {
            voiceBox.addView(text("Your current voice, ${Voices.title(prefs.sarvamSpeaker)}, isn't in this list. Pick one to switch.",
                13f, Fonts.REGULAR, Palette.AMBER), full(top = 8))
        }
    }

    /**
     * A list of voices with a Play button each. [sameAs], when given, adds a first choice meaning "no voice of
     * its own" (null). [speakerName] is who introduces themselves in the preview.
     */
    internal fun voiceList(box: LinearLayout, options: List<VoiceOption>, selected: String?, sameAs: String?,
                           speakerName: String, onPick: (String?) -> Unit) {
        var current = selected
        fun render() {
            box.removeAllViews()
            if (sameAs != null) box.addView(radioRow(sameAs, null, current == null) { current = null; onPick(null); render() }, full(top = 2))
            options.forEach { v ->
                val play = button("▶", ButtonStyle.TEXT) { previewVoice(v.id, speakerName) }.apply {
                    textSize = 16f
                    contentDescription = "Play ${v.title}"
                }
                // "Shalini · companion (faster)" → "Shalini", with "Faster voice" underneath.
                val name = v.title.substringBefore(" ·")
                box.addView(radioRow(name, if ('_' in v.id) "Faster voice" else null, current == v.id, play) {
                    current = v.id; onPick(v.id); render(); previewVoice(v.id, speakerName)
                }, full(top = 2))
            }
        }
        render()
    }

    private var feelingsPlayer: Earcons? = null

    /** Plays every feeling sound in turn, naming each one. */
    private fun previewFeelings() {
        val player = feelingsPlayer ?: Earcons(this, prefs).also { feelingsPlayer = it }
        scope.launch {
            kotlinx.coroutines.delay(300) // the sounds load in the background the first time
            for (f in Earcons.Feeling.entries) {
                toast(f.title)
                player.preview(f)
                kotlinx.coroutines.delay(1400)
            }
        }
    }

    private fun previewVoice(voice: String, speakerName: String) =
        playSample("வணக்கம்! I'm $speakerName. சொல்லுங்க, என்ன பண்ணணும்?", cache = false, voice = voice)

    // ---------- Behaviour ----------

    private fun buildBehaviour() {
        val card = card()
        val they = me.they
        header(card, "Behaviour", null)
        fun group(name: String) = card.addView(text(name.uppercase(), 12.5f, Fonts.SEMIBOLD, Palette.MUTED).apply { letterSpacing = 0.08f }, full(top = 18))
        group("Safety")
        card.addView(toggle("Ask before sending", "${me.They} ${me.verb("reads", "read")} the message or ${me.verb("names", "name")} the person and ${me.verb("waits", "wait")} for your yes before sending or calling. Agents follow this too.",
            prefs.confirmBeforeSending) { prefs.confirmBeforeSending = it })
        group("Conversation")
        card.addView(toggle("Faster back-and-forth",
            "Real-time feel: $they ${me.verb("hears", "hear")} you while you talk, ${me.verb("answers", "answer")} after a shorter pause and ${me.verb("keeps", "keep")} listening for your reply. No extra cost.",
            prefs.picoLive) { prefs.picoLive = it })
        card.addView(toggle("Keep listening", "Carry on talking after each reply without saying the wake word again.",
            prefs.keepListening) { prefs.keepListening = it })
        card.addView(toggle("Let me interrupt ${me.them}", "Start talking while $they ${me.verb("speaks", "speak")} and $they ${me.verb("stops", "stop")} to listen.",
            prefs.bargeIn) { prefs.bargeIn = it })
        card.addView(toggle("Quick replies", "Simple commands like calls, alarms or the flashlight use Claude Haiku 5.5 for an instant answer.",
            prefs.quickReplies) { prefs.quickReplies = it })
        group("Internet")
        card.addView(toggle("Search the web", "Live answers: weather, news, scores, prices.",
            prefs.webSearch) { prefs.webSearch = it })
        group("Sounds & visuals")
        card.addView(toggle("Sounds", "Short, quiet cues when $they ${me.verb("wakes", "wake")}, ${me.verb("hears", "hear")} you, ${me.verb("finishes", "finish")} or ${me.verb("hits", "hit")} a problem.",
            prefs.sounds) { prefs.sounds = it })
        card.addView(toggle("Feeling sounds", "A tiny cute voice with the mood of a reply now and then: “awww”, a giggle, a little sob, “hmph!”.",
            prefs.moodSounds) { prefs.moodSounds = it })
        card.addView(button("Hear the feeling sounds", ButtonStyle.OUTLINE) { previewFeelings() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(4); bottomMargin = dpi(6) })
        card.addView(toggle("Screen glow", "A thin light round the screen while $they ${me.verb("works", "work")} in your apps.",
            prefs.edgeGlow) { prefs.edgeGlow = it })
    }

    // ---------- Reminders and alerts ----------

    private fun buildReminders() {
        val card = card()
        header(card, "Reminders", "${me.They} ${me.verb("says", "say")} them out loud and ${me.verb("posts", "post")} a notification.")
        remindersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(remindersBox, full(top = 8))
        card.addView(button("+ Add reminder", ButtonStyle.OUTLINE) {
            textDialog("Add a reminder", "", "Call Amma at 6 pm", helper = "What, and when? ${me.name} sets it for you.", saveLabel = "Set reminder") {
                ask("Set a reminder: $it")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(10) })
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

    // ---------- Agents ----------

    private fun buildAgents() {
        val main = card()
        header(main, "Main assistant", null)
        main.addView(text("", 14f, Fonts.REGULAR, Palette.MUTED).apply { tag = "main-detail"; setLineSpacing(0f, 1.2f) }, full(top = 4))
        main.addView(button("Talk", ButtonStyle.OUTLINE) { talkNow() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(12) })

        val card = card()
        header(card, "Your agents", "Say “talk to Chef” to switch to one, and “back to ${prefs.assistantName}” to return. Each conversation starts with ${prefs.assistantName}.")
        agentsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(agentsBox, full(top = 8))
        card.addView(button("Create an agent", ButtonStyle.FILLED) { editAgent(null) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(52)).apply { topMargin = dpi(14) })

        // Ready-made ideas: each opens the wizard filled in, to change or just create.
        val ideas = card()
        header(ideas, "Start from an idea", null)
        listOf(
            Triple("Cook", "Quick dinner recipes with what's at home", setOf(Ability.WEB, Ability.TIME)),
            Triple("Study buddy", "Help me revise and quiz me before exams", setOf(Ability.WEB)),
            Triple("Fitness coach", "Simple home workouts and keeping me on track", setOf(Ability.TIME)),
        ).forEach { (name, purpose, abilities) ->
            ideas.addView(listRow(name, purpose, "Use") { editAgent(null, Agent(name = name, purpose = purpose, abilities = abilities)) }
                .apply { setOnClickListener { editAgent(null, Agent(name = name, purpose = purpose, abilities = abilities)) } }, full(top = 4))
        }
    }

    private fun refreshAgents() {
        pages.getValue(Tab.AGENTS).findViewWithTag<TextView>("main-detail")?.text =
            "${me.name}. ${me.Theyre} always here: say “${WakeWord.display(prefs.wakePhrase)}”. Voice: ${Voices.title(prefs.sarvamSpeaker)}."
        agentsBox.removeAllViews()
        val agents = Agents.all(prefs)
        if (agents.isEmpty()) {
            agentsBox.addView(empty("No agents yet. Create one for a job you do often, like a Chef for recipes, " +
                "a Study buddy for revision or a Coach for workouts."))
            return
        }
        agents.forEachIndexed { i, agent ->
            if (i > 0) agentsBox.addView(View(this).apply { setBackgroundColor(Palette.alpha(Palette.CREAM, 16)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(1)).apply { topMargin = dpi(6) })
            // The whole card starts talking to the agent; Edit, Duplicate and Delete are in the ⋮ menu.
            agentsBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpi(10), 0, dpi(10))
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 30)), null, ColorDrawable(Color.WHITE))
                isClickable = true
                contentDescription = "Talk to ${agent.name}"
                setOnClickListener { talkNow(agent) }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_ghost_mini)
                    imageTintList = ColorStateList.valueOf(GhostTheme.of(agent.colour ?: prefs.ghostTheme).swatch)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dpi(40), dpi(40)).apply { marginEnd = dpi(12) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(agent.name, 17f, Fonts.SEMIBOLD, Palette.CREAM))
                    addView(text(agent.purpose, 14f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.15f) }, wrap(top = 1))
                    addView(chips((if (agent.abilities.isEmpty()) listOf("Talk only") else agent.abilities.map { it.title }) +
                        listOf(agent.voice?.let { Voices.title(it).substringBefore(" ·") } ?: "${prefs.assistantName}'s voice")), wrap(top = 6))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(moreButton("More for ${agent.name}") {
                    actionSheet(agent.name, listOf(
                        "Talk" to { talkNow(agent) },
                        "Edit" to { editAgent(agent) },
                        "Duplicate" to { duplicateAgent(agent) },
                        "Delete" to {
                            confirm("Delete ${agent.name}?", "${agent.name} and its instructions will be removed. This can't be undone.", "Delete") {
                                Agents.remove(prefs, agent.id)
                                refreshAgents()
                                toast("${agent.name} deleted")
                            }
                        },
                    ))
                })
            }, full())
        }
    }

    private fun editAgent(agent: Agent?, template: Agent? = null) {
        var made: Agent? = null
        val steps = Onboarding.agentSteps(this, prefs, agent ?: template) { saved -> made = saved }
        showFlow(steps, { if (agent == null) "Create ${Onboarding.draftName.ifBlank { "agent" }}" else "Save changes" }, cancellable = true) {
            val saved = made ?: return@showFlow
            Agents.put(prefs, saved)
            refreshAgents()
            selectTab(Tab.AGENTS, animate = false)
            if (agent != null) { toast("Changes saved"); return@showFlow }
            PicoDialog(this).title("${saved.name} is ready")
                .message("Say “talk to ${saved.name}” to try it, or tap Talk now. Say “back to ${prefs.assistantName}” to return.")
                .secondary("Done").primary("Talk now") { talkNow(saved) }
                .show()
        }
    }

    private fun duplicateAgent(agent: Agent) {
        val names = Agents.all(prefs).map { it.name.lowercase() }.toSet()
        val name = generateSequence(2) { it + 1 }.map { "${agent.name.take(17)} $it" }.first { it.lowercase() !in names }
        Agents.put(prefs, agent.copy(id = java.util.UUID.randomUUID().toString(), name = name))
        refreshAgents()
        toast("Made a copy called $name")
    }

    // ---------- Memory ----------

    private fun buildMemory() {
        val card = card()
        header(card, "What ${me.they} ${me.verb("knows", "know")} about you",
            "These are details ${me.name} can use to personalise future answers. Say “remember that…” or add one here. Tap one to change it.")
        memorySearch = input("", "Search memories", InputType.TYPE_CLASS_TEXT).apply {
            contentDescription = "Search memories"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = refreshMemory()
            })
        }
        card.addView(memorySearch, full(top = 12))
        memoryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(memoryBox, full(top = 8))
    }

    private fun buildRoutines() {
        val card = card()
        header(card, "Routines", "One phrase that runs several steps. Say “make a routine called…” to add one. Tap one to change it.")
        routinesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(routinesBox, full(top = 8))
    }

    private fun refreshMemory() {
        memoryBox.removeAllViews()
        val items = prefs.memories
        val query = memorySearch.text.toString().trim()
        memorySearch.visibility = if (items.size >= 5 || query.isNotEmpty()) View.VISIBLE else View.GONE
        val shown = if (query.isEmpty()) items else items.filter { it.contains(query, ignoreCase = true) }
        if (items.isNotEmpty() && shown.isEmpty()) memoryBox.addView(empty("Nothing matches “$query”."))
        if (items.isEmpty()) {
            memoryBox.addView(empty("Nothing yet. Tell ${me.them} things like “my birthday is 12 March”, “Amma is saved as Mom” " +
                "or “I love Anirudh songs”. ${me.They} ${me.verb("asks", "ask")} a question now and then to get to know you, too."))
        }
        if (items.isNotEmpty()) memoryBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(if (items.size == 1) "1 memory" else "${items.size} memories", 14f, Fonts.MEDIUM, Palette.MUTED),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(moreButton("More memory options") {
                actionSheet("Memories", listOf("Clear all" to {
                    val what = if (items.size == 1) "the 1 thing" else "all ${items.size} things"
                    confirm("Clear all memories?", "${me.name} will forget $what ${me.they} ${me.verb("knows", "know")} about you. This can't be undone.", "Clear all") {
                        prefs.memories = emptyList()
                        refreshMemory()
                    }
                }))
            })
        }, full())
        shown.forEach { fact ->
            memoryBox.addView(trashRow(fact, onTap = { editMemory(fact) }) {
                // Gone at once, with a few seconds to take it back.
                val before = prefs.memories
                prefs.memories = before - fact
                refreshMemory()
                undoBar("Memory deleted") { prefs.memories = before; refreshMemory() }
            })
        }
        memoryBox.addView(button("+ Add a memory", ButtonStyle.OUTLINE) { editMemory(null) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(10) })

        refreshImports()
        routinesBox.removeAllViews()
        val routines = prefs.routines
        if (routines.isEmpty()) routinesBox.addView(empty("No routines yet. Try “make a routine called good night: silent mode, alarm at 6, dim the screen”."))
        routines.forEach { (name, steps) ->
            routinesBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(14), dpi(12), dpi(6), dpi(12))
                background = surface(radius = 18f, color = Palette.SURFACE_HI)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text("“${name.replaceFirstChar(Char::uppercase)}”", 16.5f, Fonts.SEMIBOLD, Palette.CREAM),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(button("▶ Run", ButtonStyle.OUTLINE) { ask(name) }.apply { textSize = 14f; contentDescription = "Run $name" },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(40)))
                    addView(moreButton("More for $name") {
                        actionSheet("“$name”", listOf(
                            "Edit" to { editRoutine(name, steps) },
                            "Delete" to {
                                confirm("Delete the “$name” routine?", "Saying “$name” won't run these steps any more.", "Delete") {
                                    prefs.routines = prefs.routines - name
                                    refreshMemory()
                                }
                            },
                        ))
                    })
                })
                addView(text(steps, 14f, Fonts.REGULAR, Palette.CREAM).apply { setLineSpacing(0f, 1.2f); alpha = 0.85f }, full(top = 4))
            }, full(top = 8))
        }
        routinesBox.addView(button("+ New routine", ButtonStyle.OUTLINE) { newRoutine() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(12) })
    }

    /** A routine typed in: the phrase that starts it, and what it should do. */
    private fun newRoutine() {
        val phrase = input("", "For example, good night", InputType.TYPE_CLASS_TEXT)
        val steps = area("", "For example, silent mode, alarm at 6 am, screen brightness 10%").apply { minLines = 3 }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("When I say"), full())
            addView(phrase, full(top = 6))
            addView(label("Do this"), full(top = 14))
            addView(steps, full(top = 6))
        }
        PicoDialog(this).title("New routine").content(box).requires(phrase)
            .primary("Save") {
                val name = phrase.text.toString().trim().lowercase()
                val what = steps.text.toString().trim()
                if (name.isEmpty() || what.isEmpty()) { toast("Add both the phrase and what it should do"); return@primary }
                prefs.routines = prefs.routines + (name to what)
                refreshMemory()
                toast("Saved. Say “$name” to run it.")
            }.show()
    }

    // ---------- Memory import and export ----------

    private fun buildImportExport() {
        val card = card()
        header(card, "Import & export", "Save your memories to a file, or bring them in from a file, a ChatGPT export or text you paste. " +
            "You'll see what was found before anything is added.")
        card.addView(button("Export memories", ButtonStyle.OUTLINE) { exportMemories() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(14) })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Import a file", ButtonStyle.OUTLINE) { pickFile(REQUEST_IMPORT_MEMORIES) }, LinearLayout.LayoutParams(0, dpi(48), 1f))
            addView(button("Paste memories", ButtonStyle.OUTLINE) { pasteMemories() },
                LinearLayout.LayoutParams(0, dpi(48), 1f).apply { marginStart = dpi(10) })
        }, full(top = 10))
        card.addView(text("Works with this app's memory export or backup, a ChatGPT data export (.zip), or a text file with one memory per line.",
            13f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }, full(top = 10))
        importsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(importsBox, full(top = 6))
    }

    private fun refreshImports() {
        importsBox.removeAllViews()
        val history = runCatching {
            val a = org.json.JSONArray(prefs.importsJson)
            List(a.length()) { a.getJSONObject(it) }
        }.getOrDefault(emptyList())
        if (history.isEmpty()) return
        importsBox.addView(label("Import history"), full(top = 10))
        val time = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
        history.takeLast(5).reversed().forEach { o ->
            importsBox.addView(text("${time.format(Date(o.optLong("at")))} · ${o.optString("source")} · ${o.optInt("added")} added",
                13.5f, Fonts.REGULAR, Palette.CREAM), full(top = 4))
        }
    }

    private fun exportMemories() {
        if (prefs.memories.isEmpty()) { toast("There are no memories to export yet."); return }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date())
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${prefs.assistantName.lowercase().replace(' ', '-')}-memories-$day.json")
        }, REQUEST_EXPORT_MEMORIES)
    }

    private fun pasteMemories() {
        textDialog("Paste memories", "", "Paste here…",
            helper = "One memory per line. To copy them from ChatGPT: Settings → Personalization → Memory.", lines = 7, saveLabel = "Find memories") { value ->
            val found = MemoryImport.lines(value)
            if (found.isEmpty()) toast("No memories found. Put each one on its own line.")
            else reviewImport(MemoryImport.Found("Pasted text", found))
        }
    }

    /** Shows what a file held and lets the user choose what to add; nothing is saved until they do. */
    private fun reviewImport(found: MemoryImport.Found) {
        val review = MemoryImport.review(prefs.memories, found.memories)
        val room = Prefs.MAX_MEMORIES - prefs.memories.size
        if (review.fresh.isEmpty()) {
            val message = found.note ?: if (review.duplicates > 0) "All ${review.duplicates} memories in it are already saved." else "No memories were found in it."
            val dialog = PicoDialog(this).title(found.source).message(message).secondary(null).primary("OK") {}
            if (found.note != null) dialog.extra("Paste memories") { pasteMemories() }
            dialog.show()
            return
        }
        if (room <= 0) { toast("Memory is full (${Prefs.MAX_MEMORIES}). Delete some, then import again.", Toast.LENGTH_LONG); return }
        val offered = review.fresh.take(room)
        val chosen = BooleanArray(offered.size) { true }
        val notes = buildList {
            add("Found ${found.memories.size} in the ${found.source.lowercase()}.")
            if (review.duplicates > 0) add("${review.duplicates} already saved, so they're left out.")
            if (review.fresh.size > room) add("Only $room more fit, so the first $room are shown.")
            add("Untick any you don't want.")
        }.joinToString(" ")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(text(notes, 14f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }, full())
        offered.forEachIndexed { i, m ->
            list.addView(android.widget.CheckBox(this).apply {
                text = m; isChecked = true; textSize = 15f
                typeface = Fonts.get(this@MainActivity, Fonts.REGULAR); setTextColor(Palette.CREAM)
                buttonTintList = ColorStateList.valueOf(Palette.AMBER)
                minHeight = dpi(48)
                setOnCheckedChangeListener { _, on -> chosen[i] = on }
            }, full(top = 4))
        }
        PicoDialog(this).title("Add ${offered.size} memories?").content(list)
            .primary("Add") {
                val picked = offered.filterIndexed { i, _ -> chosen[i] }
                if (picked.isEmpty()) return@primary
                prefs.memories = prefs.memories + picked
                prefs.importsJson = org.json.JSONArray(prefs.importsJson).put(org.json.JSONObject()
                    .put("at", System.currentTimeMillis()).put("source", found.source).put("added", picked.size)).toString()
                refreshMemory()
                toast("Added ${picked.size} memories")
            }
            .show()
    }

    private fun editMemory(fact: String?) {
        textDialog(if (fact == null) "Add a memory" else "Change memory", fact.orEmpty(), "For example, my office is at Tidel Park") { value ->
            val list = prefs.memories
            prefs.memories = if (fact == null) list + value else list.map { if (it == fact) value else it }
            refreshMemory()
        }
    }

    private fun editRoutine(name: String, steps: String) {
        textDialog("“$name”", steps, "What should happen, step by step") { value ->
            prefs.routines = prefs.routines + (name to value)
            refreshMemory()
        }
    }

    // ---------- Colour ----------

    private fun buildLook() {
        val card = card()
        header(card, "Colour", "Pick the one that feels like you. It's used here and on the ghost that floats over your apps.")
        card.addView(swatchGrid(GhostTheme.of(prefs.ghostTheme), sameAs = null) { theme ->
            if (theme != null) { prefs.ghostTheme = theme.name; dial.theme = theme; paintGlow(theme) }
        }, full(top = 6))
    }

    /**
     * Twenty colours as swatches, with [selected] marked. [sameAs], when given, adds a first choice meaning "the main
     * assistant's colour" (null). [onPick] runs straight away, so the choice shows at once.
     */
    internal fun swatchGrid(selected: GhostTheme?, sameAs: String?, onPick: (GhostTheme?) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        var current = selected
        val name = text("", 14f, Fonts.MEDIUM, Palette.CREAM)
        val swatches = HashMap<GhostTheme, View>()
        var same: TextView? = null
        fun mark() {
            name.text = current?.title ?: sameAs.orEmpty()
            for ((theme, view) in swatches) {
                view.background = oval(theme.swatch).apply {
                    setStroke(dpi(if (theme == current) 3 else 2), if (theme == current) Palette.CREAM else Palette.alpha(Color.WHITE, 90))
                }
                (view as TextView).text = if (theme == current) "✓" else ""
                view.setTextColor(if (Color.luminance(theme.swatch) > 0.35f) Palette.INK else Color.WHITE)
                view.contentDescription = theme.title + if (theme == current) ", selected" else ""
            }
            same?.setTextColor(if (current == null) Palette.AMBER else Palette.MUTED)
        }
        if (sameAs != null) {
            same = button(sameAs, ButtonStyle.OUTLINE) { current = null; onPick(null); mark() }
            addView(same, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { bottomMargin = dpi(6) })
        }
        GhostTheme.entries.chunked(5).forEach { row ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { theme ->
                    val dot = TextView(this@MainActivity).apply {
                        gravity = Gravity.CENTER
                        textSize = 20f
                        typeface = Fonts.get(this@MainActivity, Fonts.BOLD)
                        setOnClickListener { current = theme; onPick(theme); mark() }
                    }
                    swatches[theme] = dot
                    addView(FrameLayout(this@MainActivity).apply {
                        addView(dot, FrameLayout.LayoutParams(dpi(44), dpi(44), Gravity.CENTER))
                    }, LinearLayout.LayoutParams(0, dpi(56), 1f))
                }
            }, full(top = 6))
        }
        addView(name, full(top = 8))
        mark()
    }

    // ---------- My profile ----------

    private fun buildProfile() {
        val card = card()
        header(card, "My profile", "${me.name} uses these to make answers personal. They stay on this phone and go to Claude only as part of a conversation.")
        card.addView(label("Your name"), full(top = 14))
        nameField = input(prefs.userName, "Your name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        card.addView(nameField, full(top = 6))
        card.addView(label("City (optional)"), full(top = 14))
        cityField = input(prefs.city, "For example, Chennai", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        card.addView(cityField, full(top = 6))
        card.addView(label("Country code for phone numbers"), full(top = 14))
        country = input(prefs.countryCode, "91", InputType.TYPE_CLASS_PHONE).apply {
            // A list to pick from, not a number to type.
            isFocusable = false
            isCursorVisible = false
            setText(countryLabel(prefs.countryCode))
            setOnClickListener { pickCountry() }
        }
        card.addView(country, full(top = 6))
        card.addView(button("Save profile", ButtonStyle.OUTLINE) { saveProfile(quiet = false) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(16) })
    }

    private fun saveProfile(quiet: Boolean) {
        val name = nameField.text.toString().trim().replace(Regex("\\s+"), " ").take(30)
        val city = cityField.text.toString().trim().take(40)
        val code = country.text.toString().substringAfter("+").filter { it.isDigit() }.ifBlank { "91" }
        val changed = name != prefs.userName || city != prefs.city || code != prefs.countryCode
        prefs.userName = name
        prefs.city = city
        prefs.countryCode = code
        if (changed) reloadService() // fresh greetings with the new name
        if (!quiet) { hideKeyboard(nameField); toast("Profile saved") }
    }

    // ---------- Backup ----------

    private fun buildBackup() {
        val card = card()
        header(card, "Backup", "Save your profile, ${me.name}'s settings, memories, routines and agents to a file, and restore them later " +
            "or on a new phone. Your API keys are never included.")
        backupStatus = text("", 13.5f, Fonts.MEDIUM, Palette.MUTED)
        card.addView(backupStatus, full(top = 10))
        refreshBackup()
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Save a backup", ButtonStyle.FILLED) { startExport() }, LinearLayout.LayoutParams(0, dpi(48), 1f))
            addView(button("Restore", ButtonStyle.OUTLINE) { startRestore() },
                LinearLayout.LayoutParams(0, dpi(48), 1f).apply { marginStart = dpi(10) })
        }, full(top = 14))
    }

    private fun refreshBackup() {
        val at = prefs.lastBackupAt
        backupStatus.text = if (at == 0L) "No backup yet" else "Last backup: " + SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(Date(at))
    }

    private fun startExport() {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date())
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "${prefs.assistantName.lowercase().replace(' ', '-')}-backup-$day.json")
        }, REQUEST_EXPORT)
    }

    /** Opens the file picker to restore a backup (also from the first-time setup). */
    internal fun startRestore() = pickFile(REQUEST_RESTORE)

    private fun pickFile(request: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // many file managers don't label .json files as JSON
        }, request)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREENING) { refreshAway(); return }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_EXPORT -> scope.launch {
                val now = System.currentTimeMillis()
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.encode(Backup.read(prefs), now).toByteArray()) }
                    }.isSuccess
                }
                if (ok) { prefs.lastBackupAt = now; refreshBackup(); toast("Backup saved") }
                else toast("Couldn't save the backup there. Try another folder.")
            }
            REQUEST_EXPORT_MEMORIES -> scope.launch {
                val text = MemoryImport.export(prefs.memories, System.currentTimeMillis())
                val ok = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }.isSuccess
                }
                toast(if (ok) "Exported ${prefs.memories.size} memories" else "Couldn't save the file there. Try another folder.")
            }
            REQUEST_IMPORT_MEMORIES -> scope.launch {
                toast("Reading the file…")
                val result = withContext(Dispatchers.IO) {
                    runCatching { MemoryImport.read(contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
                }
                result.onSuccess { reviewImport(it) }.onFailure {
                    PicoLog.w("Memory import failed", it)
                    toast(if (it is IllegalArgumentException) it.message ?: "That file can't be imported." else
                        "Couldn't read that file. It may be too large or damaged.", Toast.LENGTH_LONG)
                }
            }
            REQUEST_RESTORE -> scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { Backup.decode(contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }) }
                }
                val backup = result.getOrElse {
                    toast(if (it is IllegalArgumentException) it.message ?: "That file can't be restored." else "Couldn't open that file.", Toast.LENGTH_LONG)
                    return@launch
                }
                confirm("Restore this backup?",
                    "It has ${backup.memories.size} memories, ${backup.routines.size} routines and ${backup.agents.size} agents. " +
                        "They're added to what's here, and its settings replace yours. Your keys stay as they are.", "Restore", destructive = false) {
                    Backup.restore(prefs, backup)
                    prefs.welcomed = true // a restored setup needs no questions
                    reloadService()
                    toast("Backup restored")
                    recreate()
                }
            }
        }
    }

    // ---------- Privacy & security ----------

    private fun buildPrivacy() {
        val card = card()
        header(card, "Privacy & security", null)
        card.addView(text("On this phone", 15.5f, Fonts.MEDIUM, Palette.CREAM), full(top = 14))
        card.addView(text("Your profile, memories, routines, agents, reminders, conversations and settings. Keys are encrypted.",
            13.5f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }, full(top = 2))
        card.addView(text("When you talk", 15.5f, Fonts.MEDIUM, Palette.CREAM), full(top = 16))
        listOf(
            "🎙" to "Your voice → Sarvam, to turn it into text",
            "✳" to "Your words → Claude, with your name, city and memories, to think",
            "🔊" to "The reply → Sarvam, to turn it into ${me.their} voice",
        ).forEach { (icon, line) ->
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(icon, 16f, Fonts.REGULAR, Palette.CREAM).apply { gravity = Gravity.CENTER; background = oval(Palette.SURFACE_HI) },
                    LinearLayout.LayoutParams(dpi(34), dpi(34)).apply { marginEnd = dpi(12) })
                addView(text(line, 14f, Fonts.REGULAR, Palette.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, full(top = 8))
        }
        card.addView(text("While waiting", 15.5f, Fonts.MEDIUM, Palette.CREAM), full(top = 16))
        card.addView(text("Listening for “${WakeWord.display(prefs.wakePhrase)}” happens offline on the phone. Nothing is sent until you wake ${me.them}.",
            13.5f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }, full(top = 2))
        card.addView(text("Before acting", 15.5f, Fonts.MEDIUM, Palette.CREAM), full(top = 16))
        card.addView(text("With Ask before sending on (Assistant → Behaviour), ${me.they} ${me.verb("checks", "check")} with you before messages and calls. " +
            "Agents only use the abilities you give them.", 13.5f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }, full(top = 2))
    }

    /** Last on the page and set apart: the one thing here that can't be undone. */
    private fun buildDangerZone() {
        val card = card()
        (card.background as? GradientDrawable)?.setStroke(dpi(1), Palette.alpha(PicoDialog.RED, 120))
        header(card, "Danger zone", "Removes your details from this phone for good. Save a backup first if you might want them back.")
        card.addView(button("Delete all my data", ButtonStyle.OUTLINE) { deleteAllData() }.apply { setTextColor(PicoDialog.RED) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(14) })
    }

    private fun deleteAllData() {
        val counts = listOf(prefs.memories.size to "memor" , prefs.routines.size to "routine", Agents.all(prefs).size to "agent")
            .joinToString(", ") { (n, w) -> if (w == "memor") "$n memor${if (n == 1) "y" else "ies"}" else "$n $w${if (n == 1) "" else "s"}" }
        PicoDialog(this).title("Delete all your data?")
            .message("This removes your name and city, $counts, reminders, conversations and ${me.name}'s logs from this phone. " +
                "Your settings and API keys stay so the app keeps working. This can't be undone.")
            .extra("Back up first") { startExport() }
            .primary("Delete everything", PicoDialog.Kind.DESTRUCTIVE) {
            Reminders.all(prefs).forEach { Reminders.cancel(this, prefs, it.text) }
            Watches.active(prefs).forEach { Watches.remove(prefs, it) }
            prefs.memories = emptyList()
            prefs.routines = emptyMap()
            Recent.clear(prefs)
            prefs.importsJson = "[]"
            Agents.save(prefs, emptyList())
            prefs.userName = ""
            prefs.city = ""
            PicoLog.clear()
            nameField.setText("")
            cityField.setText("")
            reloadService()
            refreshReminders()
            refreshRecent()
            refreshAgents()
            refreshMemory()
            toast("Your data was deleted")
        }.show()
    }

    // ---------- Keys ----------

    private fun buildKeys() {
        keysCard = card()
        header(keysCard, "Keys", "They connect ${me.name}'s brain (Claude) and voice (Sarvam). Stored encrypted on this phone, never put in backups.")
        claudeKey = input(prefs.claudeKey, "sk-ant-…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sarvamKey = input(prefs.sarvamKey, "sk_…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        keyField("Claude key (brain)", claudeKey, "https://console.anthropic.com/settings/keys") { key ->
            withContext(Dispatchers.IO) { testClaudeKey(key) }
        }
        keyField("Sarvam key (voice)", sarvamKey, "https://dashboard.sarvam.ai/") { key -> key.length > 10 }
        keysCard.addView(button("Save keys", ButtonStyle.OUTLINE) {
            saveKeys()
            toast("Keys saved")
            reloadService()
            refreshSetup()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(16) })
    }

    /** One key: the box with show/hide, Paste, Test (✓ Working / ✕ Not working) and a link to get one. */
    private fun keyField(title: String, field: EditText, getKeyUrl: String, test: suspend (String) -> Boolean) {
        keysCard.addView(label(title), full(top = 14))
        field.setSingleLine(true)
        // setSingleLine swaps the box's text transform, which un-hid the key: hide it again, every time.
        field.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        field.setPadding(dpi(16), dpi(12), dpi(56), dpi(12))
        var shown = false
        val eye = text("👁", 18f, Fonts.REGULAR, Palette.CREAM).apply {
            gravity = Gravity.CENTER
            contentDescription = "Show $title"
            background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)), null, oval(Color.WHITE))
            isClickable = true
            setOnClickListener {
                shown = !shown
                field.transformationMethod = if (shown) null else android.text.method.PasswordTransformationMethod.getInstance()
                field.setSelection(field.text.length)
                contentDescription = (if (shown) "Hide " else "Show ") + title
            }
        }
        keysCard.addView(FrameLayout(this).apply {
            addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(eye, FrameLayout.LayoutParams(dpi(48), dpi(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dpi(4) })
        }, full(top = 6))
        val result = text("", 13.5f, Fonts.MEDIUM, Palette.MUTED)
        keysCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("Paste", ButtonStyle.OUTLINE) {
                getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { field.setText(it); result.text = "" } ?: toast("Nothing to paste")
            }.apply { textSize = 14f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(40)))
            addView(button("Test key", ButtonStyle.OUTLINE) {
                val key = field.text.toString().trim()
                if (key.isEmpty()) { result.text = "✕ Add a key first"; result.setTextColor(Palette.CORAL); return@button }
                result.text = "Testing…"; result.setTextColor(Palette.MUTED)
                scope.launch {
                    val ok = test(key)
                    result.text = if (ok) "✓ Working" else "✕ Not working"
                    result.setTextColor(if (ok) Palette.TEAL else Palette.CORAL)
                }
            }.apply { textSize = 14f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(40)).apply { marginStart = dpi(8) })
            addView(result, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(10) })
        }, full(top = 8))
        keysCard.addView(button("How do I get a key? ›", ButtonStyle.TEXT) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getKeyUrl))) }
        }.apply { textSize = 14f; gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(0, 0, dpi(8), 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(44)))
    }

    // ---------- Permissions ----------

    private fun buildPermissions() {
        permsCard = card()
        header(permsCard, "Permissions", "${me.name} needs these to act for you. Anything missing shows a Turn on button.")
        permsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permsCard.addView(permsBox, full(top = 6))
    }

    private fun permissionRows(): List<PermissionRow> {
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val askRuntime: () -> Unit = { requestPermissions(runtimePermissions, 1) }
        return listOf(
            PermissionRow("Microphone", "To hear you", has(Manifest.permission.RECORD_AUDIO), fix = askRuntime),
            PermissionRow("Contacts", "To find who to message or call", has(Manifest.permission.READ_CONTACTS), fix = askRuntime),
            PermissionRow("Phone calls", "To call people for you", has(Manifest.permission.CALL_PHONE), fix = askRuntime),
            PermissionRow("SMS", "To send text messages", has(Manifest.permission.SEND_SMS), fix = askRuntime),
            PermissionRow("Calendar", "To read your day and add events",
                has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR), fix = askRuntime),
            PermissionRow("Location (optional)", "Your city, for weather and places near you. Never your exact spot",
                has(Manifest.permission.ACCESS_COARSE_LOCATION), optional = true, fix = askRuntime),
            PermissionRow("Notifications", "For reminders and ${me.their} status",
                Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS), fix = askRuntime),
            PermissionRow("Display over other apps", "The little ghost at the bottom of the screen", Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            PermissionRow("Accessibility", "To tap, type and scroll in your apps", accessibilityOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            PermissionRow("Notification access", "To read messages, reply and alert you", notificationAccessOn()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            PermissionRow("Do Not Disturb access", "To switch silent, vibrate and Do Not Disturb",
                getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            },
            PermissionRow("Change brightness", "To make the screen brighter or dimmer", Settings.System.canWrite(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            },
            PermissionRow("Run in the background", "So ${me.they} can hear the wake word", batteryUnrestricted()) {
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
                if (row.on) addView(text("✓ Allowed", 13.5f, Fonts.MEDIUM, Palette.MUTED), wrap().apply { marginStart = dpi(8) })
                else {
                    addView(button("Turn on", ButtonStyle.FILLED) { row.fix() }.apply { textSize = 14f; minimumHeight = dpi(40) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(40)).apply { marginStart = dpi(8) })
                }
            }, full())
        }
        // Android's "Restricted setting" block only hits these two; the way round it shows only when one is missing.
        val restricted = permissionRows().any { !it.on && (it.label == "Accessibility" || it.label == "Notification access") }
        if (restricted) permsBox.addView(text(
            "If Android says Restricted setting: open App info for this app, tap ⋮, choose Allow restricted settings, then try again.",
            13f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }, full(top = 10))
    }

    private fun buildHelp() {
        val card = card()
        header(card, "Help & tips", null)
        listOf(
            "Talk" to "Say “${WakeWord.display(prefs.wakePhrase)}”, or tap Talk now on Home. Tap the ghost to stop ${me.them}; drag it down to close.",
            "Type" to "Type a request on Home: ${me.name} answers out loud, just like when you speak.",
            "Show" to "Tap the camera button beside the ghost, point at something and ask “what is this?”.",
            "Agents" to "Make helpers in the Agents tab, then say “talk to Chef”.",
        ).forEach { (title, detail) ->
            card.addView(text(title, 15.5f, Fonts.MEDIUM, Palette.CREAM), full(top = 14))
            card.addView(text(detail, 13.5f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }, full(top = 2))
        }
        card.addView(button("Troubleshooting", ButtonStyle.OUTLINE) { showDiagnostics() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(16) })
        card.addView(button("Run setup again", ButtonStyle.OUTLINE) { prefs.welcomed = false; recreate() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(8) })
    }

    private fun buildAbout() {
        val card = card()
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        header(card, "About Pico", "Version $version · Your keys and details stay on this phone; see Privacy & security above.")
    }

    /**
     * A plain checklist of what the assistant needs, each with a Fix. The technical log is only for copying to
     * someone who helps, with what was said hidden (it holds private chats).
     */
    private fun showDiagnostics() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: android.app.Dialog? = null
        fun row(ok: Boolean?, title: String, fix: (() -> Unit)?): TextView {
            val status = text(when (ok) { true -> "✓"; false -> "✕"; null -> "…" }, 18f, Fonts.BOLD,
                when (ok) { true -> Palette.TEAL; false -> Palette.CORAL; null -> Palette.MUTED })
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(48)
                addView(status, LinearLayout.LayoutParams(dpi(30), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(text(title, 15.5f, Fonts.REGULAR, Palette.CREAM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (ok == false && fix != null) addView(button("Fix", ButtonStyle.OUTLINE) { dialog?.dismiss(); fix() }.apply { textSize = 14f },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(40)))
            }, full())
            return status
        }
        row(MissMinutesService.running, "${me.name} is on and listening") { selectTab(Tab.HOME); toggleRunning() }
        val claude = row(if (prefs.claudeKey.isBlank()) false else null,
            if (prefs.claudeKey.isBlank()) "Claude key added" else "Claude key works") { goTo(Tab.SETTINGS, keysCard) }
        row(prefs.sarvamKey.isNotBlank(), "Sarvam key added (voice)") { goTo(Tab.SETTINGS, keysCard) }
        permissionRows().filter { !it.optional }.forEach { p -> row(p.on, p.label, p.fix) }
        if (prefs.claudeKey.isNotBlank()) scope.launch {
            val works = withContext(Dispatchers.IO) { testClaudeKey(prefs.claudeKey) }
            claude.text = if (works) "✓" else "✕"
            claude.setTextColor(if (works) Palette.TEAL else Palette.CORAL)
        }
        dialog = PicoDialog(this).title("Troubleshooting").content(box)
            .extra("Copy technical details") {
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Pico technical details", technicalDetails()))
                toast("Copied, with your messages hidden")
            }
            .secondary(null).primary("Close") {}
            .show()
    }

    /** The recent log for whoever helps, with everything said or written replaced by [hidden]. */
    private fun technicalDetails(): String {
        val lines = runCatching { java.io.File(filesDir, "pico.log").readLines().takeLast(120) }.getOrDefault(emptyList())
        val private = Regex("(Heard|Said|Interrupted while saying|Ignored her own echo|Cloud: [A-Z]+|Tool [a-z_]+ ->|Shop search answer|Chat for me: [a-z' ]+?:|Away: [^\\n]*?from)\\s.*")
        return "Version ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()}, " +
            "model ${prefs.model}, running ${MissMinutesService.running}\n\n" +
            lines.joinToString("\n") { line -> private.find(line)?.let { m -> line.substring(0, m.range.first) + m.groupValues[1] + " [hidden]" } ?: line }
    }

    private fun testClaudeKey(key: String): Boolean = runCatching {
        val req = okhttp3.Request.Builder().url("https://api.anthropic.com/v1/models?limit=1")
            .header("x-api-key", key).header("anthropic-version", "2023-06-01").build()
        okhttp3.OkHttpClient().newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

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

    /** Starts a conversation now, with the main assistant or with [agent]. */
    private fun talkNow(agent: Agent? = null) {
        saveKeys()
        if (!hasMic()) { requestPermissions(runtimePermissions, 1); return }
        if (prefs.claudeKey.isBlank()) { toast("Add your Claude key in Settings → Keys first"); goTo(Tab.SETTINGS, keysCard); return }
        startForegroundService(MissMinutesService.intent(this, MissMinutesService.ACTION_TALK).apply {
            agent?.let { putExtra(MissMinutesService.EXTRA_AGENT, it.id) }
        })
        heroTitle.postDelayed({ refreshStatus(); refreshSetup() }, 700)
    }

    private fun reloadService() {
        if (MissMinutesService.running) startService(MissMinutesService.intent(this, MissMinutesService.ACTION_RELOAD))
    }

    private fun saveKeys() {
        prefs.claudeKey = claudeKey.text.toString()
        prefs.sarvamKey = sarvamKey.text.toString()
    }

    /** Plays [line] in the assistant's voice, or in [voice] for a preview. Without a Sarvam key it says how to add one. */
    private fun playSample(line: String, cache: Boolean, voice: String? = null) {
        saveKeys()
        if (prefs.sarvamKey.isBlank()) {
            toast("Add your Sarvam key in Settings → Keys to hear the voices. You can still pick one now.", Toast.LENGTH_LONG)
            return
        }
        testPlayer?.stop()
        scope.launch {
            try {
                val sarvam = testSarvam ?: SarvamClient(prefs).also { testSarvam = it }
                val player = testPlayer ?: VoicePlayer(this@MainActivity, sarvam).also { testPlayer = it }
                sarvam.speakerOverride = voice
                dial.state = OrbState.SPEAKING
                player.onLevel = { dial.setLevel(it) }
                dial.start(withIntro = false)
                player.speak(line, fallback = false, cache = cache)
            } catch (e: Exception) {
                PicoLog.w("Voice preview failed", e)
                toast("Couldn't play that voice. Check your internet connection and Sarvam key.", Toast.LENGTH_LONG)
            } finally {
                refreshStatus()
            }
        }
    }

    // ---------- Refresh ----------

    private fun refreshStatus() {
        val on = MissMinutesService.running
        val phrase = WakeWord.display(prefs.wakePhrase)
        statusChip.text = if (on) "●  Listening" else "○  Asleep"
        statusChip.setTextColor(if (on) Palette.AMBER else Palette.MUTED)
        statusChip.background = surface(radius = 99f, color = Palette.alpha(if (on) Palette.AMBER else Palette.MUTED, 30))
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (hour) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..23 -> "Good evening"; else -> "Hi" }
        val first = prefs.userName.trim().split(' ').first()
        greeting.text = (if (first.isEmpty()) "$part!" else "$part, $first!") + " How can ${me.name} help?"
        heroTitle.text = if (on) "Listening for “$phrase”" else "${me.name} is resting. Turn on $phrase, or talk now."
        startButton.text = if (on) "Turn off $phrase" else "Turn on $phrase"
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

    // ---------- Building blocks (also used by the setup and agent flows) ----------

    internal enum class ButtonStyle { FILLED, OUTLINE, TEXT }

    internal fun text(value: String, size: Float, weight: Int, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        typeface = Fonts.get(this@MainActivity, weight)
        setTextColor(color)
    }

    internal fun label(value: String) = text(value, 14f, Fonts.MEDIUM, Palette.MUTED)

    private fun empty(value: String) = text(value, 14.5f, Fonts.REGULAR, Palette.MUTED).apply {
        setLineSpacing(0f, 1.2f)
        setPadding(0, dpi(6), 0, dpi(6))
    }

    /** A line with a small coloured dot in front. */
    internal fun bullet(value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(View(this@MainActivity).apply { background = oval(Palette.AMBER) },
            LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { topMargin = dpi(8); marginEnd = dpi(12) })
        addView(text(value, 15f, Fonts.REGULAR, Palette.CREAM).apply { setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    internal fun button(label: String, style: ButtonStyle, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        typeface = Fonts.get(this@MainActivity, Fonts.SEMIBOLD)
        setTextColor(if (style == ButtonStyle.FILLED) Palette.INK else Palette.CREAM)
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

    /** A selectable row with a round mark, a title, an optional detail and an optional view at the end. */
    internal fun radioRow(title: String, detail: String?, selected: Boolean, trailing: View? = null, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14), dpi(if (detail == null) 6 else 12), dpi(if (trailing == null) 14 else 4), dpi(if (detail == null) 6 else 12))
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
                addView(text(title, 16f, Fonts.MEDIUM, Palette.CREAM))
                if (detail != null) addView(text(detail, 13.5f, Fonts.REGULAR, Palette.MUTED), wrap(top = 1))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(44)))
            isClickable = true
            contentDescription = title + (detail?.let { ". $it" } ?: "") + if (selected) ". Selected" else ""
            setOnClickListener { onClick() }
        }

    internal fun toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Palette.AMBER, Palette.MUTED))
            // The off track must show against the card (it used to be almost invisible).
            trackTintList = ColorStateList(states, intArrayOf(Palette.alpha(Palette.AMBER, 110), Palette.TRACK_OFF))
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

    private fun listRow(title: String, detail: String?, action: String, onTap: (() -> Unit)? = null, onAction: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(52)
            setPadding(0, dpi(6), 0, dpi(6))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15.5f, Fonts.REGULAR, Palette.CREAM).apply {
                    setLineSpacing(0f, 1.15f)
                    if (onTap != null) { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END } // tap shows it all
                })
                if (detail != null) addView(text(detail, 13.5f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.15f) }, wrap(top = 2))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(8) })
            addView(button(action, ButtonStyle.TEXT, onAction).apply {
                textSize = 14f
                setTextColor(Palette.MUTED)
                setPadding(dpi(14), 0, dpi(14), 0)
                contentDescription = "$action: $title"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48)))
            if (onTap != null) {
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 24)), null, ColorDrawable(Color.WHITE))
                setOnClickListener { onTap() }
            }
        }

    internal fun input(value: String, hintText: String, type: Int) = EditText(this).apply {
        setText(value)
        setSingleLine()
        textSize = 16f
        typeface = Fonts.get(this@MainActivity, Fonts.REGULAR)
        setTextColor(Palette.CREAM)
        setHintTextColor(Palette.HINT)
        hint = hintText
        inputType = type
        minHeight = dpi(52)
        setPadding(dpi(16), dpi(12), dpi(16), dpi(12))
        background = surface(radius = 14f, color = Palette.SURFACE_HI).apply {
            setStroke(dpi(1), Palette.FIELD_EDGE)
        }
        setOnFocusChangeListener { _, focused ->
            (background as? GradientDrawable)?.setStroke(dpi(if (focused) 2 else 1),
                if (focused) Palette.AMBER else Palette.FIELD_EDGE)
        }
    }

    /** A text box for a sentence or two. */
    internal fun area(value: String, hintText: String) =
        input(value, hintText, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            isSingleLine = false
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
        }

    private val countries = listOf("🇮🇳 India" to "91", "🇺🇸 United States" to "1", "🇬🇧 United Kingdom" to "44",
        "🇦🇪 UAE" to "971", "🇸🇬 Singapore" to "65", "🇲🇾 Malaysia" to "60", "🇱🇰 Sri Lanka" to "94", "🇨🇦 Canada" to "1",
        "🇦🇺 Australia" to "61", "🇸🇦 Saudi Arabia" to "966", "🇶🇦 Qatar" to "974", "🇩🇪 Germany" to "49")

    private fun countryLabel(code: String) = countries.firstOrNull { it.second == code }?.let { "${it.first} +${it.second}" } ?: "+$code"

    private fun pickCountry() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: android.app.Dialog? = null
        countries.forEach { (name, code) ->
            box.addView(radioRow(name, "+$code", prefs.countryCode == code) {
                prefs.countryCode = code
                country.setText(countryLabel(code))
                dialog?.dismiss()
                toast("Saved")
            }, full(top = 2))
        }
        dialog = PicoDialog(this).title("Country for phone numbers").content(box).show()
    }

    /** Small grey labels in a row ("Web search", "Reminders"). */
    private fun chips(labels: List<String>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        labels.take(4).forEachIndexed { i, l ->
            addView(text(l, 12f, Fonts.MEDIUM, Palette.MUTED).apply {
                setPadding(dpi(8), dpi(3), dpi(8), dpi(3))
                maxLines = 1
                background = surface(radius = 99f, color = Palette.SURFACE_HI).apply { setStroke(dpi(1), Palette.alpha(Palette.CREAM, 30)) }
            }, wrap().apply { if (i > 0) marginStart = dpi(6) })
        }
    }

    /** A ⋮ button for the less common actions, 48dp to tap. */
    private fun moreButton(description: String, onClick: () -> Unit) = text("⋮", 22f, Fonts.BOLD, Palette.CREAM).apply {
        gravity = Gravity.CENTER
        contentDescription = description
        background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)), null, oval(Color.WHITE))
        isClickable = true
        setOnClickListener { onClick() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(dpi(48), dpi(48)) }

    /** A short list of actions in the app's dialog style; "Delete" and "Clear" show in red. */
    private fun actionSheet(title: String, actions: List<Pair<String, () -> Unit>>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: android.app.Dialog? = null
        actions.forEach { (label, act) ->
            box.addView(text(label, 17f, Fonts.MEDIUM,
                if (label.startsWith("Delete") || label.startsWith("Clear")) PicoDialog.RED else Palette.CREAM).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dpi(52)
                setPadding(dpi(4), 0, dpi(4), 0)
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 30)), null, ColorDrawable(Color.WHITE))
                isClickable = true
                setOnClickListener { dialog?.dismiss(); act() }
            }, full())
        }
        dialog = PicoDialog(this).title(title).content(box).secondary("Cancel").show()
    }

    /** A row with a trash icon instead of a word: tap the row to change it. */
    private fun trashRow(title: String, onTap: () -> Unit, onDelete: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpi(52)
        setPadding(0, dpi(6), 0, dpi(6))
        background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 24)), null, ColorDrawable(Color.WHITE))
        setOnClickListener { onTap() }
        addView(text(title, 15.5f, Fonts.REGULAR, Palette.CREAM).apply {
            setLineSpacing(0f, 1.15f); maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(8) })
        addView(text("🗑", 18f, Fonts.REGULAR, Palette.MUTED).apply {
            gravity = Gravity.CENTER
            contentDescription = "Delete: $title"
            background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 40)), null, oval(Color.WHITE))
            isClickable = true
            setOnClickListener { onDelete() }
        }, LinearLayout.LayoutParams(dpi(48), dpi(48)))
    }

    /** A bar at the bottom for a few seconds: "Memory deleted · Undo". */
    private fun undoBar(message: String, undo: () -> Unit) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(18), dpi(6), dpi(8), dpi(6))
            background = surface(radius = 16f, color = PicoDialog.SURFACE).apply { setStroke(dpi(1), Palette.alpha(Palette.CREAM, 30)) }
            elevation = dp(8f)
            addView(text(message, 15f, Fonts.MEDIUM, Palette.CREAM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val remove = { (bar.parent as? ViewGroup)?.removeView(bar); Unit }
        bar.addView(button("Undo", ButtonStyle.TEXT) { undo(); remove() }.apply { setTextColor(Palette.AMBER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48)))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { setMargins(dpi(16), 0, dpi(16), dpi(96)) })
        bar.postDelayed(remove, 5000)
    }

    /** Asks before something that can't be undone; [onYes] runs only on the confirming button. */
    private fun confirm(title: String, message: String, yes: String, destructive: Boolean = true, onYes: () -> Unit) {
        PicoDialog(this).title(title).message(message)
            .primary(yes, if (destructive) PicoDialog.Kind.DESTRUCTIVE else PicoDialog.Kind.PRIMARY, onYes)
            .show()
    }

    /** A small editor for one piece of text; [onSave] gets the trimmed text, never empty. */
    private fun textDialog(title: String, value: String, hintText: String, helper: String? = null, lines: Int = 2,
                           saveLabel: String = "Save", onSave: (String) -> Unit) {
        val field = area(value, hintText).apply { minLines = lines; maxLines = maxOf(6, lines) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Instructions stay above the box (in the box they vanished as soon as something was pasted).
        helper?.let { box.addView(text(it, 14f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.2f) }, full()) }
        box.addView(field, full(top = if (helper != null) 10 else 0))
        PicoDialog(this).title(title).content(box).requires(field)
            .primary(saveLabel) { field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onSave) }
            .show()
        field.requestFocus()
    }

    internal fun hideKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    internal fun full(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dpi(top) }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dpi(top) }

    internal fun toast(msg: String, length: Int = Toast.LENGTH_SHORT) = Toast.makeText(this, msg, length).show()

    private companion object {
        const val REQUEST_EXPORT = 10
        const val REQUEST_RESTORE = 11
        const val REQUEST_IMPORT_MEMORIES = 12
        const val REQUEST_EXPORT_MEMORIES = 13
        const val REQUEST_SCREENING = 14
    }
}
