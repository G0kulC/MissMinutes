package com.missminutes.agent

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The cloud Pico asks you from: soft glass with icy-blue light above and a warm gold glow below, Pico peeking over
 * the top edge with her paws resting on it, the question, Cancel and Yes, and a "say yes or no" pill with a live
 * voice wave. The cloud, text and buttons are native views (real text, big touch targets); only Pico is Lottie.
 *
 * Phases: waiting (gentle float, Yes breathes) → listening (the wave follows your voice) → processing (a blue
 * progress line) → success (green glow and a tick) or error (a restrained red mark), then it contracts away.
 */
class CloudPanel(
    context: Context,
    theme: GhostTheme,
    private val kind: OverlayManager.PanelKind,
    message: String,
    /** Who is asking: the assistant's name, or the agent talking right now. */
    private val speaker: String,
    private val onAnswer: (Boolean) -> Unit,
    onGhostTap: () -> Unit,
) : FrameLayout(context) {

    enum class Phase { WAITING, LISTENING, PROCESSING, SUCCESS, ERROR }

    private val motion = ValueAnimator.areAnimatorsEnabled()
    private fun dp(v: Float) = context.dp(v)
    private fun dpi(v: Int) = context.dpi(v)

    val ghost = GhostView(context).apply {
        this.theme = theme
        state = OrbState.LISTENING
        waitingAnswer = kind == OverlayManager.PanelKind.CONFIRM
        setOnClickListener { onGhostTap() }
        contentDescription = "$speaker. Tap to stop."
    }
    private val shape = CloudShape(context)
    private val dot = View(context)
    private val label = TextView(context)
    private val question = TextView(context)
    private val status = TextView(context)
    private val progress = ProgressLine(context)
    private val wave = WaveIcon(context)
    private var buttons: LinearLayout? = null
    private var yesGlow: View? = null
    private val column = LinearLayout(context)
    private var floater: ValueAnimator? = null
    private var breather: ValueAnimator? = null

    var phase: Phase = Phase.WAITING
        private set

    init {
        clipChildren = false
        clipToPadding = false
        // Room under the hint pill for the gentle float, or its lower edge is cut off by the window.
        setPadding(0, 0, 0, dpi(10))

        // Pico first, so the cloud is drawn over her lower half: she peeks over its top edge.
        addView(ghost, LayoutParams(dpi(GHOST_DP), dpi(GHOST_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(22), dpi(CloudShape.TOP_DP + 16), dpi(22), dpi(CloudShape.PAD_DP + 14))
            addView(header(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(question.apply {
                text = message
                textSize = 17f
                typeface = Fonts.get(context, Fonts.SEMIBOLD)
                setTextColor(INK)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.1f)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, wide(top = 6))
            addView(progress.apply { visibility = View.GONE }, LinearLayout.LayoutParams(dpi(120), dpi(3)).apply { topMargin = dpi(10) })
            addView(status.apply {
                visibility = View.GONE
                textSize = 14f
                typeface = Fonts.get(context, Fonts.SEMIBOLD)
                gravity = Gravity.CENTER
            }, wide(top = 8))
            if (kind == OverlayManager.PanelKind.CONFIRM) addView(buildButtons().also { buttons = it }, wide(top = 12))
        }
        val cloudBox = FrameLayout(context).apply {
            clipChildren = false
            addView(shape, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        column.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(cloudBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hintPill(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpi(8) })
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(GHOST_DP - 30) })

        if (kind == OverlayManager.PanelKind.PROBLEM) setPhase(Phase.ERROR) else setPhase(Phase.WAITING)
    }

    private fun wide(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dpi(top) }

    private fun header() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(dot, LinearLayout.LayoutParams(dpi(8), dpi(8)).apply { marginEnd = dpi(6) })
        addView(label.apply {
            text = when (kind) {
                OverlayManager.PanelKind.CONFIRM -> "Confirm"
                OverlayManager.PanelKind.QUESTION -> "$speaker asks"
                OverlayManager.PanelKind.PROBLEM -> "$speaker needs your attention"
            }
            textSize = 12.5f
            letterSpacing = 0.02f
            typeface = Fonts.get(context, Fonts.SEMIBOLD)
            setTextColor(INK)
        })
    }

    private fun buildButtons() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        val cancel = pillButton("Cancel", filled = false) { onAnswer(false) }
        val yes = pillButton("Yes", filled = true) { setPhase(Phase.PROCESSING); onAnswer(true) }
        val glow = View(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(99f); setColor(Color.argb(140, 255, 205, 70)) }
            alpha = 0.35f
        }
        yesGlow = glow
        addView(cancel, LinearLayout.LayoutParams(0, dpi(42), 1f).apply { marginEnd = dpi(10) })
        addView(FrameLayout(context).apply {
            clipChildren = false
            addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(yes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(0, dpi(42), 1f))
    }

    private fun pillButton(text: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 15f
        typeface = Fonts.get(context, Fonts.SEMIBOLD)
        gravity = Gravity.CENTER
        setTextColor(INK)
        isClickable = true
        contentDescription = text
        val face = GradientDrawable().apply {
            cornerRadius = dp(99f)
            if (filled) {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(Color.rgb(255, 222, 110), Color.rgb(255, 196, 60))
            } else {
                setColor(Color.argb(150, 255, 255, 255))
                setStroke(dpi(1), Color.argb(150, 200, 192, 230))
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 20, 16, 40)), face, null)
        setOnClickListener { onClick() }
    }

    private fun hintPill() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(12), dpi(6), dpi(14), dpi(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(99f)
            setColor(Color.argb(232, 18, 24, 44))
            setStroke(dpi(1), Color.argb(40, 255, 255, 255))
        }
        addView(wave, LinearLayout.LayoutParams(dpi(20), dpi(15)).apply { marginEnd = dpi(8) })
        addView(TextView(context).apply {
            text = when (kind) {
                OverlayManager.PanelKind.CONFIRM -> "Say “yes” or “no”, or tap a button"
                OverlayManager.PanelKind.QUESTION -> "Just answer out loud"
                OverlayManager.PanelKind.PROBLEM -> "Tap $speaker to close"
            }
            textSize = 12.5f
            typeface = Fonts.get(context, Fonts.MEDIUM)
            setTextColor(Palette.CREAM)
        })
    }

    // ---------- Phases ----------

    fun setPhase(p: Phase) {
        phase = p
        val accent = when (p) {
            Phase.SUCCESS -> GREEN
            Phase.ERROR -> RED
            Phase.LISTENING, Phase.PROCESSING -> BLUE
            Phase.WAITING -> GOLD
        }
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        shape.tint = when (p) { Phase.SUCCESS -> GREEN; Phase.ERROR -> RED; else -> null }
        wave.active = p == Phase.LISTENING
        progress.visibility = if (p == Phase.PROCESSING) View.VISIBLE else View.GONE
        progress.running = p == Phase.PROCESSING
        val done = p == Phase.SUCCESS || p == Phase.ERROR
        buttons?.visibility = if (done || p == Phase.PROCESSING) View.GONE else View.VISIBLE
        status.visibility = if (done) View.VISIBLE else View.GONE
        when (p) {
            Phase.SUCCESS -> { status.text = "✓  $speaker got it done!"; status.setTextColor(GREEN_TEXT); ghost.flash(Mood.SUCCESS, 2000) }
            Phase.ERROR -> {
                status.text = if (kind == OverlayManager.PanelKind.PROBLEM) "" else "Something went wrong"
                status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
                status.setTextColor(RED_TEXT)
                ghost.flash(Mood.ERROR, 2400)
            }
            Phase.LISTENING -> ghost.state = OrbState.LISTENING
            Phase.PROCESSING -> ghost.state = OrbState.THINKING
            Phase.WAITING -> {}
        }
    }

    /** Your voice level while listening (0..1): the wave follows it. Safe from any thread. */
    fun setLevel(v: Float) { wave.level = v }

    // ---------- Entrance, float, exit ----------

    fun enter() {
        if (!motion) { shape.puffs = 1f; shape.glow = 1f; return }
        alpha = 0f
        scaleX = 0.92f; scaleY = 0.92f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(DecelerateInterpolator(2f)).setDuration(250).start()
        shape.puffs = 1f
        ghost.translationY = dp(14f)
        ghost.animate().translationY(0f).setInterpolator(DecelerateInterpolator(2f)).setDuration(250).start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener { shape.glow = it.animatedValue as Float }
        }.start()
    }

    /** Contracts and fades the cloud, then [gone]. */
    fun dismiss(gone: () -> Unit) {
        stopLoops()
        if (!motion) { gone(); return }
        animate().cancel()
        animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setInterpolator(AccelerateInterpolator())
            .setDuration(200).withEndAction(gone).start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!motion) return
        // A few pixels of float, with Pico a beat behind the cloud, and the Yes button slowly breathing.
        floater = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 3200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                column.translationY = sin(t) * dp(3f)
                ghost.translationY = sin(t - 0.7f) * dp(3f)
            }
            start()
        }
        breather = ValueAnimator.ofFloat(0.25f, 0.7f).apply {
            duration = 1800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val v = it.animatedValue as Float
                yesGlow?.alpha = v
                yesGlow?.scaleX = 1f + v * 0.06f
                yesGlow?.scaleY = 1f + v * 0.18f
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        stopLoops()
        super.onDetachedFromWindow()
    }

    private fun stopLoops() {
        floater?.cancel(); floater = null
        breather?.cancel(); breather = null
        progress.running = false
    }

    companion object {
        const val GHOST_DP = 76
        private val INK = Color.rgb(27, 24, 48)
        private val GOLD = Color.rgb(255, 196, 60)
        private val BLUE = Color.rgb(79, 176, 255)
        private val GREEN = Color.rgb(60, 205, 120)
        private val RED = Color.rgb(240, 82, 82)
        private val GREEN_TEXT = Color.rgb(24, 140, 74)
        private val RED_TEXT = Color.rgb(196, 48, 48)
    }
}

/** The cloud itself: a rounded body with puffs, glass fill, blue-to-gold glow, Pico's paws and a few sparkles. */
private class CloudShape(context: Context) : View(context) {
    companion object {
        const val TOP_DP = 16 // room above the body for the top puffs
        const val PAD_DP = 10 // room around the body for the glow
    }

    /** How grown the puffs are (0..1, the entrance pops them in). */
    var puffs = 0f
        set(value) { field = value; rebuild(); invalidate() }
    /** Glow strength (0..1). */
    var glow = 0f
        set(value) { field = value; invalidate() }
    /** A colour the glow shifts to briefly (green for success, red for error); null is blue and gold. */
    var tint: Int? = null
        set(value) { field = value; rebuildShaders(); invalidate() }

    private val d = context.resources.displayMetrics.density
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * context.resources.displayMetrics.density }
    private val paw = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pawLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * context.resources.displayMetrics.density; color = Color.argb(110, 170, 160, 220) }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 110) }
    private val born = SystemClock.uptimeMillis()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    // Only a background: it takes the size the text and buttons need, instead of all the room on offer
    // (which stretched the cloud to the full screen height).
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightSpec) else 0
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { rebuild(); rebuildShaders() }

    private fun rebuild() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = PAD_DP * d; val top = TOP_DP * d
        path.reset()
        path.addRoundRect(RectF(pad, top, w - pad, h - pad), 24 * d, 24 * d, Path.Direction.CW)
        val p = puffs.coerceIn(0f, 1.2f)
        // Puffs along the top (clear of the middle, where Pico peeks), down the sides and two little ones outside.
        val circles = listOf(
            Triple(0.16f * w, top + 6 * d, 18 * d), Triple(0.31f * w, top + 1 * d, 15 * d),
            Triple(0.70f * w, top + 1 * d, 16 * d), Triple(0.85f * w, top + 7 * d, 19 * d),
            // Side and bottom puffs stay inside the view (with room for the glow), or they'd be cut off flat.
            Triple(pad + 8 * d, h * 0.62f, 12 * d), Triple(w - pad - 8 * d, h * 0.42f, 13 * d),
        )
        for ((x, y, r) in circles) {
            if (r * p < 1f) continue
            val c = Path().apply { addCircle(x, y, r * p, Path.Direction.CW) }
            path.op(c, Path.Op.UNION)
        }
    }

    private fun rebuildShaders() {
        val h = height.toFloat()
        if (h <= 0f) return
        fill.shader = LinearGradient(0f, 0f, 0f, h,
            // Solid, not see-through: it shows over any app, and the app's own text bled through the question.
            intArrayOf(Color.rgb(255, 255, 255), Color.rgb(240, 236, 255), Color.rgb(229, 238, 255)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        val t = tint
        val topC = t ?: Color.rgb(110, 170, 255)
        val bottomC = t ?: Color.rgb(255, 196, 80)
        glowPaint.shader = LinearGradient(0f, 0f, 0f, h, topC, bottomC, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(0f, 0f, 0f, h,
            Color.argb(230, 255, 255, 255), Palette.alpha(bottomC, 230), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (path.isEmpty) return
        // Glow: wide soft strokes first, then the glass body, a crisp rim, the paws and the sparkles.
        val g = glow.coerceIn(0f, 1f)
        if (g > 0f) {
            for ((i, a) in intArrayOf(55, 22).withIndex()) {
                glowPaint.strokeWidth = (2 + i * 5) * d
                glowPaint.alpha = (a * g).toInt()
                canvas.drawPath(path, glowPaint)
            }
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, rim)

        val cx = width / 2f; val top = TOP_DP * d
        for (side in intArrayOf(-1, 1)) {
            // Her paws sit just inside her body's width, resting on the cloud's edge.
            val r = RectF(cx + side * 16 * d - 7 * d, top - 5 * d, cx + side * 16 * d + 7 * d, top + 5 * d)
            paw.color = Color.WHITE
            canvas.drawOval(r, paw)
            canvas.drawOval(r, pawLine)
        }

        val t = (SystemClock.uptimeMillis() - born) / 1000f
        val sparks = listOf(Triple(0.22f, -0.02f, 0f), Triple(0.8f, 0.03f, 1.6f))
        for ((fx, fy, phase) in sparks) {
            val a = (sin(t * 1.6f + phase) + 1f) / 2f
            if (a < 0.15f) continue
            spark.alpha = (a * 230 * g).toInt()
            drawSparkle(canvas, fx * width, top + fy * height, (2 + a * 2) * d)
        }
        if (g > 0f) postInvalidateOnAnimation()
    }

    private fun drawSparkle(c: Canvas, x: Float, y: Float, r: Float) {
        val s = Path()
        s.moveTo(x, y - r); s.quadTo(x, y, x + r, y); s.quadTo(x, y, x, y + r); s.quadTo(x, y, x - r, y); s.quadTo(x, y, x, y - r)
        c.drawPath(s, spark)
    }
}

/** A thin blue line with a light that sweeps along it while Pico works. */
private class ProgressLine(context: Context) : View(context) {
    var running = false
        set(value) { field = value; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 79, 176, 255) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
        if (!running) return
        val t = ((SystemClock.uptimeMillis() - start) % 1400) / 1400f
        val x = -w * 0.4f + t * w * 1.4f
        bar.shader = LinearGradient(x, 0f, x + w * 0.4f, 0f,
            intArrayOf(Color.argb(0, 79, 176, 255), Color.rgb(79, 176, 255), Color.argb(0, 79, 176, 255)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(maxOf(0f, x), 0f, minOf(w, x + w * 0.4f), h, r, r, bar)
        postInvalidateOnAnimation()
    }
}

/** Five blue bars: a slow pulse while waiting, following your voice while she listens. */
private class WaveIcon(context: Context) : View(context) {
    var active = false
    @Volatile var level = 0f
    private var shown = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(79, 195, 255); strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val start = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        shown += ((if (active) level else 0f) - shown) * 0.25f
        val bars = 5
        val gap = width / bars.toFloat()
        paint.strokeWidth = gap * 0.45f
        for (i in 0 until bars) {
            val idle = 0.25f + 0.15f * ((sin(t * 3f + i * 0.9f) + 1f) / 2f)
            val live = if (active) shown * (0.6f + 0.4f * abs(sin(t * 9f + i * 1.3f))) else 0f
            val hFrac = (idle + live).coerceIn(0.15f, 1f) * if (i == 0 || i == bars - 1) 0.6f else 1f
            val x = gap * (i + 0.5f)
            val half = height * hFrac / 2f
            canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
        postInvalidateOnAnimation()
    }
}
