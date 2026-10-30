package com.missminutes.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    @Test fun describesNowAndTheNextDays() {
        val o = JSONObject("""{"current":{"temperature_2m":27.4,"apparent_temperature":30.1,"relative_humidity_2m":70,
            "precipitation":0,"weather_code":2,"wind_speed_10m":11.6},
            "daily":{"time":["d1","d2"],"weather_code":[80,0],"temperature_2m_max":[31.2,32],"temperature_2m_min":[22.5,23],
            "precipitation_probability_max":[60,5]}}""")
        val text = Weather.describe("Coimbatore, Tamil Nadu", o)
        assertTrue(text, text.contains("Now: 27°C, feels like 30°C, partly cloudy, humidity 70%, wind 12 km/h."))
        assertTrue(text, text.contains("Today: light rain, 23 to 31°C, 60% chance of rain."))
        assertTrue(text, text.contains("Tomorrow: clear sky"))
    }

    @Test fun everyCodeHasWords() {
        assertEquals("thunderstorms", Weather.sky(95))
        assertEquals("mixed weather", Weather.sky(1234))
    }
}
