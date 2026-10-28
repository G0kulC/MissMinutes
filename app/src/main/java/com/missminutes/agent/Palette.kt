package com.missminutes.agent

import android.content.Context
import android.graphics.Color

/**
 * Miss Minutes colours. State colours carry meaning everywhere in the app:
 * amber = listening, teal = working, coral = speaking.
 */
object Palette {
    val INK = 0xFF17111C.toInt()    // background, deep aubergine
    val DIAL = 0xFF241B2B.toInt()   // raised surfaces
    val CREAM = 0xFFF7EBDC.toInt()  // main text, dial highlight
    val MUTED = 0xFFA99BB3.toInt()  // secondary text
    val AMBER = 0xFFFFB547.toInt()  // listening, primary actions
    val CORAL = 0xFFFF6F61.toInt()  // speaking, errors
    val TEAL = 0xFF3ED7C4.toInt()   // working, success
    val PLUM = 0xFF7C5C8A.toInt()   // idle

    fun alpha(color: Int, a: Int) =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()
