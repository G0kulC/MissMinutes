package com.missminutes.agent

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The floating card that appears when you wake Miss Minutes: the minute dial, what she heard,
 * what she says, and a chip for every action she takes. Tap to skip or stop, swipe down to close.
 */
class OverlayManager(private val context: Context, private val prefs: Prefs) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val motion = ValueAnimator.areAnimatorsEnabled()

    private var card: LinearLayout? = null
    private var edge: EdgeGlowView? = null
    @Volatile private var dial: MinuteDialView? = null
    private var status: TextView? = null
    private var main: TextView? = null
    private var chipsScroll: HorizontalScrollView? = null
    private var chipsRow: LinearLayout? = null
    private val chips = HashMap<String, TextView>()
    private var typing: ValueAnimator? = null

    var state = OrbState.IDLE
        private set
    var onTap: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    fun canShow() = Settings.canDrawOverlays(context)

    fun show() {
        if (card != null || !canShow()) return
        if (prefs.edgeGlow) addEdgeGlow()

        val dialView = MinuteDialView(context)
        val statusView = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.AMBER)
        }
        val mainView = TextView(context).apply {
            textSize = 16f
            setTextColor(Palette.CREAM)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(mainView)
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dialView, LinearLayout.LayoutParams(context.dpi(72), context.dpi(72)))
            addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dpi(12)
            })
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(row)
        }
        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpi(14), context.dpi(12), context.dpi(18), context.dpi(14))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(26f)
                setColor(Palette.alpha(Palette.DIAL, 246))
                setStroke(context.dpi(1), Palette.alpha(Palette.CREAM, 28))
            }
            addView(top)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dpi(10) })
        }

        val width = min((context.resources.displayMetrics.widthPixels * 0.94f).toInt(), context.dpi(520))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = context.dpi(16)
        }
        try {
            wm.addView(cardView, params)
        } catch (e: Exception) {
            removeEdgeGlow()
            return
        }

        card = cardView; dial = dialView; status = statusView; main = mainView
        chipsScroll = scroll; chipsRow = row
        installTouch(cardView)
        dialView.start(withIntro = true)

        if (motion) {
            cardView.alpha = 0f
            cardView.translationY = context.dp(60f)
            cardView.scaleX = 0.94f
            cardView.scaleY = 0.94f
            cardView.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setInterpolator(OvershootInterpolator(0.8f)).setDuration(360).start()
        }
    }

    fun setState(s: OrbState) {
        state = s
        dial?.state = s
        status?.apply {
            text = when (s) {
                OrbState.IDLE -> ""
                OrbState.LISTENING -> "Listening"
                OrbState.THINKING -> "Working on it"
                OrbState.SPEAKING -> "Speaking"
            }
            setTextColor(stateColor(s))
        }
        edge?.let { glow ->
            val on = s == OrbState.THINKING
            if (on) glow.running = true
            glow.animate().alpha(if (on) 1f else 0f).setDuration(240)
                .withEndAction { if (!on) glow.running = false }.start()
        }
    }

    /** Safe to call from any thread: it only stores a number the views read each frame. */
    fun setLevel(v: Float) {
        dial?.setLevel(v)
    }

    fun showHint(text: String) {
        typing?.cancel()
        main?.apply { setTextColor(Palette.MUTED); this.text = text }
    }

    fun showHeard(text: String) = showHint("“$text”")

    fun showReply(text: String) {
        val view = main ?: return
        typing?.cancel()
        view.setTextColor(Palette.CREAM)
        if (!motion || text.length < 4) { view.text = text; return }
        typing = ValueAnimator.ofInt(0, text.length).apply {
            duration = (text.length * 22L).coerceIn(250L, 1600L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { view.text = text.substring(0, it.animatedValue as Int) }
            start()
        }
    }

    fun addChip(id: String, label: String) {
        val row = chipsRow ?: return
        chipsScroll?.visibility = View.VISIBLE
        val chip = TextView(context).apply {
            text = label
            textSize = 12.5f
            maxLines = 1
            setTextColor(Palette.CREAM)
            setPadding(context.dpi(10), context.dpi(5), context.dpi(10), context.dpi(5))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(99f)
                setColor(Palette.alpha(Palette.TEAL, 36))
                setStroke(context.dpi(1), Palette.alpha(Palette.TEAL, 90))
            }
        }
        row.addView(chip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = context.dpi(6) })
        if (motion) {
            chip.alpha = 0f
            chip.translationX = context.dp(10f)
            chip.animate().alpha(1f).translationX(0f).setDuration(180).start()
        }
        chips[id] = chip
        chipsScroll?.post { chipsScroll?.fullScroll(View.FOCUS_RIGHT) }
    }

    fun finishChip(id: String, ok: Boolean) {
        val chip = chips[id] ?: return
        chip.text = (if (ok) "✓ " else "✗ ") + chip.text
        val color = if (ok) Palette.TEAL else Palette.CORAL
        (chip.background as? GradientDrawable)?.apply {
            setColor(Palette.alpha(color, 30))
            setStroke(context.dpi(1), Palette.alpha(color, 110))
        }
    }

    fun hide() {
        typing?.cancel()
        typing = null
        val view = card
        card = null
        dial?.stop()
        dial = null
        status = null; main = null; chipsRow = null; chipsScroll = null
        chips.clear()
        removeEdgeGlow()
        if (view == null) return
        if (motion) {
            view.animate().alpha(0f).translationY(context.dp(28f)).setDuration(200)
                .withEndAction { runCatching { wm.removeView(view) } }.start()
        } else {
            runCatching { wm.removeView(view) }
        }
    }

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
        val dismissDistance = context.dp(70f)
        var downY = 0f
        var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > slop) dragging = true
                    if (dragging) {
                        v.translationY = max(0f, dy)
                        v.alpha = 1f - min(0.6f, max(0f, dy) / context.dp(300f))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    if (dragging && dy > dismissDistance) {
                        onDismiss?.invoke()
                    } else {
                        if (!dragging) { v.performClick(); onTap?.invoke() }
                        v.animate().translationY(0f).alpha(1f).setDuration(150).start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().translationY(0f).alpha(1f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
    }
}
