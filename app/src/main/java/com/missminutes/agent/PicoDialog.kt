package com.missminutes.agent

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * The app's one dialog style: a plum card with rounded corners, the app's own font, and pill buttons. Secondary is
 * an outline, primary is amber, and a destructive action is red. The main button is always on the right.
 * (Android's own dialogs used another font and colour, so they looked like a different app.)
 */
class PicoDialog(private val a: Activity) {
    enum class Kind { PRIMARY, DESTRUCTIVE }

    private var title = ""
    private var message: String? = null
    private var content: View? = null
    private var primary: Triple<String, Kind, () -> Unit>? = null
    private var secondary: Pair<String, () -> Unit>? = "Cancel" to {}
    private var extra: Pair<String, () -> Unit>? = null
    private var requiredField: EditText? = null

    fun title(t: String) = apply { title = t }
    fun message(m: String) = apply { message = m }
    fun content(v: View) = apply { content = v }
    fun primary(label: String, kind: Kind = Kind.PRIMARY, onClick: () -> Unit) = apply { primary = Triple(label, kind, onClick) }
    fun secondary(label: String?, onClick: () -> Unit = {}) = apply { secondary = label?.let { it to onClick } }
    /** A third, quieter action on the left (e.g. "Export first"). */
    fun extra(label: String, onClick: () -> Unit) = apply { extra = label to onClick }
    /** The primary button stays off while this field is empty. */
    fun requires(field: EditText) = apply { requiredField = field }

    fun show(): Dialog {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pad = a.dpi(24)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, a.dpi(18))
            background = GradientDrawable().apply { cornerRadius = a.dp(28f); setColor(SURFACE) }
        }
        card.addView(text(title, 22f, Fonts.SEMIBOLD, Palette.CREAM).apply { setLineSpacing(0f, 1.1f) })
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        message?.let { body.addView(text(it, 16f, Fonts.REGULAR, Palette.CREAM).apply { setLineSpacing(0f, 1.3f); alpha = 0.92f }) }
        content?.let { body.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (message != null) topMargin = a.dpi(14)
        }) }
        val maxBody = (a.resources.displayMetrics.heightPixels * 0.6f).toInt()
        card.addView(object : ScrollView(a) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxBody, View.MeasureSpec.AT_MOST))
        }.apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dpi(12) })

        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        extra?.let { (label, act) ->
            buttons.addView(pill(label, plain = true) { d.dismiss(); act() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dpi(48)))
            buttons.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        }
        secondary?.let { (label, act) ->
            buttons.addView(pill(label, outline = true) { d.dismiss(); act() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dpi(48)))
        }
        primary?.let { (label, kind, act) ->
            val b = pill(label, color = if (kind == Kind.DESTRUCTIVE) RED else Palette.AMBER,
                textColor = if (kind == Kind.DESTRUCTIVE) Color.WHITE else Palette.INK) { d.dismiss(); act() }
            buttons.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dpi(48)).apply { marginStart = a.dpi(10) })
            requiredField?.let { f ->
                fun update() { val ok = f.text.isNotBlank(); b.isEnabled = ok; b.alpha = if (ok) 1f else 0.4f }
                f.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b4: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) = update()
                })
                update()
            }
        }
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dpi(20) })

        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(min(a.resources.displayMetrics.widthPixels - a.dpi(40), a.dpi(440)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
        return d
    }

    private fun text(v: String, size: Float, weight: Int, color: Int) = TextView(a).apply {
        text = v; textSize = size; typeface = Fonts.get(a, weight); setTextColor(color)
    }

    private fun pill(label: String, outline: Boolean = false, plain: Boolean = false, color: Int = Palette.AMBER,
                     textColor: Int = Palette.INK, onClick: () -> Unit) = text(label, 15f, Fonts.SEMIBOLD,
        if (outline || plain) Palette.CREAM else textColor).apply {
        gravity = Gravity.CENTER
        setPadding(a.dpi(20), 0, a.dpi(20), 0)
        minWidth = a.dpi(48)
        val shape = GradientDrawable().apply {
            cornerRadius = a.dp(99f)
            when {
                plain -> setColor(Color.TRANSPARENT)
                outline -> { setColor(Color.TRANSPARENT); setStroke(a.dpi(1), Palette.alpha(Palette.CREAM, 110)) }
                else -> setColor(color)
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.CREAM, 50)), shape, GradientDrawable().apply {
            cornerRadius = a.dp(99f); setColor(Color.WHITE)
        })
        isClickable = true
        setOnClickListener { onClick() }
    }

    companion object {
        val SURFACE = 0xFF2E2336.toInt()
        val RED = 0xFFE5484D.toInt()
    }
}
