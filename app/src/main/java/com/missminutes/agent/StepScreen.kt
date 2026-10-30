package com.missminutes.agent

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.launch

/**
 * A full-screen flow that asks one thing per screen: the first-time setup and creating an agent. Progress on
 * top; Back, Skip (for optional steps) and Continue at the bottom.
 */
class StepScreen(
    private val a: MainActivity,
    private val steps: List<Step>,
    /** The last step's button, saying what it does ("Create Cinema"); worked out when that step shows. */
    private val finishLabel: () -> String,
    /** Shows a Cancel on top (creating an agent can be abandoned; the first-time setup can't). */
    private val cancellable: Boolean,
    /** Where to start: the setup picks up at the step it was on if the app closed half-way. */
    startAt: Int = 0,
    /** Called with each step shown, so it can be remembered. */
    private val onStep: (Int) -> Unit = {},
    private val onClose: (finished: Boolean) -> Unit,
) {
    class Step(
        /** Worked out when the step shows, so it can use answers from earlier steps (the assistant's new name). */
        val title: () -> String,
        val detail: () -> String?,
        val optional: Boolean = false,
        /** Fills the screen's body with the question. */
        val build: (LinearLayout) -> Unit,
        /** Saves the answer: returns a message to show instead of moving on, or null to continue. */
        val save: suspend () -> String? = { null },
    )

    private var index = startAt.coerceIn(0, steps.lastIndex)
    private var busy = false
    private val counter = a.text("", 13f, Fonts.MEDIUM, Palette.MUTED)
    private val bar = LinearLayout(a)
    private val title = a.text("", 27f, Fonts.BOLD, Palette.CREAM).apply { setLineSpacing(0f, 1.1f) }
    private val detail = a.text("", 15f, Fonts.REGULAR, Palette.MUTED).apply { setLineSpacing(0f, 1.25f) }
    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val problem = a.text("", 14f, Fonts.MEDIUM, Palette.CORAL).apply { setLineSpacing(0f, 1.2f) }
    private val back = a.button("Back", MainActivity.ButtonStyle.TEXT) { back() }
    private val skip = a.button("Skip", MainActivity.ButtonStyle.TEXT) { go(+1) }
    private val next = a.button("Continue", MainActivity.ButtonStyle.FILLED) { next() }
    private val scroll = ScrollView(a)

    private val footer = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(a.dpi(24), a.dpi(10), a.dpi(24), a.dpi(14))
        setBackgroundColor(Palette.INK)
        addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dpi(52)))
        addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        addView(skip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dpi(52)).apply { marginEnd = a.dpi(6) })
        addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dpi(52)))
    }

    /** The whole flow: the scrolling question above a fixed bar (it used to scroll away and get cut off). */
    val view: View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Palette.INK)
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(View(a).apply { setBackgroundColor(Palette.alpha(Palette.CREAM, 20)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dpi(1)))
        addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init {
        scroll.isFillViewport = true
        scroll.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dpi(24), a.dpi(16), a.dpi(24), a.dpi(20))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = a.dpi(48)
                addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (cancellable) addView(a.button("Cancel", MainActivity.ButtonStyle.TEXT) { onClose(false) }.apply {
                    setTextColor(Palette.MUTED)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dpi(48)))
            }, a.full())
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dpi(6)).apply { topMargin = a.dpi(6) })
            addView(title, a.full(top = 28))
            addView(detail, a.full(top = 8))
            addView(body, a.full(top = 22))
            addView(problem, a.full(top = 12))
        })
        show(animate = false)
    }

    /** Back from the system: the previous step, or (where allowed) leaving the flow. */
    fun back() {
        if (busy) return
        if (index > 0) go(-1) else if (cancellable) onClose(false)
    }

    private fun next() {
        if (busy) return
        busy = true
        next.alpha = 0.6f
        a.scope.launch {
            val message = try { steps[index].save() } finally { busy = false; next.alpha = 1f }
            if (message != null) {
                problem.text = message
                problem.visibility = View.VISIBLE
            } else if (index == steps.lastIndex) {
                onClose(true)
            } else {
                go(+1)
            }
        }
    }

    private fun go(by: Int) {
        if (index == steps.lastIndex && by > 0) { onClose(true); return }
        index = (index + by).coerceIn(0, steps.lastIndex)
        show(animate = true)
    }

    private fun show(animate: Boolean) {
        val step = steps[index]
        a.hideKeyboard(view)
        onStep(index)
        counter.text = "Step ${index + 1} of ${steps.size}"
        bar.removeAllViews()
        bar.addView(a.progressBar(index + 1, steps.size), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        title.text = step.title()
        val about = step.detail()
        detail.text = about.orEmpty()
        detail.visibility = if (about == null) View.GONE else View.VISIBLE
        problem.visibility = View.GONE
        body.removeAllViews()
        step.build(body)
        back.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
        skip.visibility = if (step.optional) View.VISIBLE else View.GONE
        (next as TextView).text = if (index == steps.lastIndex) finishLabel() else "Continue"
        scroll.scrollTo(0, 0)
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            for (v in listOf(title, detail, body)) {
                v.alpha = 0f
                v.translationX = a.dp(24f)
                v.animate().alpha(1f).translationX(0f).setInterpolator(DecelerateInterpolator(2f)).setDuration(240).start()
            }
        }
    }
}
