package com.missminutes.agent

import java.util.Calendar
import kotlin.random.Random

/**
 * What she says the moment you wake her: a short hello, and "Good morning" the first time each morning.
 * Your name comes up only now and then (about one wake in four, plus good morning): a friend who says
 * your name every single time sounds like a machine. Every line is spoken from a cached recording, so the
 * greeting costs no waiting.
 *
 * Each line was checked by having Sarvam say it and transcribing it back. A bare "Hmm" is not used: every
 * voice turns it into a short grunt. "Hmm, சொல்லுங்க…" with words after it comes out right.
 */
object Greetings {

    private var last: String? = null

    /** The greeting for this wake-up, never the same as the one before. */
    fun next(prefs: Prefs): String {
        val name = nameFor(prefs)
        val today = today()
        val firstToday = prefs.lastGreetDay != today
        prefs.lastGreetDay = today
        if (firstToday && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 4..11) return morning(name)
        val pool = if (name.isNotEmpty() && Random.nextInt(4) == 0) named(name) else plain()
        val line = pool.filter { it != last }.ifEmpty { pool }.random()
        last = line
        return line
    }

    /** Every line she might say, so they can all be downloaded ahead of time. */
    fun all(prefs: Prefs): List<String> {
        val name = nameFor(prefs)
        return listOf(morning(name)) + plain() + (if (name.isEmpty()) emptyList() else named(name))
    }

    // The first wake of the morning offers the day's briefing; a "yes" gets it (daily_briefing).
    private fun morning(name: String) = if (name.isEmpty()) "Good morning! இன்னைக்கு என்ன இருக்குன்னு சொல்லட்டுமா?"
        else "Good morning $name! இன்னைக்கு என்ன இருக்குன்னு சொல்லட்டுமா?"

    private fun plain() = listOf("Hey there!", "Hmm, சொல்லுங்க?", "Yes?", "சொல்லுங்க, கேக்கறேன்!", "ஹாய்! என்ன பண்ணணும்?")

    private fun named(name: String) = listOf("Hey $name!", "சொல்லுங்க $name!")

    /** The user's name, unless they'd rather not be greeted by it. */
    private fun nameFor(prefs: Prefs) = if (prefs.greetWithName) prefs.userName.trim() else ""

    private fun today(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
}
