package com.missminutes.agent

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast

/**
 * The first-time setup: who you are and who your assistant is, one question per screen. Every answer is
 * already filled in from what's saved, so running it again (Settings → Run setup again) loses nothing.
 */
object Onboarding {

    fun steps(a: MainActivity, prefs: Prefs): List<StepScreen.Step> {
        var userName = prefs.userName
        var city = prefs.city
        var assistantName = prefs.assistantName
        val name = { Assistant(prefs).name }

        return listOf(
            StepScreen.Step(
                title = { "Let's set up your assistant" },
                detail = { "A few quick questions so it feels like yours. You can change every answer later." },
                build = { body ->
                    val ghost = GhostView(a).apply { theme = GhostTheme.of(prefs.ghostTheme); state = OrbState.IDLE }
                    body.addView(ghost, LinearLayout.LayoutParams(a.dpi(180), a.dpi(180)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    ghost.start(withIntro = true)
                    listOf(
                        "Speaks Tamil, English or a mix, the way you talk.",
                        "Messages, calls, reminders, music and settings, by voice.",
                        "Your details stay on this phone.",
                    ).forEach { body.addView(a.bullet(it), a.full(top = 10)) }
                    // Moving from another phone: restore now instead of answering everything again.
                    body.addView(a.text("Moving from another phone?", 15f, Fonts.MEDIUM, Palette.CREAM), a.full(top = 26))
                    body.addView(a.button("Restore from a backup", MainActivity.ButtonStyle.OUTLINE) { a.startRestore() },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dpi(52)).apply { topMargin = a.dpi(8) })
                },
            ),
            StepScreen.Step(
                title = { "What should I call you?" },
                detail = { "Your assistant uses your name to greet you now and then." },
                optional = true,
                build = { body ->
                    body.addView(a.input(userName, "Your name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
                        addTextChangedListener(watcher { userName = it })
                    }, a.full())
                },
                save = { prefs.userName = userName.trim().replace(Regex("\\s+"), " ").take(30); null },
            ),
            StepScreen.Step(
                title = { "Which city are you in?" },
                detail = { "Optional. It helps with the weather, timings and places near you." },
                optional = true,
                build = { body ->
                    body.addView(a.input(city, "For example, Chennai", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
                        addTextChangedListener(watcher { city = it })
                    }, a.full())
                },
                save = { prefs.city = city.trim().take(40); null },
            ),
            StepScreen.Step(
                title = { "Name your assistant" },
                detail = { "The name it answers to. You can keep ${Assistant.DEFAULT_NAME}, or pick your own." },
                build = { body ->
                    val hint = a.text("", 14f, Fonts.REGULAR, Palette.MUTED)
                    fun showHint() {
                        val n = Assistant.cleanName(assistantName).ifEmpty { "…" }
                        hint.text = "You'll wake it by saying “Hey $n”."
                    }
                    body.addView(a.input(assistantName, Assistant.DEFAULT_NAME, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
                        addTextChangedListener(watcher { assistantName = it; showHint() })
                    }, a.full())
                    body.addView(hint, a.full(top = 10))
                    showHint()
                },
                save = {
                    if (Assistant.cleanName(assistantName).isEmpty()) "Give your assistant a name, or keep ${Assistant.DEFAULT_NAME}."
                    else {
                        Assistant.rename(a, prefs, assistantName)?.let { a.toast(it, Toast.LENGTH_LONG) }
                        null
                    }
                },
            ),
            StepScreen.Step(
                title = { "How should ${name()} present?" },
                detail = { "This only changes the words the app uses and the voices it suggests, never what ${name()} can do." },
                build = { body ->
                    fun render() {
                        body.removeAllViews()
                        Presentation.entries.forEach { p ->
                            body.addView(a.radioRow(p.title, p.detail, prefs.presentation == p.name) {
                                prefs.presentation = p.name
                                render()
                            }, a.full(top = 4))
                        }
                    }
                    render()
                },
            ),
            StepScreen.Step(
                title = { "Choose ${name()}'s voice" },
                detail = {
                    val p = Presentation.of(prefs.presentation)
                    (if (p == Presentation.NEUTRAL) "All voices are open to you." else "${p.title} voices that speak Tamil and English.") +
                        if (prefs.sarvamKey.isBlank()) " You can hear them once your Sarvam key is added in Settings." else " Tap Play to hear one."
                },
                build = { body ->
                    val options = Voices.forPresentation(Presentation.of(prefs.presentation))
                    if (options.none { it.id == prefs.sarvamSpeaker }) prefs.sarvamSpeaker = options.first().id
                    a.voiceList(body, options, prefs.sarvamSpeaker, sameAs = null, speakerName = name()) { prefs.sarvamSpeaker = it ?: prefs.sarvamSpeaker }
                },
            ),
            StepScreen.Step(
                title = { "Pick a colour" },
                detail = { "${name()}'s colour, here and on the little ghost that floats over your apps." },
                build = { body ->
                    val preview = GhostView(a).apply { theme = GhostTheme.of(prefs.ghostTheme); state = OrbState.IDLE }
                    body.addView(preview, LinearLayout.LayoutParams(a.dpi(120), a.dpi(120)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    preview.start(withIntro = false)
                    body.addView(a.swatchGrid(GhostTheme.of(prefs.ghostTheme), sameAs = null) { picked ->
                        val t = picked ?: GhostTheme.MINT
                        prefs.ghostTheme = t.name
                        preview.theme = t
                    }, a.full(top = 8))
                },
            ),
            StepScreen.Step(
                title = { "You're all set" },
                detail = {
                    "Save a backup any time in Settings → Backup: your profile, memories, routines and agents, ready for a new " +
                        "phone. Your API keys are never included."
                },
                build = { },
            ),
        )
    }

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
    }

    /** Creating or editing an agent: name, purpose, how it answers, what it may do, and its voice. */
    /** The agent's name so far, for the last step's "Create Cinema" button. */
    var draftName = ""

    fun agentSteps(a: MainActivity, prefs: Prefs, original: Agent?, onSave: (Agent) -> Unit): List<StepScreen.Step> {
        val isNew = original == null || Agents.byId(prefs, original.id) == null
        // A new agent gets its own colour and voice, so it's easy to tell from the main assistant.
        var draft = (original ?: Agent(name = "", purpose = "")).let { d ->
            if (!isNew) d else d.copy(
                colour = d.colour ?: GhostTheme.entries.first { it.name != prefs.ghostTheme && it != GhostTheme.MINT }.name,
                voice = d.voice ?: Voices.options.firstOrNull { it.id != prefs.sarvamSpeaker && it.presentation != Presentation.of(prefs.presentation) }?.id)
        }
        draftName = draft.name
        val main = prefs.assistantName
        val called = { draft.name.ifBlank { "your agent" } }

        return listOf(
            StepScreen.Step(
                title = { "What should this agent help you with?" },
                detail = { "In a sentence, in your own words. An agent is a helper for one job, like recipes, revision or workouts." },
                build = { body ->
                    val field = a.area(draft.purpose, "For example, quick dinner recipes with what's in my fridge").apply {
                        addTextChangedListener(watcher { draft = draft.copy(purpose = it) })
                    }
                    body.addView(field, a.full())
                    // Ideas to start from: a tap fills the box.
                    body.addView(a.label("Ideas"), a.full(top = 18))
                    body.addView(android.widget.HorizontalScrollView(a).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(LinearLayout(a).apply {
                            orientation = LinearLayout.HORIZONTAL
                            listOf("Recipes" to "Quick dinner recipes with what's at home",
                                "Revision" to "Help me revise and quiz me before exams",
                                "Workouts" to "Simple home workouts and keeping me on track",
                                "Movies" to "Movie and series suggestions for the weekend").forEachIndexed { i, (label, purpose) ->
                                addView(a.button(label, MainActivity.ButtonStyle.OUTLINE) { field.setText(purpose); field.setSelection(purpose.length) }
                                    .apply { textSize = 14f },
                                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, a.dpi(42)).apply { if (i > 0) marginStart = a.dpi(8) })
                            }
                        })
                    }, a.full(top = 8))
                },
                save = {
                    draft = draft.copy(purpose = draft.purpose.trim().take(300))
                    if (draft.purpose.isEmpty()) "Tell us what the agent is for." else null
                },
            ),
            StepScreen.Step(
                title = { "What should it be called?" },
                detail = { "A short name you'll say out loud, like Chef or Coach: “talk to Chef” switches to it." },
                build = { body ->
                    body.addView(a.input(draft.name, "For example, Chef", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
                        addTextChangedListener(watcher { draft = draft.copy(name = it) })
                    }, a.full())
                },
                save = {
                    val n = Assistant.cleanName(draft.name)
                    draft = draft.copy(name = n)
                    draftName = n
                    when {
                        n.isEmpty() -> "Give your agent a name."
                        n.equals(main, ignoreCase = true) || n.equals("main", ignoreCase = true) ->
                            "$main is your main assistant's name. Pick a different one."
                        Agents.all(prefs).any { it.id != draft.id && it.name.equals(n, ignoreCase = true) } ->
                            "You already have an agent called $n."
                        else -> null
                    }
                },
            ),
            StepScreen.Step(
                title = { "How should ${called()} answer?" },
                detail = { "Pick a style, and add anything else it should know." },
                build = { body ->
                    val styles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                    fun render() {
                        styles.removeAllViews()
                        Style.entries.forEach { s ->
                            styles.addView(a.radioRow(s.title, s.instruction, draft.style == s) { draft = draft.copy(style = s); render() }, a.full(top = 2))
                        }
                    }
                    render()
                    body.addView(styles, a.full())
                    body.addView(a.label("Anything else? (optional)"), a.full(top = 16))
                    body.addView(a.area(draft.instructions, "For example, I'm vegetarian. Keep recipes under 30 minutes.").apply {
                        addTextChangedListener(watcher { draft = draft.copy(instructions = it) })
                    }, a.full(top = 6))
                },
                save = { draft = draft.copy(instructions = draft.instructions.trim().take(600)); null },
            ),
            StepScreen.Step(
                title = { "What may ${called()} do?" },
                detail = {
                    "${called()} can only do what you turn on here. With nothing on, it can still talk and answer questions. " +
                        "Turning one on lets ${called()} use your phone for it, the same way $main does."
                },
                build = { body ->
                    Ability.entries.forEach { ability ->
                        val risky = ability == Ability.MESSAGES || ability == Ability.APPS
                        body.addView(a.toggle(if (risky) "⚠ ${ability.title}" else ability.title,
                            ability.detail + if (risky) " Can act for you." else "", ability in draft.abilities) { on ->
                            draft = draft.copy(abilities = if (on) draft.abilities + ability else draft.abilities - ability)
                        })
                    }
                },
            ),
            StepScreen.Step(
                title = { "${called()}'s colour" },
                detail = { "The ghost turns this colour while ${called()} is talking, so you always know who's there." },
                build = { body ->
                    body.addView(a.swatchGrid(draft.colour?.let { GhostTheme.of(it) }, sameAs = "Same as $main") {
                        draft = draft.copy(colour = it?.name)
                    }, a.full())
                },
            ),
            StepScreen.Step(
                title = { "${called()}'s voice" },
                detail = { "A different voice makes it easy to tell who's talking." + if (prefs.sarvamKey.isBlank()) "" else " Tap Play to hear one." },
                build = { body ->
                    a.voiceList(body, Voices.options, draft.voice, sameAs = "Same as $main", speakerName = called()) { draft = draft.copy(voice = it) }
                },
                save = { onSave(draft); null },
            ),
        )
    }
}
