package com.missminutes.agent

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MemoryImportTest {

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun exportReadsBackTheSame() {
        val memories = listOf("Amma is saved as Mom", "I love Anirudh songs")
        assertEquals(memories, MemoryImport.read(MemoryImport.export(memories, 1L).toByteArray()).memories)
    }

    @Test fun plainListLosesBulletsAndNumbers() {
        val found = MemoryImport.read("- Works at Tidel Park\n• Vegetarian\n3) Has a dog called Bruno\n\nok".toByteArray())
        assertEquals(listOf("Works at Tidel Park", "Vegetarian", "Has a dog called Bruno"), found.memories)
    }

    @Test fun jsonListsOfObjectsWork() {
        val json = JSONArray().put(org.json.JSONObject().put("content", "Likes filter coffee")).put("Lives in Chennai").toString()
        assertEquals(listOf("Likes filter coffee", "Lives in Chennai"), MemoryImport.read(json.toByteArray()).memories)
    }

    @Test fun backupFilesGiveTheirMemories() {
        val backup = Backup.encode(BackupData(emptyMap(), listOf("Birthday is 12 March"), emptyMap(), emptyList()), 1L)
        assertEquals(listOf("Birthday is 12 March"), MemoryImport.read(backup.toByteArray()).memories)
    }

    @Test fun chatGptExportWithoutMemoriesSaysSo() {
        val found = MemoryImport.read(zip("conversations.json" to "[{},{},{}]", "chat.html" to "<html/>"))
        assertEquals("ChatGPT export", found.source)
        assertTrue(found.memories.isEmpty())
        assertNotNull(found.note)
        assertTrue(found.note!!.contains("3 conversations"))
    }

    @Test fun chatGptExportWithAMemoryFileUsesIt() {
        val found = MemoryImport.read(zip("conversations.json" to "[]", "memories.txt" to "Prefers Tamil\nDrives a Swift"))
        assertEquals(listOf("Prefers Tamil", "Drives a Swift"), found.memories)
    }

    @Test fun reviewLeavesOutWhatsAlreadySaved() {
        val review = MemoryImport.review(listOf("I love Anirudh songs"), listOf("i love anirudh songs.", "Vegetarian", "vegetarian"))
        assertEquals(listOf("Vegetarian"), review.fresh)
        assertEquals(2, review.duplicates)
    }

    @Test fun unusableFilesGetAFriendlyMessage() {
        for (bad in listOf(ByteArray(0), "{\"hello\":1}".toByteArray(), zip("photo.jpg" to "x"))) {
            try { MemoryImport.read(bad); fail("accepted") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.isNotBlank()) }
        }
    }

    @Test fun recentKeepsTheLastTwenty() {
        var list = emptyList<Turn>()
        repeat(25) { list = Recent.append(list, Turn(it.toLong(), "q$it", "a$it", if (it % 2 == 0) "Chef" else null)) }
        assertEquals(Recent.KEEP, list.size)
        assertEquals("q24", list.last().heard)
        assertEquals(list, Recent.parse(Recent.encode(list)))
    }
}
