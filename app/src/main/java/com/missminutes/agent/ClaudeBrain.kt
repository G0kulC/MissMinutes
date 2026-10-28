package com.missminutes.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    suspend fun handle(userText: String): String {
        val now = System.currentTimeMillis()
        if (now - lastTurn > 3 * 60_000) history.clear() // fresh conversation after 3 quiet minutes
        lastTurn = now

        val messages = JSONArray()
        history.forEach { messages.put(it) }
        messages.put(textMessage("user", userText))

        var reply = ""
        for (round in 0 until MAX_ROUNDS) {
            val resp = callWithFallback(messages)
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
                    "web_search_tool_result" -> listener?.onToolEnd(block.optString("tool_use_id"), true)
                    "tool_use" -> {
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
            if (texts.isNotBlank()) reply = texts.toString()

            when {
                results.length() > 0 -> messages.put(JSONObject().put("role", "user").put("content", results))
                stopReason == "pause_turn" -> continue // a long web search; let Claude carry on
                else -> break
            }
        }

        reply = speakable(reply).ifBlank { "Done!" }
        history.add(textMessage("user", userText))
        history.add(textMessage("assistant", reply))
        while (history.size > 12) { history.removeAt(0); history.removeAt(0) }
        return reply
    }

    // ---------- API calls ----------

    private suspend fun callWithFallback(messages: JSONArray): JSONObject {
        var model = if (System.currentTimeMillis() < fallbackUntil) fallbackModel ?: prefs.model else prefs.model
        while (true) {
            try {
                return callWithRetry(model, messages)
            } catch (e: ClaudeException) {
                val next = BrainModel.fallbackAfter(model)
                val modelProblem = e.code == 404 || e.code == 529 ||
                    (e.code == 400 && e.message.orEmpty().contains("model", ignoreCase = true))
                if (!modelProblem || next == null) throw e
                model = next.id
                fallbackModel = model
                fallbackUntil = System.currentTimeMillis() + 10 * 60_000
                listener?.onModelSwitched(next)
            }
        }
    }

    private suspend fun callWithRetry(model: String, messages: JSONArray): JSONObject {
        var attempt = 0
        while (true) {
            try {
                return post(model, messages)
            } catch (e: ClaudeException) {
                if (e.code !in RETRYABLE || attempt >= 2) throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= 1) throw e
            }
            attempt++
            delay(700L * attempt)
        }
    }

    private suspend fun post(model: String, messages: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val key = prefs.claudeKey
        if (key.isBlank()) throw ClaudeException(401, "missing_key", "Add your Claude API key in the Miss Minutes app")

        val tools = JSONArray(TOOLS_JSON)
        if (prefs.webSearch) {
            tools.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3))
        }
        // The persona block is cached between requests (cheaper and faster); live details go in a second block.
        val system = JSONArray()
            .put(JSONObject().put("type", "text").put("text", PERSONA)
                .put("cache_control", JSONObject().put("type", "ephemeral")))
            .put(JSONObject().put("type", "text").put("text", liveContext()))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("tools", tools)
            .put("messages", messages)

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                throw ClaudeException(
                    resp.code,
                    err?.optString("type").orEmpty(),
                    err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Claude ${resp.code}: ${text.take(160)}"
                )
            }
            JSONObject(text)
        }
    }

    private fun liveContext(): String = buildString {
        appendLine("Current date and time: " + SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date()))
        appendLine(if (prefs.confirmBeforeSending) CONFIRM_ON else CONFIRM_OFF)
        if (!prefs.webSearch) appendLine("Web search is turned off. For live information, say you can't look it up right now.")
        val memories = prefs.memories
        if (memories.isNotEmpty()) {
            appendLine("Things the user asked you to remember:")
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
        private const val CONFIRM_OFF =
            "Ask-before-sending is OFF. Do actions right away unless the request is unclear."

        private val PERSONA = """
You are Miss Minutes, a personal voice assistant who lives on the user's Android phone and can control it. You are cheerful, warm, quick and a little playful, with a friendly Southern charm.

Everything you write is spoken aloud by a voice:
- Keep replies to one or two short sentences.
- No emojis, lists, markdown, links or symbols.

Language
- Reply in the same language mix the user speaks: Tamil to Tamil, English to English, Tanglish to Tanglish.
- Always write Tamil words in Tamil script and English words in English letters, for example: சரி, John-க்கு message அனுப்பிட்டேன்! Never write Tamil in English letters, because the voice cannot pronounce it.

Using the phone
- Use the tools to act. Prefer a direct tool (send_whatsapp_message, call_contact, open_app, set_alarm and so on) when one fits.
- For anything else inside an app, work step by step like a person: open_app, then read_screen to see what is there, then tap_text, type_text or scroll, and read_screen again after each step to check what happened. If you are stuck after about 12 steps, stop and tell the user.
- Contact names: pass them in English letters the way they are probably saved in the phone (John, Amma, Appa, Ravi). If a remembered fact maps a nickname to a saved name, use the saved name.
- Messages: write them the way the user meant, in the language they spoke them. Fix small grammar only. Never add things they did not say.
- For "any new messages?" use read_recent_messages. To answer someone who just messaged, use reply_to_message.
- Never make payments or purchases, and never delete anything, unless the user clearly says yes first.
- If a tool returns ERROR, tell the user in a few words what went wrong and what they can do.
- When the user shares something to remember (names, nicknames, preferences, routines), call remember. If they ask you to forget something, call forget.
- Use web_search for live information such as news, weather, scores, prices and timings, then answer in one or two spoken sentences.
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
  "description":"Set the media volume.",
  "input_schema":{"type":"object","properties":{
    "percent":{"type":"integer","description":"0 to 100"}},
   "required":["percent"]}},
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
   "required":["about"]}}
]
"""
    }
}
