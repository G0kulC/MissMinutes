package com.missminutes.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

/** Pico's faces, one Lottie animation each in assets/pico. */
enum class Mood(val file: String) {
    IDLE("idle"), SLEEPY("sleepy"), GREETING("greeting"), GOODBYE("goodbye"),
    LISTENING("listening"), WAITING("waiting"), THINKING("thinking"), SEARCHING("searching"), WORKING("working"),
    SPEAKING("speaking"), PLAYING("playing"), NOTIFY("notify"), SUCCESS("success"), ERROR("error"),
    HAPPY("happy"), EXCITED("excited"), LOVE("love"), SAD("sad"), SURPRISED("surprised"),
    CONFUSED("confused"), SCARED("scared"), ANGRY("angry");

    val asset get() = "pico/$file.json"

    companion object {
        /** The face for a mood tag Claude puts on a reply ("[happy]"); calm is her normal speaking face. */
        fun ofTag(tag: String): Mood? = when (tag) {
            "happy" -> HAPPY; "excited" -> EXCITED; "playful" -> PLAYING; "love" -> LOVE; "sad" -> SAD; "surprised" -> SURPRISED
            "confused" -> CONFUSED; "scared" -> SCARED; "angry" -> ANGRY
            else -> null
        }
    }
}

/**
 * Pico's colours. The animations are drawn in mint; a theme moves the ghost's mint body and glow to [hue]
 * (degrees), with [sat] scaling how strong the colour is and [light] making it lighter or darker. Her eyes,
 * blush, hearts and badges keep their own colours.
 */
enum class GhostTheme(val title: String, val hue: Float, val sat: Float = 1f, val light: Float = 0f) {
    MINT("Mint", 175f),
    AQUA("Aqua", 190f),
    SKY("Sky", 200f, 0.95f, 0.02f),
    OCEAN("Ocean", 212f),
    ROYAL("Royal blue", 228f, 0.9f, -0.04f),
    LAVENDER("Lavender", 262f, 0.65f, 0.04f),
    GRAPE("Grape", 278f, 0.85f, -0.04f),
    ORCHID("Orchid", 300f, 0.7f),
    BUBBLEGUM("Bubblegum", 325f, 0.85f, 0.03f),
    ROSE("Rose", 345f, 0.8f),
    CHERRY("Cherry", 0f, 0.85f, -0.06f),
    CORAL("Coral", 12f, 0.95f, 0.02f),
    PEACH("Peach", 24f, 0.9f, 0.05f),
    MANGO("Mango", 36f),
    SUNSHINE("Sunshine", 50f, 1f, 0.02f),
    LIME("Lime", 82f, 0.9f),
    LEAF("Leaf", 120f, 0.75f, -0.03f),
    EMERALD("Emerald", 150f, 0.85f, -0.04f),
    SNOW("Snow", 210f, 0f, 0.1f),
    MIDNIGHT("Midnight", 232f, 0.5f, -0.28f);

    /** The theme's main colour, for its swatch in the app. */
    val swatch: Int get() = shade(0.306f, 0.949f, 0.902f).let { (r, g, b) -> Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()) }

    /** [r], [g], [b] (0..1) moved to this theme if it's one of the ghost's mint shades, unchanged otherwise. */
    fun shade(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val (h, s, l) = toHsl(r, g, b)
        if (s < 0.25f || abs(h - 177f) > 22f) return Triple(r, g, b) // not mint: eyes, white, blush, badges
        return fromHsl(hue, (s * sat).coerceIn(0f, 1f), (l + light).coerceIn(0f, 1f))
    }

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: MINT

        private fun toHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val l = (max + min) / 2f
            if (max == min) return Triple(0f, 0f, l)
            val d = max - min
            val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            val h = when (max) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f))
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            } * 60f
            return Triple(h, s, l)
        }

        private fun fromHsl(h: Float, s: Float, l: Float): Triple<Float, Float, Float> {
            if (s == 0f) return Triple(l, l, l)
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            fun channel(t0: Float): Float {
                var t = t0
                if (t < 0) t += 1f
                if (t > 1) t -= 1f
                return when {
                    t < 1f / 6 -> p + (q - p) * 6 * t
                    t < 1f / 2 -> q
                    t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                    else -> p
                }
            }
            val hh = h / 360f
            return Triple(channel(hh + 1f / 3), channel(hh), channel(hh - 1f / 3))
        }
    }
}

/** Loads Pico's animations recoloured for a theme, off the main thread, and keeps them for instant switching. */
private object GhostLibrary {
    private val cache = HashMap<String, LottieComposition>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, mood: Mood, theme: GhostTheme, done: (LottieComposition) -> Unit) {
        val key = "${mood.file}/${theme.name}"
        synchronized(cache) { cache[key] }?.let { done(it); return }
        val app = context.applicationContext
        worker.execute {
            val composition = runCatching {
                val json = app.assets.open(mood.asset).bufferedReader().use { it.readText() }
                val tinted = if (theme == GhostTheme.MINT) json else recolor(JSONObject(json), theme).toString()
                LottieCompositionFactory.fromJsonStringSync(tinted, key).value
            }.getOrNull() ?: return@execute
            synchronized(cache) { cache[key] = composition }
            main.post { done(composition) }
        }
    }

    /** Walks the animation and moves every mint fill, stroke and gradient stop to the theme. */
    private fun recolor(node: Any?, theme: GhostTheme): JSONObject? {
        when (node) {
            is JSONObject -> {
                when (node.optString("ty")) {
                    "fl", "st" -> node.optJSONObject("c")?.let { tintProperty(it, theme, stops = 1, stride = 0) }
                    "gf", "gs" -> node.optJSONObject("g")?.let { g ->
                        g.optJSONObject("k")?.let { tintProperty(it, theme, stops = g.optInt("p"), stride = 4) }
                    }
                }
                node.keys().forEach { recolor(node.opt(it), theme) }
                return node
            }
            is JSONArray -> for (i in 0 until node.length()) recolor(node.opt(i), theme)
        }
        return null
    }

    /** A colour property, still or animated. Plain colours are [r,g,b,a]; gradients are [offset,r,g,b]×stops. */
    private fun tintProperty(prop: JSONObject, theme: GhostTheme, stops: Int, stride: Int) {
        val k = prop.opt("k") as? JSONArray ?: return
        if (k.length() > 0 && k.opt(0) is JSONObject) {
            for (i in 0 until k.length()) k.optJSONObject(i)?.let { kf ->
                kf.optJSONArray("s")?.let { tintValues(it, theme, stops, stride) }
                kf.optJSONArray("e")?.let { tintValues(it, theme, stops, stride) }
            }
        } else {
            tintValues(k, theme, stops, stride)
        }
    }

    private fun tintValues(a: JSONArray, theme: GhostTheme, stops: Int, stride: Int) {
        for (stop in 0 until stops) {
            val at = if (stride == 0) 0 else stop * stride + 1 // gradients start each stop with its offset
            if (a.length() < at + 3) return
            val (r, g, b) = theme.shade(a.optDouble(at).toFloat(), a.optDouble(at + 1).toFloat(), a.optDouble(at + 2).toFloat())
            a.put(at, r.toDouble()); a.put(at + 1, g.toDouble()); a.put(at + 2, b.toDouble())
        }
    }
}

/**
 * Pico the ghost. Her face follows what she's doing: [state] (listening, thinking, speaking), what kind of work
 * she's doing ([thinkingMood]: searching the web, working in your apps), the feeling of what she's saying
 * ([emotion]), and one-off moments shown with [flash] (hello, success, error, a notification, goodbye).
 * Stopped, she's sleepy. She swells a little with the voice level, and wears the colour [theme].
 */
class GhostView(context: Context) : ImageView(context) {

    var state: OrbState = OrbState.IDLE
        set(value) {
            if (value == OrbState.THINKING && field != OrbState.THINKING) emotion = null // a new turn starts calm
            field = value
            if (value != OrbState.THINKING) thinkingMood = Mood.THINKING
            refresh()
        }

    /** What thinking looks like right now: [Mood.THINKING], [Mood.SEARCHING] or [Mood.WORKING]. */
    var thinkingMood = Mood.THINKING
        set(value) { field = value; refresh() }

    /** The feeling of the reply she's speaking; null is her normal speaking face. */
    var emotion: Mood? = null
        set(value) { field = value; refresh() }

    /** Waiting for your yes or no: listening shows her waiting face. */
    var waitingAnswer = false
        set(value) { field = value; refresh() }

    var theme: GhostTheme = GhostTheme.MINT
        set(value) { if (field != value) { field = value; shown = null; refresh() } }

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

    /** Asleep: sleepy eyes, z z z. */
    fun stop() { awake = false; refresh() }

    fun flash(mood: Mood, ms: Long = 1600) {
        removeCallbacks(endFlash)
        flashMood = mood
        refresh()
        postDelayed(endFlash, ms)
    }

    private fun refresh() {
        val mood = flashMood ?: if (!awake) Mood.SLEEPY else when (state) {
            OrbState.IDLE -> Mood.IDLE
            OrbState.LISTENING -> if (waitingAnswer) Mood.WAITING else Mood.LISTENING
            OrbState.THINKING -> thinkingMood
            OrbState.SPEAKING -> emotion ?: Mood.SPEAKING
        }
        if (mood == shown) return
        shown = mood
        GhostLibrary.load(context, mood, theme) { composition ->
            if (shown != mood) return@load // the mood changed again while this one loaded
            lottie.composition = composition
            setImageDrawable(null) // makes the ImageView re-measure the drawable's new size
            setImageDrawable(lottie)
            if (motion) lottie.playAnimation() else lottie.progress = 0.3f // reduced motion: a still pose
        }
    }
}

/** A thin rotating light around the screen edge while Pico is working inside your apps. */
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
