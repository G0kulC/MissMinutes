package com.missminutes.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/**
 * Miss Minutes colours. State colours carry meaning everywhere in the app:
 * amber = listening, teal = working, coral = speaking.
 */
object Palette {
    val INK = 0xFF17111C.toInt()        // background, deep aubergine
    val DIAL = 0xFF241B2B.toInt()       // raised surfaces (cards, the floating panel)
    val SURFACE_HI = 0xFF31263A.toInt() // surfaces on surfaces (fields, selected rows)
    val CREAM = 0xFFF7EBDC.toInt()      // main text, dial highlight
    val MUTED = 0xFFB3A6BD.toInt()      // secondary text (7:1 on INK, 5.6:1 on DIAL)
    val AMBER = 0xFFFFB547.toInt()      // listening, primary actions
    val CORAL = 0xFFFF7A6B.toInt()      // speaking, errors
    val TEAL = 0xFF3ED7C4.toInt()       // working, success
    val PLUM = 0xFF7C5C8A.toInt()       // idle

    fun alpha(color: Int, a: Int) =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

/**
 * Outfit, a variable font bundled in assets/fonts (SIL Open Font License, see OFL.txt). Tamil letters
 * fall back to the phone's own Tamil font automatically.
 */
object Fonts {
    const val REGULAR = 400
    const val MEDIUM = 500
    const val SEMIBOLD = 600
    const val BOLD = 700

    private val cache = HashMap<Int, Typeface>()

    fun get(context: Context, weight: Int = REGULAR): Typeface = synchronized(cache) {
        cache.getOrPut(weight) {
            runCatching {
                Typeface.Builder(context.assets, "fonts/Outfit.ttf")
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }.getOrNull() ?: Typeface.create("sans-serif", if (weight >= SEMIBOLD) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()
