package com.missminutes.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

/** The ghost's moods, one Lottie file each in assets/ghost (drawn by tools/ghost_lottie.py). */
enum class Mood(val asset: String) {
    IDLE("ghost/idle.json"),
    LISTENING("ghost/listening.json"),
    LOADING("ghost/loading.json"),
    NOTIFICATION("ghost/notification.json"),
    SLEEPING("ghost/sleeping.json"),
    SPEAKING("ghost/speaking.json"),
    SUCCESS("ghost/success.json"),
    ERROR("ghost/error.json"),
    TYPING("ghost/typing.json"),
}

/**
 * Miss Minutes as a little ghost. [state] picks her mood: listening, thinking (typing while she works inside
 * your apps), speaking. [flash] shows a one-off mood for a moment: success, error, a new notification.
 * Stopped, she sleeps. She swells a little with the voice level.
 */
class GhostView(context: Context) : ImageView(context) {

    var state: OrbState = OrbState.IDLE
        set(value) {
            field = value
            if (value != OrbState.THINKING) inApps = false
            refresh()
        }

    /** Thinking while working inside your apps shows her typing on a laptop instead of the thinking ring. */
    var inApps = false
        set(value) { field = value; refresh() }

    @Volatile private var targetLevel = 0f
    private var voice = 0f
    private var awake = true
    private var flashMood: Mood? = null
    private var shown: Mood? = null
    private val motion = ValueAnimator.areAnimatorsEnabled()
    private val endFlash = Runnable { flashMood = null; refresh() }
    // Played as a drawable in a plain ImageView: LottieAnimationView would pull in AppCompat, which the app doesn't use.
    private val lottie = LottieDrawable().apply {
        repeatCount = LottieDrawable.INFINITE
        addAnimatorUpdateListener {
            voice += (targetLevel - voice) * 0.25f
            val s = 1f + 0.08f * voice
            this@GhostView.scaleX = s
            this@GhostView.scaleY = s
        }
    }

    init {
        scaleType = ScaleType.FIT_CENTER
        setImageDrawable(lottie)
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(endFlash)
        lottie.cancelAnimation()
    }

    /** Safe from any thread: it only stores a number read each frame. */
    fun setLevel(v: Float) { targetLevel = v.coerceIn(0f, 1f) }

    /** Awake again, showing the mood for [state]. */
    @Suppress("UNUSED_PARAMETER")
    fun start(withIntro: Boolean = true) { awake = true; refresh() }

    /** Asleep: eyes closed, z z z. */
    fun stop() { awake = false; refresh() }

    fun flash(mood: Mood, ms: Long = 1600) {
        removeCallbacks(endFlash)
        flashMood = mood
        refresh()
        postDelayed(endFlash, ms)
    }

    private fun refresh() {
        val mood = flashMood ?: if (!awake) Mood.SLEEPING else when (state) {
            OrbState.IDLE -> Mood.IDLE
            OrbState.LISTENING -> Mood.LISTENING
            OrbState.THINKING -> if (inApps) Mood.TYPING else Mood.LOADING
            OrbState.SPEAKING -> Mood.SPEAKING
        }
        if (mood == shown) return
        shown = mood
        // Lottie caches each file after the first load, so switching moods is instant from then on.
        LottieCompositionFactory.fromAsset(context, mood.asset).addListener { composition ->
            if (shown != mood) return@addListener // the mood changed again while this one loaded
            lottie.composition = composition
            setImageDrawable(null) // makes the ImageView re-measure the drawable's new size
            setImageDrawable(lottie)
            if (motion) lottie.playAnimation() else lottie.progress = 0.3f // reduced motion: a still pose
        }
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
