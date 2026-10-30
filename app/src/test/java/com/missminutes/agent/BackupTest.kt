package com.missminutes.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {

    private val chef = Agent(id = "a1", name = "Chef", purpose = "Recipes")
    private val coach = Agent(id = "a2", name = "Coach", purpose = "Workouts")

    private val phone = BackupData(
        settings = mapOf("userName" to "Gokul", "picoLive" to true, "voice" to "kavya"),
        memories = listOf("Amma is saved as Mom", "I love Anirudh songs"),
        routines = mapOf("good night" to "silent mode"),
        agents = listOf(chef),
    )

    private val file = BackupData(
        settings = mapOf("voice" to "vijay", "assistantName" to "Nova"),
        memories = listOf("i love anirudh songs", "My office is at Tidel Park"),
        routines = mapOf("good night" to "silent mode, alarm at 6", "focus" to "do not disturb"),
        agents = listOf(chef.copy(purpose = "Quick recipes"), coach),
    )

    @Test fun restoringAddsAndNeverLosesWhatsOnThePhone() {
        val merged = Backup.merge(phone, file)
        assertEquals(mapOf("userName" to "Gokul", "picoLive" to true, "voice" to "vijay", "assistantName" to "Nova"), merged.settings)
        // A memory already there (any case) isn't added twice.
        assertEquals(listOf("Amma is saved as Mom", "I love Anirudh songs", "My office is at Tidel Park"), merged.memories)
        assertEquals(mapOf("good night" to "silent mode, alarm at 6", "focus" to "do not disturb"), merged.routines)
        assertEquals(listOf(chef.copy(purpose = "Quick recipes"), coach), merged.agents)
    }

    @Test fun backupSurvivesEncodeAndDecode() {
        val back = Backup.decode(Backup.encode(phone, exportedAt = 1L))
        assertEquals(phone, back)
    }

    @Test fun backupNeverContainsKeys() {
        val text = Backup.encode(phone, exportedAt = 1L).lowercase()
        assertFalse(text.contains("key"))
        assertFalse(text.contains("sk-ant"))
    }

    @Test fun otherFilesAreRefusedWithAFriendlyMessage() {
        for (bad in listOf("hello", "{}", """{"format":"pico-backup","version":99}""")) {
            try {
                Backup.decode(bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }
}
