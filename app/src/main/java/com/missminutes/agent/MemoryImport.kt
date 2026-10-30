package com.missminutes.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reading memories from a file: this app's memory export, a full backup, a plain list (one per line, as copied
 * from ChatGPT's Memory page), or a ChatGPT data export (.zip). Nothing is saved here; the app shows what was
 * found and the user picks what to add.
 */
object MemoryImport {
    const val FORMAT = "pico-memories"

    /** What a file held. [note] explains anything worth knowing, like a ChatGPT export without saved memories. */
    data class Found(val source: String, val memories: List<String>, val note: String? = null)

    /** The found memories split into new ones and ones already saved (ignoring case and end punctuation). */
    data class Review(val fresh: List<String>, val duplicates: Int)

    fun export(memories: List<String>, exportedAt: Long): String = JSONObject()
        .put("format", FORMAT).put("version", 1).put("exportedAt", exportedAt)
        .put("memories", JSONArray(memories))
        .toString(2)

    /** Reads [bytes]; throws [IllegalArgumentException] with a friendly message when nothing can be used. */
    fun read(bytes: ByteArray): Found {
        require(bytes.isNotEmpty()) { "That file is empty." }
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return readZip(bytes)
        val text = bytes.decodeToString().removePrefix("﻿").trim()
        return fromJson(text) ?: Found("Text file", lines(text)).also {
            require(it.memories.isNotEmpty()) { "No memories were found in that file." }
        }
    }

    fun review(existing: List<String>, found: List<String>): Review {
        val seen = existing.map(::key).toMutableSet()
        val fresh = ArrayList<String>()
        var duplicates = 0
        for (m in found) {
            if (seen.add(key(m))) fresh += m else duplicates++
        }
        return Review(fresh, duplicates)
    }

    private fun key(m: String) = m.trim().trimEnd('.', '!', ' ').lowercase().replace(Regex("\\s+"), " ")

    /** One memory per line; bullets and numbering are taken off. */
    fun lines(text: String): List<String> = text.lines()
        .map { it.trim().replace(Regex("^([-*•·]|\\d+[.)])\\s*"), "").trim() }
        .filter { it.length in 3..300 }

    private fun fromJson(text: String): Found? {
        if (!text.startsWith("{") && !text.startsWith("[")) return null
        val value = runCatching { if (text.startsWith("{")) JSONObject(text) else JSONArray(text) }.getOrNull() ?: return null
        if (value is JSONObject) {
            when (value.optString("format")) {
                FORMAT -> return Found("Memory export", strings(value.optJSONArray("memories")))
                Backup.FORMAT -> return Found("Backup file", Backup.decode(text).memories)
            }
            value.optJSONArray("memories")?.let { return Found("Memory file", strings(it)) }
            throw IllegalArgumentException("That file doesn't have memories in a format this app knows.")
        }
        return Found("Memory file", strings(value as JSONArray))
    }

    /** Strings, or objects with the fact under a common name ("content", "text", "memory"). */
    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return List(a.length()) { i ->
            when (val v = a.opt(i)) {
                is String -> v
                is JSONObject -> listOf("content", "text", "memory", "value").firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() } }
                else -> null
            }
        }.filterNotNull().map { it.trim() }.filter { it.length in 3..300 }
    }

    /**
     * A ChatGPT data export holds conversations.json (and chat.html), but usually not your saved memories:
     * ChatGPT doesn't include them. Conversations are counted, not turned into memories.
     */
    private fun readZip(bytes: ByteArray): Found {
        var conversations: Int? = null
        var chatGpt = false
        val memories = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/').lowercase()
                when {
                    name == "conversations.json" -> {
                        chatGpt = true
                        conversations = runCatching { JSONArray(zip.readBytes().decodeToString()).length() }.getOrNull()
                    }
                    name in setOf("chat.html", "user.json", "message_feedback.json") -> chatGpt = true
                    name.startsWith("memor") && (name.endsWith(".json") || name.endsWith(".txt")) -> {
                        val text = zip.readBytes().decodeToString().trim()
                        memories += runCatching { fromJson(text)?.memories }.getOrNull() ?: lines(text)
                    }
                }
            }
        }
        require(chatGpt || memories.isNotEmpty()) { "That zip file isn't a ChatGPT export or a memory file." }
        val count = conversations?.let { "$it conversations" } ?: "conversations"
        val note = when {
            memories.isNotEmpty() -> null
            else -> "This ChatGPT export has $count but no saved memories: ChatGPT doesn't include them in exports. " +
                "To bring them over, open ChatGPT → Settings → Personalization → Memory, copy them, and use Paste memories here."
        }
        return Found("ChatGPT export", memories, note)
    }
}
