package com.phoneai.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Live weather via Open-Meteo geocoding and forecast endpoints; no AI request or API key is needed. */
object WeatherService {
    private const val TIMEOUT_MS = 8_000

    fun isWeatherRequest(text: String): Boolean {
        val s = text.lowercase(Locale.ROOT)
        val hasWeather = Regex("\\b(weather|forecast|rain|raining|temperature outside)\\b").containsMatchIn(s)
        val asksLive = Regex("\\b(current|today|tonight|tomorrow|now|outside|in my area|what'?s|what is|check|tell me|will it|is it)\\b").containsMatchIn(s) ||
            Regex("\\b(weather|forecast)\\s+(in|for|at)\\b").containsMatchIn(s)
        return hasWeather && asksLive
    }

    fun isWeatherTopic(text: String): Boolean =
        Regex("(?i)\\b(weather|forecast|rain|raining|temperature outside)\\b").containsMatchIn(text)

    /** Extracts place names case-insensitively, including "weather today in benin city Nigeria". */
    fun requestedPlace(text: String): String {
        val pattern = Regex(
            "(?i)\\b(?:in|for|at)\\s+(.+?)(?:\\s+(?:right now|at the moment|currently|today|tonight|tomorrow|now|this weekend|please)\\b|[?.!]|$)"
        )
        val match = pattern.find(text) ?: return ""
        val place = match.groupValues[1].trim().trimEnd('.', '?', '!', ',')
            .replace(Regex("\\s+"), " ")
        if (place.isBlank() || place.equals("my area", true) || place.equals("here", true)) return ""
        return place
    }

    /** Keep the last explicitly requested weather location so short follow-ups stay in context. */
    fun rememberPlace(context: android.content.Context, place: String) {
        val clean = place.trim().take(100)
        if (clean.isNotBlank()) context.getSharedPreferences("phoneai", android.content.Context.MODE_PRIVATE)
            .edit().putString("last_weather_place", clean).apply()
    }

    fun lastPlace(context: android.content.Context): String =
        context.getSharedPreferences("phoneai", android.content.Context.MODE_PRIVATE)
            .getString("last_weather_place", "").orEmpty()

    fun current(place: String): String {
        val clean = place.trim().take(100)
        if (clean.isBlank()) return "Which city or town should I check? I can fetch live weather without using an AI API call."
        return try {
            // Accept both "Benin City, Nigeria" and "Benin City Nigeria". When no comma
            // is supplied, progressively separate trailing qualifiers and match them against
            // the geocoder's region/country metadata instead of searching the whole phrase.
            val commaParts = clean.split(",").map { it.trim() }.filter { it.isNotBlank() }
            var results: org.json.JSONArray? = null
            var requestedQualifiers: List<String> = emptyList()
            if (commaParts.size > 1) {
                val geo = geocode(commaParts.first())
                results = geo.optJSONArray("results")
                requestedQualifiers = commaParts.drop(1)
            } else {
                val words = clean.split(Regex("\\s+"))
                for (keep in words.size downTo 1) {
                    val candidate = words.take(keep).joinToString(" ")
                    val qualifiers = words.drop(keep)
                    val found = geocode(candidate).optJSONArray("results")
                    if (found != null && found.length() > 0) {
                        val matching = if (qualifiers.isEmpty()) found else org.json.JSONArray().apply {
                            for (i in 0 until found.length()) {
                                val item = found.getJSONObject(i)
                                val metadata = (item.optString("admin1", "") + " " + item.optString("country", "")).lowercase(Locale.ROOT)
                                if (qualifiers.all { metadata.contains(it.lowercase(Locale.ROOT)) }) put(item)
                            }
                        }
                        if (matching.length() > 0) {
                            results = matching
                            requestedQualifiers = qualifiers
                            break
                        }
                        if (qualifiers.isEmpty()) {
                            results = found
                            break
                        }
                    }
                }
            }
            if (results == null || results.length() == 0) return "I couldn't find \"$clean\". Try a city and country, such as \"Benin City, Nigeria\"."
            val location = (0 until results.length()).map { results!!.getJSONObject(it) }.firstOrNull { candidate ->
                val metadata = (candidate.optString("admin1", "") + " " + candidate.optString("country", "")).lowercase(Locale.ROOT)
                requestedQualifiers.all { metadata.contains(it.lowercase(Locale.ROOT)) }
            } ?: results!!.getJSONObject(0)
            val lat = location.optDouble("latitude", Double.NaN)
            val lon = location.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return "The weather service returned incomplete coordinates for $clean."
            val name = location.optString("name", clean)
            val admin = location.optString("admin1", "")
            val country = location.optString("country", "")
            val label = listOf(name, admin, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
            val weather = getJson(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1"
            )
            val now = weather.optJSONObject("current") ?: return "The weather service returned no current conditions for $label."
            val daily = weather.optJSONObject("daily")
            val description = weatherDescription(now.optInt("weather_code", -1))
            val temp = now.optDouble("temperature_2m", Double.NaN)
            val feels = now.optDouble("apparent_temperature", Double.NaN)
            val humidity = now.optInt("relative_humidity_2m", -1)
            val wind = now.optDouble("wind_speed_10m", Double.NaN)
            val rain = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
            val high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
            val low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
            val updated = now.optString("time", "")
            val parts = mutableListOf<String>()
            parts.add("Live weather for $label: $description")
            if (!temp.isNaN()) parts.add(String.format(Locale.US, "%.1f°C", temp))
            if (!feels.isNaN()) parts.add(String.format(Locale.US, "feels like %.1f°C", feels))
            if (humidity >= 0) parts.add("humidity $humidity%")
            if (!wind.isNaN()) parts.add(String.format(Locale.US, "wind %.0f km/h", wind))
            if (!high.isNaN() && !low.isNaN()) parts.add(String.format(Locale.US, "today's high/low %.1f°/%.1f°C", high, low))
            if (rain >= 0) parts.add("maximum precipitation chance $rain%")
            val timestamp = if (updated.isNotBlank()) " Local observation/model time: $updated." else ""
            parts.joinToString("; ") + "." + timestamp + " Source: Open-Meteo (open-meteo.com)."
        } catch (e: Exception) {
            "I couldn't retrieve live weather right now (" + (e.message?.take(100) ?: "network error") + "). No AI quota was used; please retry when the data service is reachable."
        }
    }

    private fun geocode(name: String): JSONObject {
        val encoded = URLEncoder.encode(name, "UTF-8")
        return getJson("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=10&language=en&format=json")
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PhoneAI/1.0 (Android weather lookup)")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(500_000) } ?: ""
            if (code !in 200..299) throw IllegalStateException("weather data service returned HTTP $code")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "conditions unavailable"
    }
}
