package com.missminutes.agent

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentsTest {

    private val chef = Agent(name = "Chef", purpose = "Dinner recipes", style = Style.SHORT,
        instructions = "I'm vegetarian", abilities = setOf(Ability.WEB, Ability.TIME), voice = "vijay", colour = "ROYAL")

    @Test fun agentSurvivesJsonRoundTrip() {
        assertEquals(chef, Agent.fromJson(chef.toJson()))
    }

    @Test fun agentListParsesAndSkipsBrokenEntries() {
        val json = JSONArray().put(chef.toJson()).put(Agent(name = "", purpose = "x").toJson()).toString()
        assertEquals(listOf(chef), Agents.parse(json))
        assertEquals(emptyList<Agent>(), Agents.parse("not json"))
    }

    @Test fun unknownVoiceOrColourFallsBackToTheMainAssistants() {
        val o = chef.toJson().put("voice", "no_such_voice").put("colour", "PLAID")
        assertNull(Agent.fromJson(o).voice)
        assertNull(Agent.fromJson(o).colour)
    }

    @Test fun anAgentGetsOnlyTheToolsItWasGiven() {
        assertEquals(setOf("web_search", "get_weather", "shop_search", "set_alarm", "list_alarms", "alarm_switch", "set_timer", "set_reminder", "list_reminders", "cancel_reminder",
            "read_calendar", "add_calendar_event", "watch_notifications", "stop_watching"), chef.tools)
        assertEquals(emptySet<String>(), Agent(name = "Buddy", purpose = "chat").tools)
    }

    @Test fun everyAbilityNamesARealTool() {
        val all = JSONArray(ClaudeBrain.TOOLS_JSON)
        val names = List(all.length()) { all.getJSONObject(it).getString("name") }.toSet() + "web_search"
        Ability.entries.flatMap { it.tools }.forEach { assertTrue("$it isn't a tool", it in names) }
    }

    @Test fun voicesFollowPresentation() {
        assertTrue(Voices.forPresentation(Presentation.MASCULINE).all { it.presentation == Presentation.MASCULINE })
        assertTrue(Voices.forPresentation(Presentation.FEMININE).all { it.presentation == Presentation.FEMININE })
        assertEquals(Voices.options, Voices.forPresentation(Presentation.NEUTRAL))
    }

    @Test fun misheardNamesStillCount() {
        assertTrue(ClaudeBrain.soundsLike("switch to pickup", "Pico"))
        assertTrue(ClaudeBrain.soundsLike("back to piko", "Pico"))
        assertTrue(ClaudeBrain.soundsLike("Pico", "Pico"))
        assertFalse(ClaudeBrain.soundsLike("give me a recipe", "Pico"))
        assertFalse(ClaudeBrain.soundsLike("pizza please", "Pico"))
    }
}
