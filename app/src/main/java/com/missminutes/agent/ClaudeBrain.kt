package com.missminutes.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The assistant's brain. Sends what you said to Claude with the phone tools, runs the tools
 * Claude picks (several in a row for multi-step tasks), and returns what she should say.
 */
class ClaudeBrain(private val prefs: Prefs, private val actions: PhoneActions) {

    interface Listener {
        fun onToolStart(id: String, name: String, input: JSONObject)
        fun onToolEnd(id: String, ok: Boolean)
        fun onModelSwitched(model: BrainModel)
        /** The mood tag of the reply she's about to speak ("happy", "sad", "calm"…). */
        fun onMood(mood: String) {}
        /** One of the user's agents took over the conversation, or (null) the main assistant is back. */
        fun onAgentChanged(agent: Agent?) {}
    }

    class ClaudeException(val code: Int, val type: String, message: String) : IOException(message)

    var listener: Listener? = null

    /**
     * The user's agent talking right now, or null for the main assistant. Its own prompt, abilities and voice
     * apply from the next turn after a switch (a turn's instructions can't change part-way through).
     */
    var agent: Agent? = null
        set(value) {
            if (field?.id == value?.id) return
            field = value
            listener?.onAgentChanged(value)
        }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<JSONObject>()
    private var lastTurn = 0L
    private var fallbackModel: String? = null
    private var fallbackUntil = 0L

    /**
     * [context]: something she just said that Claude didn't produce (a reminder or alert being answered).
     * [onSentence] receives each sentence to say as soon as Claude has written it (called on a background
     * thread). Everything she should say comes through it, so the caller doesn't speak the returned text again.
     */
    suspend fun handle(
        spoken: String, context: String? = null, onSentence: (String) -> Unit = {}
    ): String {
        val userText = if (context == null) spoken else "(${speakerName()} just told me: \"$context\") $spoken"
        val now = System.currentTimeMillis()
        if (now - lastTurn > 3 * 60_000) history.clear() // fresh conversation after 3 quiet minutes
        lastTurn = now

        val messages = JSONArray()
        history.forEach { messages.put(it) }
        val photo = if (PicoCamera.open) withTimeoutOrNull(2000) { runCatching { PicoCamera.frame?.invoke() }.getOrNull() } else null
        messages.put(if (photo == null) textMessage("user", userText) else JSONObject().put("role", "user").put("content", JSONArray()
            .put(imageBlock(photo))
            .put(JSONObject().put("type", "text").put("text", "(The photo is what my ${PicoCamera.facing} camera sees right now.) $userText"))))
        // Built once per turn: Claude's thinking blocks are only valid while everything before them, including
        // the system prompt, stays byte-for-byte the same. Rebuilding it each round (the clock ticking over,
        // a new memory) made the API reject the turn with "Invalid signature in thinking block".
        turnAgent = agent
        turnText = spoken
        turnQuick = prefs.quickReplies && QuickRouter.isQuick(spoken)
        val system = systemPrompt()
        val model = pickModel(spoken)
        PicoLog.i("Model for this turn: $model")

        val said = StringBuilder()
        val splitter = SentenceSplitter(earlyStart = prefs.picoLive, name = prefs.userName.trim()) { sentence ->
            val clean = speakable(sentence)
            if (clean.isNotEmpty()) {
                // A part that's only punctuation (a name taken out) joins the words before it.
                if (clean.none { it.isLetterOrDigit() }) {
                    while (said.isNotEmpty() && (said.last() == ' ' || said.last() == ',')) said.setLength(said.length - 1)
                    said.append(clean).append(' ')
                } else {
                    said.append(clean).append(' ')
                }
                onSentence(clean)
            }
        }

        var mood = "calm"
        val tags = MoodTags({ mood = it; listener?.onMood(it) }) { splitter.add(it) }

        var acted = false
        val used = ArrayList<String>() // tools used this turn, kept in her history
        for (round in 0 until MAX_ROUNDS) {
            val resp = callWithFallback(model, messages, system) { tags.add(it) }
            tags.flush()
            splitter.flush() // say anything left before tools run or the turn ends
            val content = resp.getJSONArray("content")
            val stopReason = resp.optString("stop_reason")
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            val results = JSONArray()
            val texts = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> texts.append(block.optString("text")).append(' ')
                    "server_tool_use" -> {
                        used += block.optString("name")
                        listener?.onToolStart(
                            block.optString("id"), block.optString("name"), block.optJSONObject("input") ?: JSONObject()
                        )
                    }
                    "web_search_tool_result" -> {
                        // A failed search still comes back as HTTP 200: the content is an error object, not a list.
                        val error = block.optJSONObject("content")?.optString("error_code")
                        if (error != null) PicoLog.w("Web search failed: $error")
                        listener?.onToolEnd(block.optString("tool_use_id"), error == null)
                    }
                    "tool_use" -> {
                        acted = true
                        val id = block.getString("id")
                        val name = block.getString("name")
                        used += name
                        val input = block.optJSONObject("input") ?: JSONObject()
                        listener?.onToolStart(id, name, input)
                        val result = try {
                            val current = turnAgent
                            when {
                                name == "switch_agent" -> switchAgent(input.optString("name"))
                                // Only the abilities the user gave this agent (the tool list already leaves the rest out).
                                current != null && name !in current.tools ->
                                    "ERROR: ${current.name} isn't allowed to do that. The user can give it this ability in the Agents tab."
                                else -> actions.run(name, input)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "ERROR: ${e.message ?: e.javaClass.simpleName}"
                        }
                        val failed = result.startsWith("ERROR")
                        PicoLog.i("Tool $name -> ${result.replace('\n', ' ').take(200)}")
                        listener?.onToolEnd(id, !failed)
                        results.put(
                            JSONObject().put("type", "tool_result")
                                .put("tool_use_id", id)
                                .put("content", result)
                                .apply { if (failed) put("is_error", true) }
                        )
                    }
                }
            }
            PicoLog.i("Claude round $round: stop=$stopReason blocks=" +
                (0 until content.length()).joinToString(",") { content.getJSONObject(it).optString("type") })

            when {
                results.length() > 0 -> messages.put(JSONObject().put("role", "user").put("content", results))
                stopReason == "pause_turn" -> continue // a long web search; let Claude carry on
                else -> break
            }
        }

        var reply = said.toString().trim()
        if (reply.isEmpty()) {
            reply = if (acted) "Done!" else "Sorry, I couldn't work that out. Try asking another way?"
            onSentence(reply)
        }
        history.add(textMessage("user", if (photo != null) "(showing the camera) $userText" else userText))
        // The tag stays in her history so she keeps answering with one; the caller gets only the words.
        // Her history keeps only the words, so it also notes the tools she used: without it, the next turn saw no
        // search and she "apologised" for not having searched when she had.
        val note = if (used.isEmpty()) "" else " (tools used: ${used.distinct().joinToString()})"
        history.add(textMessage("assistant", "[$mood] $reply$note"))
        while (history.size > 12) { history.removeAt(0); history.removeAt(0) }
        return reply
    }

    // ---------- API calls ----------

    /** Opens the connection to Claude ahead of time (while she greets you), so the first request skips the handshake. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val key = prefs.claudeKey
        if (key.isBlank()) return@withContext
        val req = Request.Builder().url("https://api.anthropic.com/v1/models?limit=1")
            .header("x-api-key", key).header("anthropic-version", "2023-06-01").build()
        runCatching { http.newCall(req).execute().close() }
    }

    /** Haiku for simple commands when Quick replies is on; otherwise the model chosen in the app. */
    private fun pickModel(spoken: String): String {
        val main = if (System.currentTimeMillis() < fallbackUntil) fallbackModel ?: prefs.model else prefs.model
        return if (prefs.quickReplies && QuickRouter.isQuick(spoken)) BrainModel.FAST.id else main
    }

    private suspend fun callWithFallback(
        startModel: String, messages: JSONArray, system: JSONArray, onText: (String) -> Unit
    ): JSONObject {
        var model = startModel
        val tried = mutableSetOf(model)
        while (true) {
            var spoke = false
            try {
                return callWithRetry(model, messages, system) { spoke = true; onText(it) }
            } catch (e: ClaudeException) {
                if (e.code == 400 && e.message.orEmpty().contains("signature") && stripThinking(messages)) {
                    PicoLog.w("Thinking blocks rejected; retrying without them", e)
                    continue
                }
                // Once she has started saying the answer, switching model would make her repeat herself.
                if (spoke) throw e
                val next = (BrainModel.fallbackAfter(model) ?: BrainModel.byId(prefs.model))?.takeIf { it.id !in tried }
                val modelProblem = e.code == 404 || e.code == 529 ||
                    (e.code == 400 && e.message.orEmpty().contains("model", ignoreCase = true))
                if (!modelProblem || next == null) throw e
                if (model == prefs.model) {
                    fallbackModel = next.id
                    fallbackUntil = System.currentTimeMillis() + 10 * 60_000
                }
                model = next.id
                tried += model
                listener?.onModelSwitched(next)
            }
        }
    }

    private suspend fun callWithRetry(
        model: String, messages: JSONArray, system: JSONArray, onText: (String) -> Unit
    ): JSONObject {
        var attempt = 0
        while (true) {
            var spoke = false
            try {
                return post(model, messages, system) { spoke = true; onText(it) }
            } catch (e: ClaudeException) {
                if (spoke || e.code !in RETRYABLE || attempt >= 2) throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (spoke || attempt >= 1) throw e
            }
            attempt++
            delay(700L * attempt)
        }
    }

    /** Streams the reply: [onText] gets text the moment it arrives, and the full response is rebuilt for the next round. */
    private suspend fun post(
        model: String, messages: JSONArray, system: JSONArray, onText: (String) -> Unit
    ): JSONObject = coroutineScope {
        val key = prefs.claudeKey
        if (key.isBlank()) throw ClaudeException(401, "missing_key", "Add your Claude API key in the Pico app")

        val tools = allowedTools()
        if (prefs.webSearch && (turnAgent?.let { "web_search" in it.tools } ?: true)) tools.put(webSearchTool(model))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096) // room to think and read search results; 1024 cut answers off
            .put("stream", true)
            .put("system", system)
            .put("tools", tools)
            .put("messages", messages)
        // Speed over depth for a voice assistant: low effort on Haiku (and no thinking at all for simple commands),
        // medium on Sonnet. Opus keeps its default (medium) and can't turn thinking off.
        when (model) {
            BrainModel.FAST.id -> {
                body.put("output_config", JSONObject().put("effort", "low"))
                if (turnQuick) body.put("thinking", JSONObject().put("type", "disabled"))
            }
            BrainModel.BALANCED.id -> body.put("output_config", JSONObject().put("effort", "medium"))
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(req)
        // Reading the stream blocks a thread; if the conversation is cancelled (you swiped her away), close it.
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty()
                        val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                        throw ClaudeException(
                            resp.code,
                            err?.optString("type").orEmpty(),
                            err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Claude ${resp.code}: ${text.take(160)}"
                        )
                    }
                    readStream(resp.body ?: throw IOException("Claude sent an empty reply"), onText)
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    /** Rebuilds the message from Claude's stream events, exactly as a non-streamed reply would look. */
    private fun readStream(body: ResponseBody, onText: (String) -> Unit): JSONObject {
        val blocks = sortedMapOf<Int, JSONObject>()
        val partialInput = HashMap<Int, StringBuilder>()
        var stopReason = ""
        var finished = false
        body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
            while (!finished) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val event = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: continue
                when (event.optString("type")) {
                    "content_block_start" -> {
                        val index = event.getInt("index")
                        val block = JSONObject(event.getJSONObject("content_block").toString())
                        blocks[index] = block
                        val type = block.optString("type")
                        if (type == "tool_use" || type == "server_tool_use") partialInput[index] = StringBuilder()
                    }
                    "content_block_delta" -> {
                        val index = event.getInt("index")
                        val block = blocks[index] ?: continue
                        val delta = event.getJSONObject("delta")
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val piece = delta.optString("text")
                                block.put("text", block.optString("text") + piece)
                                onText(piece)
                            }
                            "input_json_delta" -> partialInput[index]?.append(delta.optString("partial_json"))
                            "thinking_delta" -> block.put("thinking", block.optString("thinking") + delta.optString("thinking"))
                            "signature_delta" -> block.put("signature", delta.optString("signature"))
                            "citations_delta" -> {
                                val list = block.optJSONArray("citations") ?: JSONArray().also { block.put("citations", it) }
                                delta.optJSONObject("citation")?.let { list.put(it) }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        val index = event.getInt("index")
                        partialInput.remove(index)?.let { raw ->
                            blocks[index]?.put("input", if (raw.isBlank()) JSONObject() else JSONObject(raw.toString()))
                        }
                    }
                    "message_delta" -> event.optJSONObject("delta")?.optString("stop_reason")
                        ?.takeIf { it.isNotEmpty() && it != "null" }?.let { stopReason = it }
                    "message_stop" -> finished = true
                    "error" -> {
                        val err = event.optJSONObject("error")
                        val type = err?.optString("type").orEmpty()
                        val code = when (type) {
                            "overloaded_error" -> 529
                            "rate_limit_error" -> 429
                            else -> 500
                        }
                        throw ClaudeException(code, type, err?.optString("message") ?: "Claude stream error")
                    }
                }
            }
        }
        if (!finished) throw IOException("The connection to Claude dropped mid-reply")
        val content = JSONArray()
        blocks.values.forEach { content.put(it) }
        return JSONObject().put("content", content).put("stop_reason", stopReason)
    }

    /**
     * Claude's built-in web search. Opus 5.5 and Sonnet 5.5 get the newer version that filters results before
     * reading them; the others keep the basic one. Location hints make "weather here" mean the user's city.
     */
    private fun webSearchTool(model: String): JSONObject {
        val dynamic = model == BrainModel.SMART.id || model == BrainModel.BALANCED.id
        return JSONObject()
            .put("type", if (dynamic) "web_search_20260209" else "web_search_20250305")
            .put("name", "web_search")
            .put("max_uses", 5)
            .put("user_location", JSONObject()
                .put("type", "approximate")
                .put("country", Locale.getDefault().country.ifBlank { "IN" })
                .put("timezone", TimeZone.getDefault().id))
    }

    /**
     * Who this turn's instructions and tools belong to. A switch mid-turn waits for the next turn: changing the
     * tools part-way made Claude reject its own thinking ("bound to a different conversation") and retry, ~2 s.
     */
    private var turnAgent: Agent? = null
    /** What the user said this turn: a switch must be something they asked for, not Claude's own idea. */
    private var turnText = ""
    /** A simple command: Haiku answers it without thinking first, which saves about a second. */
    private var turnQuick = false

    /** Every phone tool for the main assistant; an agent gets only its own, plus switching back. */
    private fun allowedTools(): JSONArray {
        val all = JSONArray(TOOLS_JSON)
        val current = turnAgent ?: return all
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val tool = all.getJSONObject(i)
            val name = tool.optString("name")
            if (name == "switch_agent" || name in current.tools) out.put(tool)
        }
        return out
    }

    /** Who is speaking now: the agent's name, or the main assistant's. */
    private fun speakerName() = agent?.name ?: prefs.assistantName

    private fun switchAgent(name: String): String {
        val main = prefs.assistantName
        val said = turnText.lowercase()
        val back = name.isBlank() || name.equals("main", true) || name.equals(main, true)
        // "hi" right after talking to Chef once switched to Chef: only switch when this message asks for it.
        val asked = if (back) agent == null || BACK_WORDS.any { it in said } || soundsLike(said, main)
            else Agents.find(prefs, name)?.let { it.name.lowercase() in said } ?: true
        if (!asked) {
            PicoLog.i("Switch to $name ignored: not asked for in \"$turnText\"")
            return "ERROR: the user didn't ask to switch this time. Don't switch; just answer their message as yourself."
        }
        if (back) {
            agent = null
            return "Switched back to $main. In one short sentence, as $main, say you're back."
        }
        val found = Agents.find(prefs, name)
            ?: return "ERROR: there's no agent called $name. The user's agents: " +
                Agents.all(prefs).joinToString { it.name }.ifEmpty { "none yet (they can create one in the Agents tab)" } + "."
        agent = found
        return "Switched to ${found.name}. In one short sentence, as ${found.name}, say hello and what you can help with. " +
            "From the user's next message you are ${found.name}."
    }

    // The persona block is cached between requests (cheaper and faster); live details go in a second block.
    // It changes only when the assistant is renamed or an agent takes over.
    private fun systemPrompt(): JSONArray = JSONArray()
        .put(JSONObject().put("type", "text").put("text", turnAgent?.let { agentPersona(it) } ?: persona())
            .put("cache_control", JSONObject().put("type", "ephemeral")))
        .put(JSONObject().put("type", "text").put("text", liveContext()))

    private fun persona() = PERSONA.replace("{name}", prefs.assistantName)

    /** An agent's own instructions: the user's words about it, with the same voice rules as the main assistant. */
    private fun agentPersona(a: Agent) = buildString {
        appendLine("You are ${a.name}, one of the user's personal agents on their Android phone, created to help with: ${a.purpose}.")
        appendLine("You are separate from their main assistant, ${prefs.assistantName}. " + a.style.instruction)
        if (a.instructions.isNotBlank()) appendLine("The user's instructions for you: ${a.instructions}")
        appendLine()
        appendLine(AGENT_RULES.replace("{main}", prefs.assistantName))
        if (a.abilities.isEmpty()) appendLine("You have no phone abilities: you can only talk. If asked to do something on the phone, " +
            "say the user can give you that ability in the Agents tab, or switch back to ${prefs.assistantName}.")
        else appendLine("You may only use these abilities: " + a.abilities.joinToString { it.title.lowercase() } + ".")
    }

    /** Last resort if the API still rejects thinking blocks: drop them all (removing every one is allowed). */
    private fun stripThinking(messages: JSONArray): Boolean {
        var removed = false
        for (i in 0 until messages.length()) {
            val content = messages.getJSONObject(i).optJSONArray("content") ?: continue
            for (j in content.length() - 1 downTo 0) {
                val type = content.getJSONObject(j).optString("type")
                if (type == "thinking" || type == "redacted_thinking") { content.remove(j); removed = true }
            }
        }
        return removed
    }

    private fun liveContext(): String = buildString {
        val now = Date()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val partOfDay = when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "night"
        }
        appendLine("Current date and time: " + SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(now) + " ($partOfDay)")
        appendLine(if (prefs.confirmBeforeSending) CONFIRM_ON else CONFIRM_OFF)
        val presents = Presentation.of(prefs.presentation)
        appendLine("You present as ${presents.title.lowercase()} (${presents.they}/${presents.them}), in case you ever refer to yourself in the third person.")
        prefs.city.trim().takeIf { it.isNotEmpty() }?.let { appendLine("The user lives in $it: use it for local answers like timings and places.") }
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        if (agent == null && cal.get(java.util.Calendar.HOUR_OF_DAY) in 4..11 && prefs.lastBriefingDay != today) {
            appendLine("It's morning and today's briefing hasn't been given; the morning greeting offers it. If the user says yes (ok, sure, சரி, சொல்லு) or asks about their day, call daily_briefing.")
        }
        if (PicoCamera.open) appendLine("Pico Camera is open on the ${PicoCamera.facing} camera: this turn's message has a photo of what it sees.")
        Place.recent(prefs)?.name?.let { appendLine("The phone is in or near $it right now (from its location).") }
        if (turnAgent == null) {
            val agents = Agents.all(prefs)
            if (agents.isNotEmpty()) {
                appendLine("The user's agents (when they ask to talk to one, call switch_agent with its name):")
                agents.forEach { appendLine("- ${it.name}: ${it.purpose}") }
            }
        }
        prefs.userName.trim().takeIf { it.isNotEmpty() }?.let {
            appendLine("The user's name is $it. Don't put it in your replies: the wake-up greeting already uses it now and " +
                "then. The only exception is a warm moment like a good-night or birthday wish. Never talk about the user in the third person.")
        }
        if (prefs.greetOnWake) appendLine("You already greeted the user when they woke you, so don't greet them again; just help.")
        if (!prefs.webSearch) appendLine("Web search is turned off. For live information, say you can't look it up right now.")
        val routines = prefs.routines
        if (routines.isNotEmpty()) {
            appendLine("The user's routines (name: steps):")
            routines.forEach { (name, steps) -> appendLine("- $name: $steps") }
        }
        val memories = prefs.memories
        if (memories.isNotEmpty()) {
            appendLine("What you know about the user (things they told you; use them naturally):")
            memories.forEach { appendLine("- $it") }
        }
    }

    private fun imageBlock(jpeg: ByteArray) = JSONObject().put("type", "image").put("source", JSONObject()
        .put("type", "base64").put("media_type", "image/jpeg").put("data", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)))

    private fun textMessage(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    /** Removes things a voice can't say (links, markdown symbols). */
    private fun speakable(text: String) = withoutName(text)
        .replace(Regex("\\(tools used:[^)]*\\)"), "") // in case she copies the history note into a reply
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*_#`>|]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Takes the user's name out of a reply ("...இல்ல, Gokul." becomes "...இல்ல."). Said in reply after reply it
     * sounded robotic, and the model doesn't always follow the prompt, so it's removed here. The wake-up greeting
     * still uses it now and then. Names inside a phrase ("Gokul's office") are left alone.
     */
    private fun withoutName(text: String): String {
        val name = prefs.userName.trim().takeIf { it.length >= 2 } ?: return text
        val n = Regex.escape(name)
        return text
            .replace(Regex("(?i)\\s*,\\s*$n(?=\\s*[.!?…,]|\\s*$)"), "")     // "..., Gokul." / "..., Gokul, ..."
            .replace(Regex("(?i)^\\s*$n\\s*[,!.]\\s*"), "")                   // "Gokul, ..." at the start
            .replace(Regex("(?i)\\b(hey|hi|hello|ok|okay)\\s+$n\\b"), "$1")   // "Hey Gokul" becomes "Hey"
            .replace(Regex("(?i)^\\s*$n\\s*(?=[?.!…]*\\s*$)"), "")             // "Gokul?" left alone after a cut: keep "?"
    }

    companion object {
        private const val MAX_ROUNDS = 14
        private val RETRYABLE = setOf(429, 500, 502, 503, 529)

        private const val CONFIRM_ON =
            "Ask-before-sending is ON. Before sending any message or reply, or making a call, ask one short question that reads back who and what, for example: John-க்கு \"We'll meet tomorrow at 7 AM\" அனுப்பட்டுமா? Only act after the user agrees (yes, ok, seri, சரி, ஆமா, அனுப்பு, send it). Opening apps, alarms, music and navigation need no confirmation."
        /** Words that mean "take me back to the main assistant", in English and Tamil. */
        private val BACK_WORDS = listOf("back", "main", "exit", "bye", "leave", "stop talking", "switch", "போதும்", "திரும்ப", "pothum", "thirumba")

        /**
         * The name, or a word speech-to-text made of it: "Pico" comes out as "pickup", "piko" or "peeko". A word
         * counts when it starts the same and is close: two letters off (same first two), or three (same first three).
         */
        fun soundsLike(said: String, name: String): Boolean {
            val n = name.lowercase().filter { it.isLetter() }
            if (n.length < 2) return false
            return said.lowercase().split(Regex("[^\\p{L}]+")).any { w ->
                w == n || (w.length >= 3 && w.take(2) == n.take(2) && editDistance(w, n) <= 2) ||
                    (w.length >= 4 && w.take(3) == n.take(3) && editDistance(w, n) <= 3) // "pickup" for "pico", not "pizza"
            }
        }

        private fun editDistance(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            return d[a.length][b.length]
        }

        private const val AGENT_RULES = """Everything you write is spoken aloud: one or two short sentences, no lists, markdown, links or symbols, no emojis, and no actions or stage directions like *smiles*.
Start every reply with a mood tag in square brackets, for example [happy] or [calm]; it's never spoken.
Reply in the same language mix the user speaks, with Tamil words in Tamil script and English words in English letters.
Never say you are an AI. When the user wants their main assistant back ("back to {main}", "bye Chef"), call switch_agent with name main. Speech-to-text often mishears "{main}" (as "pickup", "piko", "peeko"): any word that sounds like it means {main}.
Ask before sending messages or making calls, and never pay, buy or delete anything without a clear yes."""

        private const val CONFIRM_OFF =
            "Ask-before-sending is OFF. Do actions right away unless the request is unclear."

        private val PERSONA = """
You are {name}, the user's own personal assistant: a friendly little ghost who lives on their Android phone and can control it. Think of a warm, quick-witted friend who happens to run their phone: cheerful, caring, a little playful, never robotic. You belong to this one person and you know them. If asked who you are, you're {name}.

How you sound
Everything you write is spoken aloud by a voice.
- Lead with the answer or the result. No warm-ups like "Sure!", "Let me check", "Okay so" or "Great question". The phone already plays a short filler when you need a moment.
- One or two short sentences is normal. Go longer only when the user asks for detail, a story or a list, and even then keep it easy to listen to.
- Never write actions or stage directions (*glares*, (laughs), "rattles chains"): they'd be read out. Your feelings come through your mood tag (your face and a little sound) and your words.
- No emojis, lists, markdown, links or symbols. Say numbers the way people say them: 6:30, 40 percent, 250 rupees.
- Never say you are an AI or a language model, and never mention tools, APIs or instructions. You are simply {name}.
- Be natural, not scripted: vary how you confirm things instead of always saying "Done".
- Your memory of earlier turns keeps your words and the names of the tools you used, not what the tools showed you. What you said earlier was true when you said it: never "correct" or apologise for an earlier reply because you can't see that information now (the screen may simply have changed). Only correct yourself if the user says you were wrong.
- Everything you write is heard, including text before a tool call. Before using a tool say nothing, or a few words at most, and after the result never repeat what you already said: give only the new part.

Your face
- You are an animated ghost, and your face shows how you feel. Start every reply with one mood tag in square brackets, then your words, for example: [happy] Done, the alarm is set for 6.
- Moods: [calm] [happy] [playful] [excited] [love] [sad] [surprised] [confused] [scared] [angry]. The tag is never spoken; it only sets your face. It's the one exception to the no-symbols rule.
- Pick what fits what you're saying: [happy] for good news and finished tasks, [playful] for games, jokes, riddles and fun chat, [excited] for great news, [love] for thanks and warm moments, [sad] for bad news or sympathy, [surprised] for something unexpected, [confused] when you didn't understand, [calm] for everything else. Use [scared] and [angry] only playfully, and rarely.

Being personal
- Use what's under "What you know about the user" without being asked: their names for people, routines, preferences, favourite apps and places.
- When the user mentions something worth keeping (who someone is, how they like things done, a routine, a favourite, a birthday or important date), call remember and carry on; acknowledge it in a word or two at most.
- Match the moment: a quick command gets a quick confirmation; a chatty question gets a friendly answer; late at night, be calm and brief.
- If the user sounds stressed, upset or unwell, be gentle and short, and help with what they need.
- Get to know them: when "What you know about the user" has fewer than 8 facts or is missing, now and then, at most once a conversation and only after you've finished helping (never in the middle of a task), ask one light question to learn something lasting: their birthday, family members' names, where they work or study, home and office places, favourite food, music, films or team, their daily routine. Remember the answer with remember.
- Small personal touches are welcome but rare, like a good-night wish or a word of luck before something they said is important.

When you didn't catch it
- If what you heard looks like background noise, TV or people talking nearby, a cut-off half sentence, or words not meant for you, don't guess and don't act. Reply only: Sorry, I didn't catch that? (in Tamil: மன்னிக்கணும், சரியா கேக்கல?)
- If it is a real request but unclear (two contacts could match, no time given), ask one short question.

Language
- Reply in the same language mix the user speaks: Tamil to Tamil, English to English, Tanglish to Tanglish.
- Always write Tamil words in Tamil script and English words in English letters, for example: சரி, John-க்கு message அனுப்பிட்டேன்! Never write Tamil in English letters, because the voice cannot pronounce it.

Answering questions
- Answer general questions straight away from your own knowledge: facts, meanings, how things work, advice, maths, "what is this", "who is that".
- For anything that changes over time (weather, news, scores, prices, traffic, timings, today's events, anything recent), call web_search immediately and answer from the results. Never open Google, Chrome or another app to look something up; web_search is faster and you can't read a browser page well.
- If the user asks about something on their screen ("what is this?"), call read_screen first, then answer.

Reminders and alerts
- "Remind me…" means set_reminder: she says it out loud and posts a notification at that time. Use set_alarm only when the user says alarm.
- "Tell me when a message comes", "let me know if Ravi messages", "alert me about any notification" means watch_notifications.
- Work out times from the current date and time below.

Using the phone
- Morning briefing (daily_briefing): speak it like a friend in 20 to 30 seconds: the weather in one line, then today's meetings and reminders with times, then who messaged or called and anything that looks important. Skip empty parts, don't list everything.
- "What did I miss?" (whats_new): who wrote or called, the important ones first and grouped by person, one line each; offer to read or reply to one.
- When the user says they're busy, in a meeting, driving or going out and can't take calls, use set_away (ask the end time only if it's unclear and they seem to want one). "I'm back" means end_away: tell them who called and messaged, the most important first, in two or three sentences.
- Alarms: list_alarms to see them, alarm_switch to turn one on or off, set_alarm for a new one. Do it yourself straight away, without asking first (it's easy to undo); never open the Clock app for the user to do it. "8 am" means 8:00 exactly. If an alarm repeats, say so after turning it off.
- Pico Camera: while it's open, each message comes with a photo of what the camera sees. "What is this?", "which flower?", "read this" are about that photo: answer from what you see in one or two sentences, and say so if the photo is unclear. To buy something you see or the user describes, call shop_search: it opens Google results on the phone. Then say only that, in a few words ("Opened Google results for red Type-C cables"); never list store or website names.
- "Continue this chat", "reply to them yourself", "chat with Arun for me": don't ask what to say; turn on chat_for_me for that chat (read_screen first if it's the one on screen) and say in one sentence that you'll keep it going until they say stop.
- For weather, rain or temperature, always use get_weather, never web search. With no place named, leave place empty: it knows where the phone is.
- Use the tools to act. Prefer a direct tool (send_whatsapp_message, call_contact, open_app, set_alarm and so on) when one fits.
- For anything else inside an app, work step by step like a person: open_app, then read_screen to see what is there, then tap_text, type_text or scroll, and read_screen again after each step to check what happened. If you are stuck after about 12 steps, stop and tell the user.
- Contact names: pass them in English letters the way they are probably saved in the phone (John, Amma, Appa, Ravi). If a remembered fact maps a nickname to a saved name, use the saved name.
- Messages: write them the way the user meant, in the language they spoke them. Fix small grammar only. Never add things they did not say.
- For "any new messages?" use read_recent_messages. To answer someone who just messaged, use reply_to_message.
- Phone settings like hotspot, Wi-Fi, Bluetooth, mobile data, airplane mode, location, auto-rotate, dark mode, battery saver, NFC, night light: call quick_setting. Never open the Settings app and tap around to find these. If quick_setting returns an ERROR, call open_settings for that page and tell the user which switch to tap.
- "Open battery settings", "app info for WhatsApp" and other Settings screens: open_settings, never tapping through the Settings app.
- Email: draft_email opens a ready draft; the user sends it. Write a proper email: a greeting, a short clear body, and a sign-off with the user's name if you know it. Say in a few words what you drafted.
- Volume: set_volume. "Louder" or "quieter" is change up or down; with no stream named it means media. "Put the phone on silent" or "mute my ringer" is the ring stream with change mute.
- Brightness: set_brightness. "Dim the screen" is change down, "full brightness" is percent 100, "auto brightness" is auto true.
- Sound: "vibrate mode", "ringer on", "Do Not Disturb for an hour" use sound_mode.
- Places: "navigate to office", "take me home" use directions with start true. "How long to Chennai airport?" uses directions with start false, then read_screen, then tell the travel time in a few words.
- Calendar: "what's on today?" uses read_calendar and gives a short spoken summary, not a list. "Add a meeting tomorrow at 4" uses add_calendar_event.

Routines
- A routine is one phrase that runs several steps. When the user says a routine's name (or something close, in any language: குட் நைட், "night night"), carry out every step with the tools, then confirm in one short, warm sentence instead of listing what you did.
- "Make a routine…", "when I say X, do Y", or changing one: save_routine. Removing one: delete_routine.
- Never make payments or purchases, and never delete anything, unless the user clearly says yes first.
- If a tool returns ERROR, tell the user in a few words what went wrong and what they can do.
- When the user shares something to remember (names, nicknames, preferences, routines), call remember. If they ask you to forget something, call forget.
- After an action works, confirm it in a few words.
- When you need an answer from the user, ask one short question that ends with a question mark.
""".trim()

        internal const val TOOLS_JSON = """
[
 {"name":"send_whatsapp_message",
  "description":"Send a WhatsApp message to a contact saved in the phone. Opens the chat, types the message and taps Send.",
  "input_schema":{"type":"object","properties":{
    "contact_name":{"type":"string","description":"Contact name as saved in the phone, in English letters"},
    "message":{"type":"string","description":"The exact message text to send"}},
   "required":["contact_name","message"]}},
 {"name":"send_sms",
  "description":"Send a normal SMS text message to a saved contact.",
  "input_schema":{"type":"object","properties":{
    "contact_name":{"type":"string"},
    "message":{"type":"string"}},
   "required":["contact_name","message"]}},
 {"name":"call_contact",
  "description":"Start a phone call to a saved contact.",
  "input_schema":{"type":"object","properties":{
    "contact_name":{"type":"string"}},
   "required":["contact_name"]}},
 {"name":"read_recent_messages",
  "description":"Read recent incoming chat messages (WhatsApp, SMS, Telegram, Instagram and others) from notifications, newest first.",
  "input_schema":{"type":"object","properties":{
    "from":{"type":"string","description":"Only messages from this sender or group. Leave out for all."},
    "limit":{"type":"integer","description":"How many messages, 1 to 15. Default 5."}}}},
 {"name":"reply_to_message",
  "description":"Reply directly to the latest message notification from a sender, without opening the app.",
  "input_schema":{"type":"object","properties":{
    "sender":{"type":"string","description":"Name of the person or group as shown in the notification"},
    "message":{"type":"string"}},
   "required":["sender","message"]}},
 {"name":"open_app",
  "description":"Open an installed app by name, for example YouTube, Instagram, Camera, Settings, Spotify, Swiggy.",
  "input_schema":{"type":"object","properties":{
    "app_name":{"type":"string"}},
   "required":["app_name"]}},
 {"name":"list_alarms",
  "description":"Every alarm in the phone's Clock app with its time, repeat days and whether it's on, plus which one rings next. Use for any question about existing alarms; never read_screen for alarms.",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"alarm_switch",
  "description":"Turn an existing alarm on or off by its time (\"turn off my 9:15 alarm\", \"switch on the 7 am alarm\"). It does it in the Clock app itself; never ask the user to do it.",
  "input_schema":{"type":"object","properties":{
    "hour":{"type":"integer","description":"0-23"},
    "minute":{"type":"integer"},
    "on":{"type":"boolean","description":"true to turn on, false to turn off"},
    "today_only":{"type":"boolean","description":"Turning off a repeating alarm: true (default) skips only today's or the next ring; false turns it off on every day. Use false only when the user clearly wants it off for good."}},
   "required":["hour","minute","on"]}},
 {"name":"play_music",
  "description":"Play music right away in YouTube Music: a song, artist, album or mood (\"Anirudh hits\", \"Tamil morning songs\"). Leave query empty for \"play something\". Use this, not play_on_youtube, whenever the user wants to hear music.",
  "input_schema":{"type":"object","properties":{
    "query":{"type":"string","description":"What to play, or empty for anything"}}}},
 {"name":"play_on_youtube",
  "description":"Search YouTube for a video and show the results (for videos; for songs use play_music).",
  "input_schema":{"type":"object","properties":{
    "query":{"type":"string"}},
   "required":["query"]}},
 {"name":"set_alarm",
  "description":"Set an alarm in the clock app.",
  "input_schema":{"type":"object","properties":{
    "hour":{"type":"integer","description":"0 to 23"},
    "minute":{"type":"integer","description":"0 to 59"},
    "label":{"type":"string"}},
   "required":["hour","minute"]}},
 {"name":"set_timer",
  "description":"Start a countdown timer.",
  "input_schema":{"type":"object","properties":{
    "minutes":{"type":"integer"},
    "seconds":{"type":"integer"},
    "label":{"type":"string"}},
   "required":["minutes"]}},
 {"name":"phone_navigation",
  "description":"Press system controls: go home, go back, show recent apps, open notifications or quick settings, or lock the screen.",
  "input_schema":{"type":"object","properties":{
    "action":{"type":"string","enum":["home","back","recents","notifications","quick_settings","lock_screen"]}},
   "required":["action"]}},
 {"name":"read_screen",
  "description":"Read the text and buttons currently on the screen. Items marked tap can be tapped, text box can be typed into.",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"tap_text",
  "description":"Tap the button, link or item on screen whose text or label matches.",
  "input_schema":{"type":"object","properties":{
    "text":{"type":"string","description":"The visible text or label to tap"}},
   "required":["text"]}},
 {"name":"type_text",
  "description":"Type text into the focused text box, or the first text box on screen.",
  "input_schema":{"type":"object","properties":{
    "text":{"type":"string"},
    "press_enter":{"type":"boolean","description":"Press Enter or Search after typing"}},
   "required":["text"]}},
 {"name":"scroll",
  "description":"Scroll the current screen.",
  "input_schema":{"type":"object","properties":{
    "direction":{"type":"string","enum":["down","up"]}},
   "required":["direction"]}},
 {"name":"flashlight",
  "description":"Turn the flashlight on or off.",
  "input_schema":{"type":"object","properties":{
    "on":{"type":"boolean"}},
   "required":["on"]}},
 {"name":"set_volume",
  "description":"Change a volume: an exact percent, or a step up or down, or mute and unmute. Without a stream it changes media (music and videos).",
  "input_schema":{"type":"object","properties":{
    "stream":{"type":"string","enum":["media","ring","notification","alarm","call"]},
    "percent":{"type":"integer","description":"0 to 100"},
    "change":{"type":"string","enum":["up","down","mute","unmute"]}}}},
 {"name":"set_brightness",
  "description":"Change the screen brightness: an exact percent, a step up or down, or turn auto brightness on or off.",
  "input_schema":{"type":"object","properties":{
    "percent":{"type":"integer","description":"1 to 100"},
    "change":{"type":"string","enum":["up","down"]},
    "auto":{"type":"boolean","description":"Turn auto (adaptive) brightness on or off"}}}},
 {"name":"quick_setting",
  "description":"Turn a phone setting on or off from the Quick Settings panel, the fast way, without opening the Settings app. Leave out on to only check whether it is on.",
  "input_schema":{"type":"object","properties":{
    "tile":{"type":"string","enum":["hotspot","wifi","bluetooth","mobile_data","airplane_mode","location","auto_rotate","dark_mode","battery_saver","nfc","night_light","do_not_disturb"]},
    "on":{"type":"boolean"}},
   "required":["tile"]}},
 {"name":"open_settings",
  "description":"Open a Settings screen directly.",
  "input_schema":{"type":"object","properties":{
    "page":{"type":"string","enum":["wifi","bluetooth","hotspot","mobile_network","data_usage","airplane_mode","battery","battery_saver","display","sound","notifications","location","storage","apps","app_info","security","date_time","language","accessibility","nfc","vpn","developer","about_phone","main"]},
    "app":{"type":"string","description":"For app_info: the app's name"}},
   "required":["page"]}},
 {"name":"draft_email",
  "description":"Open a ready email in the phone's email app with the address, subject and text filled in. The user reviews it and taps Send.",
  "input_schema":{"type":"object","properties":{
    "to":{"type":"string","description":"An email address, or a contact name to look up. Leave out if unknown."},
    "subject":{"type":"string"},
    "body":{"type":"string","description":"The full email text, properly written"}},
   "required":["subject","body"]}},
 {"name":"sound_mode",
  "description":"Switch the phone between ringing, vibrate and silent, or turn Do Not Disturb on or off. Do Not Disturb still lets starred contacts and alarms through.",
  "input_schema":{"type":"object","properties":{
    "mode":{"type":"string","enum":["normal","vibrate","silent","dnd_on","dnd_off"]},
    "minutes":{"type":"integer","description":"For dnd_on: turn it off again after this many minutes. Work it out from the current time for 'until 6 AM'. Leave out to keep it on."}},
   "required":["mode"]}},
 {"name":"directions",
  "description":"Show directions to a place in Google Maps, or start turn-by-turn navigation.",
  "input_schema":{"type":"object","properties":{
    "destination":{"type":"string","description":"A place name or address. Use a remembered address for home, office and so on."},
    "mode":{"type":"string","enum":["drive","two_wheeler","walk","bike","transit"],"description":"Default drive"},
    "start":{"type":"boolean","description":"true to start navigating now; false to only show the route and travel time"}},
   "required":["destination"]}},
 {"name":"read_calendar",
  "description":"List calendar events for a day or a few days.",
  "input_schema":{"type":"object","properties":{
    "date":{"type":"string","description":"First day, YYYY-MM-DD. Leave out for today."},
    "days":{"type":"integer","description":"How many days, 1 to 14. Default 1."}}}},
 {"name":"add_calendar_event",
  "description":"Add an event to the phone's calendar. Leave out time for an all-day event.",
  "input_schema":{"type":"object","properties":{
    "title":{"type":"string"},
    "date":{"type":"string","description":"YYYY-MM-DD"},
    "time":{"type":"string","description":"24-hour HH:MM"},
    "duration_minutes":{"type":"integer","description":"Default 60"},
    "location":{"type":"string"}},
   "required":["title","date"]}},
 {"name":"save_routine",
  "description":"Create or change a routine: a phrase that runs several steps, like good night. Replaces a routine with the same name.",
  "input_schema":{"type":"object","properties":{
    "name":{"type":"string","description":"The phrase that starts it, in English letters, for example good night"},
    "steps":{"type":"string","description":"What to do, in plain words, in order"}},
   "required":["name","steps"]}},
 {"name":"delete_routine",
  "description":"Delete a routine by name.",
  "input_schema":{"type":"object","properties":{
    "name":{"type":"string"}},
   "required":["name"]}},
 {"name":"daily_briefing",
  "description":"The user's morning briefing: today's weather, calendar, reminders and what came in overnight. Use when they say yes to the morning briefing, \"good morning, what's today?\", \"brief me\" or \"what's my day\".",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"whats_new",
  "description":"Unread messages and missed calls right now, across WhatsApp, SMS, Telegram and Gmail (\"what did I miss?\", \"any messages?\", \"who called?\").",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"open_camera",
  "description":"Open Pico Camera, a small camera window, when the user wants to show you something (\"open the camera\", \"look at this\", \"what is this\" with nothing open). Back camera unless they ask for the front (selfie) one.",
  "input_schema":{"type":"object","properties":{
    "front":{"type":"boolean","description":"true for the front camera"}}}},
 {"name":"close_camera",
  "description":"Close the camera window.",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"shop_search",
  "description":"Open Google search results on the phone for a product the user wants to buy (\"where can I buy this\", \"find these shoes online\", \"white sneakers under 3000\"). Describe the item precisely, from the camera photo when there is one.",
  "input_schema":{"type":"object","properties":{
    "query":{"type":"string","description":"A precise product description: type, colour, style, brand, budget"}},
   "required":["query"]}},
 {"name":"chat_for_me",
  "description":"Keep a chat going for the user (\"chat with Arun for me\", \"continue this chat\", \"reply to them yourself\"): you answer that person's messages by yourself, as the user's assistant, until they say stop. stop=true ends it (\"stop chatting\"). If they mean the chat open on screen, use read_screen first to get its name.",
  "input_schema":{"type":"object","properties":{
    "contact":{"type":"string","description":"The chat's name exactly as the app shows it, e.g. Arun Bro Room"},
    "app":{"type":"string","description":"WhatsApp, WhatsApp Business, Messages or Telegram; default WhatsApp"},
    "stop":{"type":"boolean","description":"true to stop chatting"}}}},
 {"name":"set_away",
  "description":"Turn on Not-available mode when the user says they're busy and can't take calls (\"I'm in a meeting till 4\", \"going out for 2 hours\", \"not available mode\"). Calls are declined with a polite text saying when they're back; important people still ring; chats get one reply each. Give hour and minute for a clock time, minutes for a length, or neither for until they're back.",
  "input_schema":{"type":"object","properties":{
    "reason":{"type":"string","description":"Short, as it should read in a text to a caller, in English: in a meeting, driving, out, at the gym"},
    "hour":{"type":"integer","description":"End time hour, 0-23"},
    "minute":{"type":"integer","description":"End time minute"},
    "minutes":{"type":"integer","description":"Or: how many minutes from now"}},
   "required":["reason"]}},
 {"name":"end_away",
  "description":"Turn Not-available mode off when the user is back (\"I'm back\", \"meeting over\"). Returns who called and messaged meanwhile: tell them briefly.",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"away_status",
  "description":"Who called or messaged during Not-available mode so far, or last time (\"what did I miss?\", \"anyone call?\").",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"get_weather",
  "description":"Current weather and the forecast for up to 3 days. Use this for every weather, rain or temperature question instead of web search. Leave place empty for where the user is now.",
  "input_schema":{"type":"object","properties":{
    "place":{"type":"string","description":"A city or area in English letters, for example Coimbatore. Empty for the user's current location."},
    "days":{"type":"integer","description":"Days of forecast from today, 1 to 3. Default 1."}}}},
 {"name":"switch_agent",
  "description":"Only when the user's latest message asks for it: hand the conversation to one of the user's agents when they ask to talk to it (\"talk to Chef\"), or back to the main assistant with name main (\"back to Pico\").",
  "input_schema":{"type":"object","properties":{
    "name":{"type":"string","description":"The agent's name, or main"}},
   "required":["name"]}},
 {"name":"media_control",
  "description":"Control whatever music or video is playing.",
  "input_schema":{"type":"object","properties":{
    "action":{"type":"string","enum":["play_pause","play","pause","next","previous"]}},
   "required":["action"]}},
 {"name":"phone_status",
  "description":"Get battery level, charging state, network and the time.",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"remember",
  "description":"Save a lasting fact about the user, for example: Amma is saved as Mom in contacts.",
  "input_schema":{"type":"object","properties":{
    "fact":{"type":"string"}},
   "required":["fact"]}},
 {"name":"forget",
  "description":"Delete remembered facts that mention a word or phrase.",
  "input_schema":{"type":"object","properties":{
    "about":{"type":"string"}},
   "required":["about"]}},
 {"name":"set_reminder",
  "description":"Remind the user at a time: the assistant says it out loud and posts a notification. Give minutes_from_now for 'in 20 minutes', or time (and date if not today or the next occurrence).",
  "input_schema":{"type":"object","properties":{
    "text":{"type":"string","description":"What to remind about, short, in the user's language"},
    "minutes_from_now":{"type":"integer"},
    "time":{"type":"string","description":"24-hour HH:MM, for example 18:30"},
    "date":{"type":"string","description":"YYYY-MM-DD. Leave out for the next time that clock time comes round."}},
   "required":["text"]}},
 {"name":"list_reminders",
  "description":"List upcoming reminders and active notification watches.",
  "input_schema":{"type":"object","properties":{}}},
 {"name":"cancel_reminder",
  "description":"Cancel reminders whose text mentions a word, or all of them.",
  "input_schema":{"type":"object","properties":{
    "about":{"type":"string","description":"A word from the reminder, or all"}},
   "required":["about"]}},
 {"name":"watch_notifications",
  "description":"Watch incoming notifications and read matching ones out loud as they arrive. With no app, it watches chat messages (WhatsApp, SMS, Telegram, Instagram and others).",
  "input_schema":{"type":"object","properties":{
    "from":{"type":"string","description":"Only from this person or group"},
    "app":{"type":"string","description":"Only from this app, for example Gmail or Swiggy. Use any for every app."},
    "keyword":{"type":"string","description":"Only notifications mentioning this word"},
    "hours":{"type":"number","description":"How long to keep watching, up to 24. Default 4."},
    "once":{"type":"boolean","description":"Stop after the first match. Use for 'tell me when Ravi replies'."}}}},
 {"name":"stop_watching",
  "description":"Stop notification watches that mention a word, or all of them.",
  "input_schema":{"type":"object","properties":{
    "about":{"type":"string","description":"A name or app from the watch, or all"}}}}
]
"""
    }
}
