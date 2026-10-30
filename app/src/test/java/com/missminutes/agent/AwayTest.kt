package com.missminutes.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwayTest {
    private val now = 1_000_000_000L

    @Test fun starredAndUrgentCallersRingEveryoneElseIsDeclined() {
        assertEquals(Away.Decision.RING, Away.decide(starred = true, letStarred = true, earlierCalls = emptyList(), letRepeat = true, now = now))
        assertEquals(Away.Decision.DECLINE, Away.decide(starred = true, letStarred = false, earlierCalls = emptyList(), letRepeat = true, now = now))
        assertEquals(Away.Decision.RING, Away.decide(false, true, listOf(now - 3 * 60_000), true, now))
        assertEquals(Away.Decision.DECLINE, Away.decide(false, true, listOf(now - 20 * 60_000), true, now))
        assertEquals(Away.Decision.DECLINE, Away.decide(false, true, listOf(now - 60_000), false, now))
    }

    @Test fun endsAtItsTimeOrAfterTwelveHours() {
        assertTrue(Away.active(AwayState("in a meeting", now, now + 60_000), now + 30_000))
        assertFalse(Away.active(AwayState("in a meeting", now, now + 60_000), now + 61_000))
        assertTrue(Away.active(AwayState("busy", now, 0), now + 11 * 3_600_000L))
        assertFalse(Away.active(AwayState("busy", now, 0), now + 13 * 3_600_000L))
    }

    @Test fun summaryGroupsByPerson() {
        val events = listOf(
            AwayEvent(now, "call", "Ravi"), AwayEvent(now + 1, "call", "Ravi"), AwayEvent(now + 2, "call", "Amma", rang = true),
            AwayEvent(now + 3, "message", "Ravi", "Are you coming tomorrow?", "WhatsApp"),
        )
        val text = Away.summary(AwayState("in a meeting", now, 0), events, now + 10)
        assertEquals("While you were in a meeting: Ravi called twice, Amma called (it rang through). " +
            "1 message: Ravi on WhatsApp: “Are you coming tomorrow?”.", text)
        assertEquals("While you were away, nobody called or messaged.", Away.summary(null, emptyList(), now))
    }

    @Test fun eventsSurviveSaving() {
        val events = listOf(AwayEvent(now, "message", "Amma", "Call me", "WhatsApp", replied = true), AwayEvent(now, "call", "+91 98400 12345"))
        assertEquals(events, Away.parseEvents(Away.encodeEvents(events)))
    }

    @Test fun brandsAndSenderIdsAreNotAnswered() {
        assertTrue(Away.looksLikePerson("Ravi"))
        assertTrue(Away.looksLikePerson("+91 98400 12345"))
        assertFalse(Away.looksLikePerson("JX-HDFCBK"))
        assertFalse(Away.looksLikePerson("AD-AIRTEL"))
    }

    @Test fun ownAndOtherAutoRepliesAreNeverAnswered() {
        val ours = "Hi, Gokul is in a meeting until 8:54 AM and can't take calls. This is Pico, Gokul's assistant. I'll let them know you got in touch."
        assertTrue(Away.isOwnReply(ours, "Pico"))
        assertTrue(Away.isOwnReply(ours.replace("Pico", "Nova Star"), "Pico")) // renamed, or the other account's assistant
        assertFalse(Away.isOwnReply("hey, are you free?", "Pico"))
        assertTrue(Away.isAutoReply("Auto-reply: I'm out of office until Monday"))
        assertFalse(Away.isAutoReply("hey"))
    }

    @Test fun theSameMessageTwiceCountsOnce() {
        var list = Away.append(emptyList(), AwayEvent(now, "message", "My Jio", "hey gokul", "WhatsApp", replied = true))
        list = Away.append(list, AwayEvent(now + 6, "message", "My Jio", "hey gokul", "WhatsApp"))
        list = Away.append(list, AwayEvent(now + 60_000, "message", "My Jio", "hey gokul", "WhatsApp")) // said again later: counts
        assertEquals(2, list.size)
    }

    @Test fun whatsAppCallsAreCountedAsCalls() {
        val events = listOf(AwayEvent(now, "call", "Arun", app = "WhatsApp"), AwayEvent(now + 1, "call", "Arun", app = "WhatsApp"),
            AwayEvent(now + 2, "call", "Arun"))
        assertEquals("While you were away: Arun called 3 times on WhatsApp.", Away.summary(null, events, now))
    }

    @Test fun doubledAmPmIsCleanedWhateverTheSpaces() {
        assertEquals("in a meeting until 12:12 PM and busy", Away.tidyTime("in a meeting until 12:12 PM AM and busy"))
        assertEquals("back at 9:00 AM.", Away.tidyTime("back at 9:00 AM am."))
        assertEquals("I am fine", Away.tidyTime("I am fine"))
    }
}
