package com.missminutes.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.ValueAnimator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What you see when you wake Pico: the little ghost at the bottom of the screen, whose mood follows
 * what she's doing (listening, thinking, typing in your apps, speaking, happy when it worked, sad when it failed). Tap it to skip her speaking or stop; swipe it down to close.
 * When she asks you something, the cloud popup ([CloudPanel]) appears in the middle of the screen with Pico peeking
 * over it, her question, Yes / Cancel for confirmations, and a voice hint; it stays to show the outcome.
 */
class OverlayManager(private val context: Context, private val prefs: Prefs) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val motion = ValueAnimator.areAnimatorsEnabled()

    private var orb: FrameLayout? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var orbDisc: View? = null
    @Volatile private var dial: GhostView? = null
    private var edge: EdgeGlowView? = null
    private var cloud: CloudPanel? = null
    private var activity: String? = null

    var state = OrbState.IDLE
        private set
    /** The ghost's colour: the assistant's, or the agent's own while it talks. */
    var ghostTheme: GhostTheme = GhostTheme.of(prefs.ghostTheme)
        set(value) {
            field = value
            dial?.theme = value
            cloud?.ghost?.theme = value
        }
    /** The name on the popup and in screen-reader messages: the assistant's, or the agent talking now. */
    var speakerName: String = prefs.assistantName
    var onTap: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    /** A tap on the Pico Camera button beside the ghost. */
    var onCamera: (() -> Unit)? = null
    private var cameraButton: View? = null
    /** While the camera window is open the ghost sits in its corner, so the floating one and its button step aside. */
    var docked = false
        set(value) {
            field = value
            PicoLog.i("Ghost ${if (value) "moved into the camera window" else "back on screen"} (orb ${if (orb == null) "not shown" else "shown"})")
            val show = if (value) View.INVISIBLE else View.VISIBLE
            orb?.visibility = show
            cameraButton?.visibility = show
        }
    /** A Yes (true) or Cancel (false) tap on the confirmation panel. */
    var onAnswer: ((Boolean) -> Unit)? = null

    enum class PanelKind { CONFIRM, QUESTION, PROBLEM }

    fun canShow() = Settings.canDrawOverlays(context)

    private val orbSize get() = context.dpi(ORB_DP)
    /**
     * Distance from the bottom of the screen to the orb: its centre sits [ORB_CENTER] of the way down, clear of
     * the keyboard and the apps' own bottom bars (it used to hug the bottom edge, which felt too low).
     */
    private val orbLift: Int get() {
        // The whole screen, system bars included: displayMetrics leaves them out, which put the orb ~2% too high.
        val height = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height()
                     else context.resources.displayMetrics.heightPixels
        return ((height * (1f - ORB_CENTER)) - orbSize / 2f).toInt().coerceAtLeast(context.dpi(28))
    }

    // ---------- The orb ----------

    fun show() {
        if (orb != null || !canShow()) return
        if (prefs.edgeGlow) addEdgeGlow()

        val dialView = GhostView(context).apply { theme = ghostTheme }
        val disc = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.alpha(Palette.INK, 236))
                setStroke(context.dpi(1), Palette.alpha(Palette.CREAM, 26))
            }
            elevation = context.dp(6f)
        }
        val inset = context.dpi(14)
        val orbView = FrameLayout(context).apply {
            clipChildren = false
            addView(disc, FrameLayout.LayoutParams(orbSize - inset * 2, orbSize - inset * 2, Gravity.CENTER))
            addView(dialView, FrameLayout.LayoutParams(orbSize, orbSize, Gravity.CENTER))
            dialView.elevation = context.dp(7f) // keeps the dial drawn above the disc's shadow
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val params = overlayParams(orbSize, orbSize).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = orbLift
        }
        try {
            wm.addView(orbView, params)
        } catch (e: Exception) {
            removeEdgeGlow()
            return
        }
        orb = orbView; dial = dialView; orbParams = params; orbDisc = disc
        installTouch(orbView)
        dialView.start(withIntro = true)
        describe()
        addCameraButton()
        if (docked) { orbView.visibility = View.INVISIBLE; cameraButton?.visibility = View.INVISIBLE }

        if (motion) {
            orbView.alpha = 0f
            orbView.scaleX = 0.5f
            orbView.scaleY = 0.5f
            orbView.translationY = context.dp(24f)
            orbView.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setInterpolator(OvershootInterpolator(1.4f)).setDuration(340).start()
        }
    }

    fun setState(s: OrbState) {
        when (s) {
            OrbState.LISTENING -> PicoCamera.onCaption?.invoke("Listening…")
            OrbState.THINKING -> PicoCamera.onCaption?.invoke("Thinking…")
            else -> {}
        }
        if (s != OrbState.THINKING) activity = null
        state = s
        ghosts().forEach { it.state = s }
        fitOrb()
        cloud?.let { c ->
            when {
                // She answered without doing anything ("okay, I won't send it"): the cloud contracts away.
                s == OrbState.SPEAKING && c.phase == CloudPanel.Phase.PROCESSING -> hidePanel()
                s == OrbState.LISTENING && c.phase == CloudPanel.Phase.WAITING -> c.setPhase(CloudPanel.Phase.LISTENING)
                s == OrbState.SPEAKING && c.phase == CloudPanel.Phase.LISTENING -> c.setPhase(CloudPanel.Phase.WAITING)
            }
        }
        describe()
        edge?.let { glow ->
            val on = s == OrbState.THINKING
            if (on) glow.running = true
            glow.animate().alpha(if (on) 1f else 0f).setDuration(if (on) 240 else 180)
                .withEndAction { if (!on) glow.running = false }.start()
        }
    }

    /** Safe to call from any thread: it only stores a number the views read each frame. */
    fun setLevel(v: Float) {
        dial?.setLevel(v)
        cloud?.setLevel(v)
    }

    /** What she's doing right now ("Opening WhatsApp"), read out by TalkBack instead of shown on screen. */
    fun setActivity(label: String) {
        activity = label
        describe()
    }

    /**
     * While she's thinking, her thought cloud ("Hmm... Let's see...") shares the orb with her, so the orb grows
     * enough for its words to be read, keeping the same centre; it's back to normal size for everything else.
     */
    private fun fitOrb() {
        val view = orb ?: return
        val params = orbParams ?: return
        val ghost = dial ?: return
        val thinking = state == OrbState.THINKING && ghost.thinkingMood == Mood.THINKING && cloud == null
        val size = if (thinking) context.dpi(THINKING_DP) else orbSize
        // The dark disc frames her alone; with the thought cloud beside her it would sit off-centre, so it fades out.
        orbDisc?.animate()?.alpha(if (thinking) 0f else 1f)?.setDuration(160)?.start()
        if (params.width == size) return
        params.width = size
        params.height = size
        params.y = orbLift - (size - orbSize) / 2
        ghost.layoutParams = (ghost.layoutParams as FrameLayout.LayoutParams).apply { width = size; height = size }
        runCatching { wm.updateViewLayout(view, params) }
    }

    /** A tool started: her thinking face shows what kind of work it is. */
    fun toolStarted(name: String) {
        val mood = when (name) {
            "web_search", "get_weather", "daily_briefing", "whats_new", "list_alarms", "shop_search" -> Mood.SEARCHING
            in APP_TOOLS -> Mood.WORKING
            else -> Mood.THINKING
        }
        ghosts().forEach { it.thinkingMood = mood }
        fitOrb()
    }

    /** The feeling of the reply she's about to speak ([Mood.ofTag]); null is her normal speaking face. */
    fun setEmotion(mood: Mood?) {
        ghosts().forEach { it.emotion = mood }
    }

    /** A tool finished: a quick happy face when an action worked, a frown when it failed. Lookups stay quiet. */
    fun toolEnded(name: String, ok: Boolean) {
        // A confirmed action finishing shows its outcome in the cloud, which then closes by itself.
        cloud?.takeIf { it.phase == CloudPanel.Phase.PROCESSING }?.let { c ->
            c.setPhase(if (ok) CloudPanel.Phase.SUCCESS else CloudPanel.Phase.ERROR)
            c.postDelayed({ if (cloud === c) hidePanel() }, if (ok) 1400 else 2400)
        }
        when {
            !ok -> {
                dial?.flash(Mood.ERROR, 1800)
                orb?.announceForAccessibility("$speakerName needs your attention.")
            }
            name in MEDIA_TOOLS -> dial?.flash(Mood.PLAYING, 1800)
            name in ACTION_TOOLS -> {
                dial?.flash(Mood.SUCCESS, 1400)
                orb?.announceForAccessibility("$speakerName got it done!")
            }
        }
    }

    /** Shows a one-off mood for a moment (a reminder or alert arriving). */
    fun flash(mood: Mood, ms: Long = 1600) {
        ghosts().forEach { it.flash(mood, ms) }
    }

    private fun describe() {
        val view = orb ?: return
        val what = when (state) {
            OrbState.IDLE -> "ready"
            OrbState.LISTENING -> "listening"
            OrbState.THINKING -> activity?.let { "working on it: $it" } ?: "working on it"
            OrbState.SPEAKING -> "speaking"
        }
        val action = if (state == OrbState.SPEAKING) "Double-tap to skip" else "Double-tap to stop"
        view.contentDescription = "$speakerName, $what. $action, swipe down to close."
    }

    // ---------- The panel ----------

    /**
     * Shows her question (or a problem) in the cloud popup ([CloudPanel]), centred on screen. While it's up the
     * small orb steps aside: Pico is peeking over the cloud instead. Calling it again replaces the cloud.
     */
    fun showPanel(message: String, kind: PanelKind) {
        if (orb == null || !canShow()) return
        removeCloud()
        dial?.waitingAnswer = kind == PanelKind.CONFIRM // her waiting face while she listens for your yes

        val view = CloudPanel(context, ghostTheme, kind, message, speakerName,
            onAnswer = { yes -> onAnswer?.invoke(yes) },
            onGhostTap = { onTap?.invoke() })
        view.ghost.state = state
        // Compact, and where Pico already is: just above the orb's spot, with even side margins on any screen.
        val width = min(context.resources.displayMetrics.widthPixels - context.dpi(48), context.dpi(340))
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = orbLift
        }
        val added = runCatching { wm.addView(view, params) }
        if (added.isFailure) {
            PicoLog.w("Cloud popup couldn't be shown", added.exceptionOrNull())
            return
        }
        PicoLog.i("Cloud: $kind \"$message\"")
        cloud = view
        view.enter()
        orb?.let { o -> o.animate().cancel(); o.animate().alpha(0f).setDuration(160).withEndAction { o.visibility = View.INVISIBLE }.start() }
    }

    /**
     * You've answered. A confirmation stays up to show the outcome (working, then done or a problem); a plain
     * question just goes away.
     */
    fun answerHeard() {
        val c = cloud ?: return
        if (c.phase == CloudPanel.Phase.WAITING || c.phase == CloudPanel.Phase.LISTENING) {
            if (c.ghost.waitingAnswer) c.setPhase(CloudPanel.Phase.PROCESSING) else hidePanel()
        }
    }

    fun hidePanel(animated: Boolean = true) {
        dial?.waitingAnswer = false
        val view = cloud ?: return
        cloud = null
        val gone = { runCatching { wm.removeView(view) }; Unit }
        if (animated) view.dismiss(gone) else gone()
        orb?.let { o ->
            o.visibility = View.VISIBLE
            o.animate().cancel()
            if (motion && animated) o.animate().alpha(1f).setStartDelay(120).setDuration(200).start() else o.alpha = 1f
        }
    }

    private fun removeCloud() {
        val view = cloud ?: return
        cloud = null
        runCatching { wm.removeView(view) }
    }

    /** Both Picos: the orb's, and the one peeking over the cloud while it's up. */
    private fun ghosts() = listOfNotNull(dial, cloud?.ghost, PicoCamera.ghost)

    /** The round Pico Camera button just right of the ghost: a tap opens the camera window. */
    private fun addCameraButton() {
        if (cameraButton != null) return
        val size = context.dpi(48)
        val button = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_pico_camera)
            setPadding(context.dpi(9), context.dpi(9), context.dpi(9), context.dpi(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.alpha(Palette.INK, 236))
                setStroke(context.dpi(1), Palette.alpha(Palette.AMBER, 120))
            }
            elevation = context.dp(6f)
            contentDescription = "Pico Camera: show me something and ask about it"
            setOnClickListener { onCamera?.invoke() }
        }
        val params = overlayParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = orbSize / 2 + context.dpi(30) // to the right of the ghost
            y = orbLift + (orbSize - size) / 2
        }
        if (runCatching { wm.addView(button, params) }.isFailure) return
        cameraButton = button
        // The first few times, say once what the little button is for.
        if (prefs.cameraHintShown < 2) {
            prefs.cameraHintShown = prefs.cameraHintShown + 1
            android.widget.Toast.makeText(context, "Tip: tap the camera button to show ${prefs.assistantName} something", android.widget.Toast.LENGTH_LONG).show()
        }
        if (motion) {
            button.alpha = 0f; button.scaleX = 0.4f; button.scaleY = 0.4f
            button.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(220).setInterpolator(OvershootInterpolator(1.6f)).setDuration(300).start()
        }
    }

    // ---------- Hide everything ----------

    fun hide() {
        hidePanel()
        cameraButton?.let { b -> cameraButton = null; runCatching { wm.removeView(b) } }
        val view = orb
        orb = null
        orbParams = null
        orbDisc = null
        dial?.stop()
        dial = null
        activity = null
        removeEdgeGlow()
        if (view == null) return
        if (motion) {
            view.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).translationY(context.dp(16f))
                .setInterpolator(AccelerateInterpolator()).setDuration(180)
                .withEndAction { runCatching { wm.removeView(view) } }.start()
        } else {
            runCatching { wm.removeView(view) }
        }
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    // ---------- Edge glow window ----------

    private fun addEdgeGlow() {
        val glow = EdgeGlowView(context).apply { alpha = 0f }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Android 12+ only lets touches pass through overlays that are at most 80% opaque.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        if (runCatching { wm.addView(glow, params) }.isSuccess) edge = glow
    }

    private fun removeEdgeGlow() {
        val glow = edge ?: return
        edge = null
        glow.running = false
        runCatching { wm.removeView(glow) }
    }

    // ---------- Tap and swipe ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouch(view: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val dismissDistance = context.dp(56f)
        var downY = 0f
        var dragging = false
        view.setOnClickListener { onTap?.invoke() } // TalkBack double-tap lands here
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; dragging = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > slop) dragging = true
                    if (dragging) {
                        v.translationY = max(0f, dy)
                        v.alpha = 1f - min(0.7f, max(0f, dy) / context.dp(160f))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    if (dragging && dy > dismissDistance) {
                        onDismiss?.invoke()
                    } else {
                        if (!dragging) v.performClick()
                        v.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
                            .setInterpolator(OvershootInterpolator(2f)).setDuration(220).start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val ORB_DP = 112
        /** The orb while her thought cloud shows, big enough to read "Hmm... Let's see...". */
        private const val THINKING_DP = 156
        /**
         * Where the orb's centre is aimed, as a fraction of the screen height from the top. Overlays are placed
         * above the navigation bar, so it lands at about 76% (checked on screen), inside the 75-78% asked for.
         */
        private const val ORB_CENTER = 0.78f
        /** Tools that work inside other apps on screen. */
        private val APP_TOOLS = setOf(
            "read_screen", "tap_text", "type_text", "scroll", "open_app", "play_on_youtube", "phone_navigation",
            "send_whatsapp_message", "quick_setting", "open_settings", "draft_email", "list_alarms", "alarm_switch",
        )
        /** Tools that start music or videos: her playing face. */
        private val MEDIA_TOOLS = setOf("media_control", "play_on_youtube", "play_music")
        /** Tools that change something, worth a happy face (and the success sound) when they work. */
        val ACTION_TOOLS = setOf(
            "send_whatsapp_message", "send_sms", "call_contact", "reply_to_message", "set_alarm", "set_timer",
            "flashlight", "set_volume", "set_brightness", "sound_mode", "media_control", "add_calendar_event",
            "set_reminder", "cancel_reminder", "watch_notifications", "stop_watching", "remember", "forget",
            "save_routine", "delete_routine", "quick_setting", "alarm_switch",
        )
    }
}
