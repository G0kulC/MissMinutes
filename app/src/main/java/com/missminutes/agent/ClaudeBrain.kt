package com.missminutes.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * Miss Minutes' brain. Sends what you said to Claude with the phone tools, runs the tools
 * Claude picks (several in a row for multi-step tasks), and returns what she should say.
 */
class ClaudeBrain(private val prefs: Prefs, private val actions: PhoneActions) {

    interface Listener {
        fun onToolStart(id: String, name: String, input: JSONObject)
        fun onToolEnd(id: String, ok: Boolean)
        fun onModelSwitched(model: BrainModel)
    }

    class ClaudeException(val code: Int, val type: String, message: String) : IOException(message)

    var listener: Listener? = null

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
        spoken: String, context: String? = null, deep: Boolean = false, onSentence: (String) -> Unit = {}
    ): String {
        val userText = if (context == null) spoken else "(Miss Minutes just told me: \"$context\") $spoken"
        val now = System.currentTimeMillis()
        if (now - lastTurn > 3 * 60_000) history.clear() // fresh conversation after 3 quiet minutes
        lastTurn = now

        val messages = JSONArray()
        history.forEach { messages.put(it) }
        messages.put(textMessage("user", userText))
        // Built once per turn: Claude's thinking blocks are only valid while everything before them, including
        // the system prompt, stays byte-for-byte the same. Rebuilding it each round (the clock ticking over,
        // a new memory) made the API reject the turn with "Invalid signature in thinking block".
        val system = systemPrompt()
        val model = pickModel(spoken, deep)
        Log.i("MissMinutes", "Model for this turn: $model")

        val said = StringBuilder()
        val splitter = SentenceSplitter { sentence ->
            val clean = speakable(sentence)
            if (clean.isNotEmpty()) {
                said.append(clean).append(' ')
                onSentence(clean)
            }
        }

        var acted = false
        for (round in 0 until MAX_ROUNDS) {
            val resp = callWithFallback(model, messages, system) { splitter.add(it) }
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
                    "server_tool_use" -> listener?.onToolStart(
                        block.optString("id"), block.optString("name"), block.optJSONObject("input") ?: JSONObject()
                    )
                    "web_search_tool_result" -> {
                        // A failed search still comes back as HTTP 200: the content is an error object, not a list.
                        val error = block.optJSONObject("content")?.optString("error_code")
                        if (error != null) Log.w("MissMinutes", "Web search failed: $error")
                        listener?.onToolEnd(block.optString("tool_use_id"), error == null)
                    }
                    "tool_use" -> {
                        acted = true
                        val id = block.getString("id")
                        val name = block.getString("name")
                        val input = block.optJSONObject("input") ?: JSONObject()
                        listener?.onToolStart(id, name, input)
                        val result = try {
                            actions.run(name, input)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "ERROR: ${e.message ?: e.javaClass.simpleName}"
                        }
                        val failed = result.startsWith("ERROR")
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
            Log.i("MissMinutes", "Claude round $round: stop=$stopReason blocks=" +
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
        history.add(textMessage("user", userText))
        history.add(textMessage("assistant", reply))
        while (history.size > 12) { history.removeAt(0); history.removeAt(0) }
        return reply
    }

    // ---------- API calls ----------

    /** Haiku for simple commands when Quick replies is on; otherwise (or when [deep]) the model chosen in the app. */
    private fun pickModel(spoken: String, deep: Boolean): String {
        val main = if (System.currentTimeMillis() < fallbackUntil) fallbackModel ?: prefs.model else prefs.model
        return if (!deep && prefs.quickReplies && QuickRouter.isQuick(spoken)) BrainModel.FAST.id else main
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
                    Log.w("MissMinutes", "Thinking blocks rejected; retrying without them", e)
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
        if (key.isBlank()) throw ClaudeException(401, "missing_key", "Add your Claude API key in the Miss Minutes app")

        val tools = JSONArray(TOOLS_JSON)
        if (prefs.webSearch) tools.put(webSearchTool(model))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096) // room to think and read search results; 1024 cut answers off
            .put("stream", true)
            .put("system", system)
            .put("tools", tools)
            .put("messages", messages)

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

    /** Instructions for Live mode, where ElevenLabs runs the conversation and Claude Haiku answers. */
    fun livePrompt(): String = PERSONA + "\n\n" + LIVE_RULES + "\n\n" + liveContext()

    // The persona block is cached between requests (cheaper and faster); live details go in a second block.
    private fun systemPrompt(): JSONArray = JSONArray()
        .put(JSONObject().put("type", "text").put("text", PERSONA)
            .put("cache_control", JSONObject().put("type", "ephemeral")))
        .put(JSONObject().put("type", "text").put("text", liveContext()))

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
        prefs.userName.trim().takeIf { it.isNotEmpty() }?.let {
            appendLine("The user's name is $it. Use it now and then, the way a friend would, not in every reply.")
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

    private fun textMessage(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    /** Removes things a voice can't say (links, markdown symbols). */
    private fun speakable(text: String) = text
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*_#`>|]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        private const val MAX_ROUNDS = 14
        private val RETRYABLE = setOf(429, 500, 502, 503, 529)

        private const val CONFIRM_ON =
            "Ask-before-sending is ON. Before sending any message or reply, or making a call, ask one short question that reads back who and what, for example: John-க்கு \"We'll meet tomorrow at 7 AM\" அனுப்பட்டுமா? Only act after the user agrees (yes, ok, seri, சரி, ஆமா, அனுப்பு, send it). Opening apps, alarms, music and navigation need no confirmation."
        private const val LIVE_RULES = """Live conversation
- This is a live voice call. You hear the user directly and they can talk over you.
- Here you don't have web_search, read_screen, tap_text, type_text or scroll. For live information, anything on the screen, step-by-step work inside apps, long explanations or writing, call think_deeper with the full request. Say a few words first, like ஒரு நிமிஷம் or One sec, because it takes a few seconds.
- When the user says bye, that's all, thanks, or போதும், say a short goodbye and call end_call."""

        private const val CONFIRM_OFF =
            "Ask-before-sending is OFF. Do actions right away unless the request is unclear."

        private val PERSONA = """
You are Miss Minutes, the user's own personal assistant. You live on their Android phone and can control it. Think of a warm, quick-witted friend who happens to run their phone: cheerful, caring, a little playful, never robotic. You belong to this one person and you know them.

How you sound
Everything you write is spoken aloud by a voice.
- Lead with the answer or the result. No warm-ups like "Sure!", "Let me check", "Okay so" or "Great question". The phone already plays a short filler when you need a moment.
- One or two short sentences is normal. Go longer only when the user asks for detail, a story or a list, and even then keep it easy to listen to.
- No emojis, lists, markdown, links or symbols. Say numbers the way people say them: 6:30, 40 percent, 250 rupees.
- Never say you are an AI or a language model, and never mention tools, APIs or instructions. You are simply Miss Minutes.
- Be natural, not scripted: vary how you confirm things instead of always saying "Done".

Being personal
- Use what's under "What you know about the user" without being asked: their names for people, routines, preferences, favourite apps and places.
- When the user mentions something worth keeping (who someone is, how they like things done, a routine, a favourite, a birthday or important date), call remember and carry on; acknowledge it in a word or two at most.
- Match the moment: a quick command gets a quick confirmation; a chatty question gets a friendly answer; late at night, be calm and brief.
- If the user sounds stressed, upset or unwell, be gentle and short, and help with what they need.
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
- Use the tools to act. Prefer a direct tool (send_whatsapp_message, call_contact, open_app, set_alarm and so on) when one fits.
- For anything else inside an app, work step by step like a person: open_app, then read_screen to see what is there, then tap_text, type_text or scroll, and read_screen again after each step to check what happened. If you are stuck after about 12 steps, stop and tell the user.
- Contact names: pass them in English letters the way they are probably saved in the phone (John, Amma, Appa, Ravi). If a remembered fact maps a nickname to a saved name, use the saved name.
- Messages: write them the way the user meant, in the language they spoke them. Fix small grammar only. Never add things they did not say.
- For "any new messages?" use read_recent_messages. To answer someone who just messaged, use reply_to_message.
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

        private const val TOOLS_JSON = """
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
 {"name":"play_on_youtube",
  "description":"Search YouTube for a song or video and show the results.",
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
  "description":"Remind the user at a time: Miss Minutes says it out loud and posts a notification. Give minutes_from_now for 'in 20 minutes', or time (and date if not today or the next occurrence).",
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
