package com.missminutes.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTest {

    private fun split(earlyStart: Boolean, name: String = "", vararg chunks: String): List<String> {
        val out = mutableListOf<String>()
        val s = SentenceSplitter(earlyStart, name) { out += it }
        chunks.forEach(s::add)
        s.flush()
        return out
    }

    @Test fun splitsWholeSentences() {
        assertEquals(listOf("It's 3.5 degrees now.", "Take a jacket!"),
            split(false, "", "It's 3.5 deg", "rees now. Take a ", "jacket!"))
    }

    @Test fun firstPhraseEndsAtCommaInLiveMode() {
        val out = split(true, "", "Sure, I can set that alarm for you, it will ring at six in the morning.")
        assertEquals("Sure, I can set that alarm for you,", out.first())
    }

    @Test fun neverCutsJustBeforeTheUsersName() {
        val out = split(true, "Gokul", "Can I ask you something about you, Gokul? What's your favourite food?")
        assertTrue(out.first(), out.first().contains("Gokul?"))
    }

    @Test fun moodTagsAreTakenOutAndReported() {
        val moods = mutableListOf<String>()
        val spoken = StringBuilder()
        val tags = MoodTags({ moods += it }) { spoken.append(it) }
        tags.add("[hap"); tags.add("py] Done! [note] kept")
        tags.flush()
        assertEquals(listOf("happy"), moods)
        assertEquals(" Done! [note] kept", spoken.toString())
    }

    @Test fun quickRouterSendsSimpleCommandsToHaiku() {
        assertTrue(QuickRouter.isQuick("turn on the flashlight"))
        assertTrue(QuickRouter.isQuick("yes"))
        assertTrue(QuickRouter.isQuick("hotspot on"))
        assertTrue(QuickRouter.isQuick("what's the weather in Chennai")) // its own tool now: quick
        assertFalse(QuickRouter.isQuick("what's the news in Chennai"))
        assertFalse(QuickRouter.isQuick("explain how vaccines work"))
    }

    @Test fun clockRowsBecomeTimes() {
        assertEquals(Triple(9, 15, "Mon to Sat"), parseRow(listOf("9:15 ", "AM", "Mon to Sat", "On")))
        assertEquals(Triple(21, 30, "Ring once"), parseRow(listOf("9:30 PM", "Ring once")))
        assertEquals(Triple(0, 5, ""), parseRow(listOf("12:05", "am")))
        assertEquals(Triple(21, 15, "Every day"), parseRow(listOf("21:15", "Every day")))
        assertEquals(null, parseRow(listOf("Alarm", "Add Alarm")))
    }
}
