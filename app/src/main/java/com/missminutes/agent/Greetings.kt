package com.missminutes.agent

import java.util.Calendar

/**
 * What she says the moment you wake her: a short hello with your name ("Hey Gokul!", "சொல்லுங்க Gokul!"),
 * and "Good morning" the first time each morning. Every line is spoken from a cached recording, so the
 * greeting costs no waiting.
 *
 * Each line was checked by having Sarvam say it and transcribing it back. A bare "Hmm" is not used: every
 * voice turns it into a short grunt. "Hmm, சொல்லுங்க…" with words after it comes out right.
 */
object Greetings {

    private var last: String? = null

    /** The greeting for this wake-up, never the same as the one before. */
    fun next(prefs: Prefs): String {
        val name = prefs.userName.trim()
        val today = today()
        val firstToday = prefs.lastGreetDay != today
        prefs.lastGreetDay = today
        if (firstToday && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 4..11) return morning(name)
        val line = short(name).filter { it != last }.random()
        last = line
        return line
    }

    /** Every line she might say, so they can all be downloaded ahead of time. */
    fun all(prefs: Prefs): List<String> {
        val name = prefs.userName.trim()
        return listOf(morning(name)) + short(name)
    }

    private fun morning(name: String) = if (name.isEmpty()) "Good morning! சொல்லுங்க?" else "Good morning $name! சொல்லுங்க?"

    private fun short(name: String) = if (name.isEmpty()) {
        listOf("Hey there!", "Hmm, சொல்லுங்க?", "Yes?", "சொல்லுங்க, கேக்கறேன்!", "ஹாய்! என்ன பண்ணணும்?")
    } else {
        listOf("Hey $name!", "Hmm, சொல்லுங்க $name?", "Yes $name?", "சொல்லுங்க $name!", "ஹாய் $name! என்ன பண்ணணும்?")
    }

    private fun today(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
}
