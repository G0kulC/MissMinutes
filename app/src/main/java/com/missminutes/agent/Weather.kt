package com.missminutes.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Where the phone is, roughly (city level). Android only lets the app read it while the app or the assistant is on
 * screen, so it's saved each time it can be read and the last one is used for a while after.
 */
object Place {
    data class Spot(val lat: Double, val lon: Double, val name: String?, val at: Long)

    private const val FRESH_MS = 12 * 60 * 60_000L

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The saved spot if it's from the last 12 hours. */
    fun recent(prefs: Prefs): Spot? = runCatching {
        val o = JSONObject(prefs.lastPlaceJson)
        Spot(o.getDouble("lat"), o.getDouble("lon"), o.optString("name").takeIf { it.isNotBlank() }, o.getLong("at"))
    }.getOrNull()?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }

    /** Reads the phone's last known position and names it ("Coimbatore, Tamil Nadu"). Slow: call off the main thread. */
    fun refresh(context: Context, prefs: Prefs) {
        if (!allowed(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val loc = runCatching {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return
        val old = recent(prefs)
        // Name it again only if it moved more than a few kilometres (the name lookup goes over the network).
        val moved = old == null || old.name == null || Math.abs(old.lat - loc.latitude) + Math.abs(old.lon - loc.longitude) > 0.05
        val name = if (!moved) old?.name else runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.ENGLISH).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea).distinct().joinToString(", ")
            }
        }.getOrNull()
        prefs.lastPlaceJson = JSONObject().put("lat", loc.latitude).put("lon", loc.longitude)
            .put("name", name ?: "").put("at", System.currentTimeMillis()).toString()
    }
}

/**
 * Weather from Open-Meteo (free, no key): now, and today to the next couple of days. Web search kept failing on
 * weather ("couldn't get a reliable reading"), so it has its own tool.
 */
object Weather {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun get(context: Context, prefs: Prefs, place: String, days: Int): String = withContext(Dispatchers.IO) {
        val spot = if (place.isNotBlank()) find(place) ?: return@withContext "ERROR: couldn't find a place called $place. Ask the user to say it another way."
        else Place.recent(prefs)?.let { Triple(it.lat, it.lon, (it.name ?: "your location") + " (the phone's location)") }
            ?: prefs.city.trim().takeIf { it.isNotEmpty() }?.let { find(it) }
            ?: return@withContext "ERROR: I don't know where the user is. Ask which city, and mention they can turn on Location " +
                "for the app or add their city in Settings, My profile."
        val (lat, lon, name) = spot
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "%.4f".format(Locale.US, lat))
            .addQueryParameter("longitude", "%.4f".format(Locale.US, lon))
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", days.coerceIn(1, 3).toString())
            .build()
        val o = runCatching { fetch(url.toString()) }.getOrElse {
            PicoLog.w("Weather failed", it)
            return@withContext "ERROR: the weather service didn't answer. Say you couldn't check the weather right now."
        }
        describe(name, o)
    }

    /** "Coimbatore" → its coordinates and full name. */
    private fun find(place: String): Triple<Double, Double, String>? = runCatching {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", place.trim()).addQueryParameter("count", "1").addQueryParameter("language", "en").build()
        val r = fetch(url.toString()).optJSONArray("results")?.optJSONObject(0) ?: return null
        val name = listOf(r.optString("name"), r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
        Triple(r.getDouble("latitude"), r.getDouble("longitude"), name)
    }.getOrNull()

    private fun fetch(url: String): JSONObject =
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            JSONObject(resp.body!!.string())
        }

    fun describe(name: String, o: JSONObject): String = buildString {
        append("Weather for $name.")
        o.optJSONObject("current")?.let { c ->
            append(" Now: ${c.optDouble("temperature_2m").roundToInt()}°C, feels like ${c.optDouble("apparent_temperature").roundToInt()}°C, ")
            append("${sky(c.optInt("weather_code"))}, humidity ${c.optInt("relative_humidity_2m")}%, wind ${c.optDouble("wind_speed_10m").roundToInt()} km/h")
            if (c.optDouble("precipitation") > 0) append(", ${c.optDouble("precipitation")} mm of rain right now")
            append(".")
        }
        o.optJSONObject("daily")?.let { d ->
            val labels = listOf("Today", "Tomorrow", "The day after")
            val count = d.optJSONArray("time")?.length() ?: 0
            for (i in 0 until count) {
                val rain = d.optJSONArray("precipitation_probability_max")?.optInt(i) ?: 0
                append(" ${labels.getOrElse(i) { "Day ${i + 1}" }}: ${sky(d.getJSONArray("weather_code").optInt(i))}, ")
                append("${d.getJSONArray("temperature_2m_min").optDouble(i).roundToInt()} to ${d.getJSONArray("temperature_2m_max").optDouble(i).roundToInt()}°C, ")
                append("$rain% chance of rain.")
            }
        }
    }

    /** WMO weather codes in plain words. */
    fun sky(code: Int) = when (code) {
        0 -> "clear sky"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "cloudy"
        45, 48 -> "foggy"
        in 51..57 -> "drizzle"
        61, 80 -> "light rain"
        63, 81 -> "rain"
        65, 82 -> "heavy rain"
        66, 67 -> "freezing rain"
        in 71..77, 85, 86 -> "snow"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "mixed weather"
    }
}
