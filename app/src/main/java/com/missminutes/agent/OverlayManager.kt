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
 * What you see when you wake Miss Minutes: just the round minute dial at the bottom of the screen
 * (amber listening, teal working, coral speaking). Tap it to skip her speaking or stop; swipe it down to close.
 * When she asks you something, a panel slides up above the orb with her question, Yes / Cancel buttons
 * for confirmations, and a short hint underneath.
 */
class OverlayManager(private val context: Context, private val prefs: Prefs) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val motion = ValueAnimator.areAnimatorsEnabled()

    private var orb: FrameLayout? = null
    @Volatile private var dial: MinuteDialView? = null
    private var edge: EdgeGlowView? = null
    private var panel: LinearLayout? = null
    private var activity: String? = null

    var state = OrbState.IDLE
        private set
    var onTap: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    /** A Yes (true) or Cancel (false) tap on the confirmation panel. */
    var onAnswer: ((Boolean) -> Unit)? = null

    enum class PanelKind { CONFIRM, QUESTION, PROBLEM }

    fun canShow() = Settings.canDrawOverlays(context)

    private val orbSize get() = context.dpi(ORB_DP)
    private val orbLift get() = context.dpi(28)

    // ---------- The orb ----------

    fun show() {
        if (orb != null || !canShow()) return
        if (prefs.edgeGlow) addEdgeGlow()

        val dialView = MinuteDialView(context)
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
        orb = orbView; dial = dialView
        installTouch(orbView)
        dialView.start(withIntro = true)
        describe()

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
        if (s != OrbState.THINKING) activity = null
        state = s
        dial?.state = s
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
    }

    /** What she's doing right now ("Opening WhatsApp"), read out by TalkBack instead of shown on screen. */
    fun setActivity(label: String) {
        activity = label
        describe()
    }

    private fun describe() {
        val view = orb ?: return
        val what = when (state) {
            OrbState.IDLE -> "ready"
            OrbState.LISTENING -> "listening"
            OrbState.THINKING -> activity?.let { "working: $it" } ?: "working"
            OrbState.SPEAKING -> "speaking"
        }
        val action = if (state == OrbState.SPEAKING) "Double-tap to skip" else "Double-tap to stop"
        view.contentDescription = "Miss Minutes, $what. $action, swipe down to close."
    }

    // ---------- The panel ----------

    /** Shows her question (or a problem) above the orb. Calling it again swaps the text in place. */
    fun showPanel(message: String, kind: PanelKind) {
        if (orb == null || !canShow()) return
        hidePanel(animated = false)

        val accent = when (kind) {
            PanelKind.CONFIRM -> Palette.AMBER
            PanelKind.QUESTION -> Palette.AMBER
            PanelKind.PROBLEM -> Palette.CORAL
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(context).apply {
            text = when (kind) {
                PanelKind.CONFIRM -> "Confirm"
                PanelKind.QUESTION -> "Miss Minutes asks"
                PanelKind.PROBLEM -> "Something went wrong"
            }
            textSize = 13f
            letterSpacing = 0.04f
            typeface = Fonts.get(context, Fonts.SEMIBOLD)
            setTextColor(accent)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(context.dpi(8), context.dpi(8)).apply { marginEnd = context.dpi(8) })
            addView(label)
        }
        val body = TextView(context).apply {
            text = message
            textSize = 18f
            typeface = Fonts.get(context, Fonts.REGULAR)
            setTextColor(Palette.CREAM)
            setLineSpacing(0f, 1.2f)
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpi(20), context.dpi(16), context.dpi(20), context.dpi(16))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(24f)
                setColor(Palette.alpha(Palette.DIAL, 250))
                setStroke(context.dpi(1), Palette.alpha(Palette.CREAM, 24))
            }
            elevation = context.dp(10f)
            addView(header)
            addView(body, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpi(8) })
            if (kind == PanelKind.CONFIRM) {
                val buttons = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("Cancel", filled = false) { onAnswer?.invoke(false) },
                        LinearLayout.LayoutParams(0, context.dpi(48), 1f).apply { marginEnd = context.dpi(10) })
                    addView(button("Yes", filled = true) { onAnswer?.invoke(true) },
                        LinearLayout.LayoutParams(0, context.dpi(48), 1f))
                }
                addView(buttons, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dpi(16) })
            }
        }
        val hint = TextView(context).apply {
            text = when (kind) {
                PanelKind.CONFIRM -> "Say “yes” or “no”, or tap a button"
                PanelKind.QUESTION -> "Just answer out loud · tap the orb to stop"
                PanelKind.PROBLEM -> "Tap the orb to close"
            }
            textSize = 13f
            typeface = Fonts.get(context, Fonts.MEDIUM)
            setTextColor(Palette.CREAM)
            gravity = Gravity.CENTER
            setPadding(context.dpi(14), context.dpi(6), context.dpi(14), context.dpi(6))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(99f)
                setColor(Palette.alpha(Palette.INK, 214)) // readable over any app behind it
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipToPadding = false
            setPadding(context.dpi(6), context.dpi(6), context.dpi(6), context.dpi(4))
            addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(hint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpi(10) })
        }

        val width = min((context.resources.displayMetrics.widthPixels * 0.92f).toInt(), context.dpi(440))
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = orbLift + orbSize + context.dpi(4)
        }
        if (runCatching { wm.addView(column, params) }.isFailure) return
        panel = column

        if (motion) {
            column.alpha = 0f
            column.translationY = context.dp(18f)
            column.scaleX = 0.97f
            column.scaleY = 0.97f
            column.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setInterpolator(DecelerateInterpolator(2f)).setDuration(260).start()
        }
    }

    fun hidePanel(animated: Boolean = true) {
        val view = panel ?: return
        panel = null
        if (motion && animated) {
            view.animate().alpha(0f).translationY(context.dp(10f)).setInterpolator(AccelerateInterpolator())
                .setDuration(150).withEndAction { runCatching { wm.removeView(view) } }.start()
        } else {
            runCatching { wm.removeView(view) }
        }
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        typeface = Fonts.get(context, Fonts.SEMIBOLD)
        stateListAnimator = null
        minHeight = context.dpi(48)
        minimumHeight = context.dpi(48)
        setPadding(context.dpi(12), 0, context.dpi(12), 0)
        setTextColor(if (filled) Palette.INK else Palette.CREAM)
        val shape = GradientDrawable().apply {
            cornerRadius = context.dp(99f)
            if (filled) setColor(Palette.AMBER)
            else { setColor(Palette.alpha(Palette.CREAM, 10)); setStroke(context.dpi(1), Palette.alpha(Palette.CREAM, 70)) }
        }
        val ripple = if (filled) Palette.alpha(Palette.INK, 60) else Palette.alpha(Palette.CREAM, 50)
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
        setOnClickListener { onClick() }
    }

    // ---------- Hide everything ----------

    fun hide() {
        hidePanel()
        val view = orb
        orb = null
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
    }
}
