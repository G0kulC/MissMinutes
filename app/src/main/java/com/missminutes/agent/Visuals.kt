package com.missminutes.agent

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

fun stateColor(s: OrbState) = when (s) {
    OrbState.IDLE -> Palette.PLUM
    OrbState.LISTENING -> Palette.AMBER
    OrbState.THINKING -> Palette.TEAL
    OrbState.SPEAKING -> Palette.CORAL
}

/**
 * The minute dial: 60 ticks around a glowing core, like a clock face that reacts to sound.
 * Idle: a lit second hand ticks round. Listening: ticks stretch with your voice.
 * Working: a bright sweep races round. Speaking: ticks pulse with her voice.
 */
class MinuteDialView(context: Context) : View(context) {

    var state: OrbState = OrbState.IDLE
        set(value) {
            if (field == value) return
            fromPrimary = primary; fromSecondary = secondary
            field = value
            transitionStart = SystemClock.uptimeMillis()
            invalidate()
        }

    @Volatile private var targetLevel = 0f
    private var level = 0f
    fun setLevel(v: Float) { targetLevel = v.coerceIn(0f, 1f) }

    private var running = false
    private var introStart = 0L
    private val born = SystemClock.uptimeMillis()
    private var transitionStart = 0L
    private val motion = ValueAnimator.areAnimatorsEnabled()

    private var primary = stateColor(OrbState.IDLE)
    private var secondary = Palette.PLUM
    private var fromPrimary = primary
    private var fromSecondary = secondary
    private val evaluator = ArgbEvaluator()

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** [withIntro] sweeps the ticks in clockwise, the moment she wakes up. */
    fun start(withIntro: Boolean = true) {
        running = motion
        introStart = if (withIntro && motion) SystemClock.uptimeMillis() else 0L
        invalidate()
    }

    fun stop() { running = false; invalidate() }

    private fun secondaryFor(s: OrbState) = when (s) {
        OrbState.IDLE -> Palette.AMBER
        OrbState.LISTENING -> Palette.CORAL
        OrbState.THINKING -> Palette.CREAM
        OrbState.SPEAKING -> Palette.AMBER
    }

    private fun blend(a: Int, b: Int, f: Float) = evaluator.evaluate(f, a, b) as Int

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - born) / 1000f
        level += (targetLevel - level) * 0.25f

        val p = if (transitionStart == 0L) 1f else ((now - transitionStart) / 260f).coerceIn(0f, 1f)
        primary = blend(fromPrimary, stateColor(state), p)
        secondary = blend(fromSecondary, secondaryFor(state), p)

        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val ring = size * 0.31f
        val intro = if (introStart == 0L) 1f else ((now - introStart) / 450f).coerceIn(0f, 1f)

        // Soft glow behind the dial
        val glowAlpha = 50 + (110 * level).toInt() + if (state == OrbState.THINKING) 30 else 0
        fillPaint.shader = RadialGradient(
            cx, cy, size * 0.5f,
            intArrayOf(Palette.alpha(primary, glowAlpha), Palette.alpha(primary, 0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, size * 0.5f, fillPaint)
        fillPaint.shader = null

        // 60 minute ticks
        for (i in 0 until 60) {
            if (i / 60f > intro) break
            val major = i % 5 == 0
            var len = if (major) size * 0.05f else size * 0.028f
            var alpha = if (major) 170 else 85
            when (state) {
                OrbState.IDLE -> if (running) {
                    val second = (t % 60f).toInt()
                    val behind = (second - i + 60) % 60
                    if (behind < 6) alpha = maxOf(alpha, 255 - behind * 32)
                }
                OrbState.LISTENING -> {
                    val wave = 0.55f + 0.45f * sin(i * 0.55f + t * 7f)
                    len += size * 0.10f * level * wave
                    alpha = (110 + 145 * min(1f, level * 1.4f + 0.15f)).toInt()
                }
                OrbState.THINKING -> {
                    val head = (t * 0.85f % 1f) * 60f
                    val behind = (head - i + 60f) % 60f
                    if (behind < 16f) {
                        val k = 1f - behind / 16f
                        alpha = maxOf(alpha, (255 * k).toInt())
                        len += size * 0.045f * k
                    }
                }
                OrbState.SPEAKING -> {
                    val wave = 0.6f + 0.4f * sin(i * 0.9f - t * 9f)
                    len += size * 0.085f * level * wave
                    alpha = (120 + 135 * level).toInt()
                }
            }
            val angle = (i / 60f) * 2f * PI.toFloat() - PI.toFloat() / 2f
            val c = cos(angle)
            val s = sin(angle)
            tickPaint.strokeWidth = if (major) size * 0.016f else size * 0.01f
            tickPaint.color = Palette.alpha(if (major) primary else blend(primary, secondary, 0.45f), alpha)
            canvas.drawLine(cx + c * ring, cy + s * ring, cx + c * (ring + len), cy + s * (ring + len), tickPaint)
        }

        // Glowing core
        var coreR = size * 0.17f * (1f + 0.15f * level) * intro
        if (state == OrbState.THINKING && running) coreR *= 0.94f + 0.04f * sin(t * 5f)
        if (coreR > 0f) {
            fillPaint.shader = RadialGradient(
                cx - coreR * 0.35f, cy - coreR * 0.35f, coreR * 1.5f,
                intArrayOf(Palette.CREAM, primary, blend(primary, Color.BLACK, 0.55f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, coreR, fillPaint)
            fillPaint.shader = null
        }

        if (running || p < 1f || intro < 1f) postInvalidateOnAnimation()
    }
}

/** A thin rotating light around the screen edge while Miss Minutes is working inside your apps. */
class EdgeGlowView(context: Context) : View(context) {
    @Volatile private var targetLevel = 0f
    private var level = 0f
    var running = false
        set(value) { field = value; if (value) invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rotation = Matrix()
    private val rect = RectF()
    private var sweep: SweepGradient? = null
    private val born = SystemClock.uptimeMillis()
    private val layerWidths = floatArrayOf(4.5f, 2.2f, 1f)
    private val layerAlphas = intArrayOf(40, 90, 255)

    fun setLevel(v: Float) { targetLevel = v.coerceIn(0f, 1f) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sweep = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Palette.TEAL, Palette.AMBER, Palette.CORAL, Palette.TEAL), null
        )
    }

    override fun onDraw(canvas: Canvas) {
        val shader = sweep ?: return
        level += (targetLevel - level) * 0.15f
        val t = (SystemClock.uptimeMillis() - born) / 1000f
        rotation.setRotate(t * 70f, width / 2f, height / 2f)
        shader.setLocalMatrix(rotation)
        paint.shader = shader
        val radius = context.dp(34f)
        val base = context.dp(2.2f) + context.dp(3f) * level
        for (i in layerWidths.indices) {
            paint.strokeWidth = base * layerWidths[i]
            paint.alpha = layerAlphas[i]
            val inset = paint.strokeWidth / 2f
            rect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        if (running) postInvalidateOnAnimation()
    }
}
