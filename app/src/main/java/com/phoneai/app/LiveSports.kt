package com.phoneai.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Live and recent scores from ESPN's public scoreboard JSON (no key, no AI call). */
object LiveSports {
    private val leagues = listOf(
        "soccer/eng.1" to "Premier League", "soccer/uefa.champions" to "Champions League", "soccer/uefa.europa" to "Europa League",
        "soccer/esp.1" to "La Liga", "soccer/ger.1" to "Bundesliga", "soccer/ita.1" to "Serie A", "soccer/fra.1" to "Ligue 1",
        "soccer/eng.fa" to "FA Cup", "soccer/eng.league_cup" to "League Cup", "soccer/usa.1" to "MLS",
        "basketball/nba" to "NBA", "football/nfl" to "NFL", "hockey/nhl" to "NHL", "baseball/mlb" to "MLB"
    )
    private val aliases = mapOf("man utd" to "manchester united", "man united" to "manchester united", "man city" to "manchester city",
        "spurs" to "tottenham", "barca" to "barcelona", "real" to "real madrid", "juve" to "juventus", "psg" to "paris saint-germain",
        "bayern" to "bayern munich", "wolves" to "wolverhampton", "the lakers" to "lakers", "the warriors" to "warriors")

    fun isScoreRequest(text: String): Boolean {
        val s = text.lowercase()
        return Regex("\\b(score|scoreline|score line|result|results|final score|who won|did .+ win|fixture|playing today|match today|game today|live score)\\b").containsMatchIn(s) &&
            !Regex("\\b(credit|test|exam|bmi|score my|my score)\\b").containsMatchIn(s)
    }

    /** The team/club words in the question, with filler removed. */
    fun teamQuery(text: String): String {
        var s = text.lowercase().replace(Regex("[^a-z0-9' .-]"), " ")
        s = s.replace(Regex("\\b(what|whats|what's|is|the|score|scoreline|score line|line|result|results|final|of|for|on|in|today|tonight|yesterday|now|live|latest|current|did|win|who|won|game|match|fixture|playing|please|tell|me|how|are|doing|vs|against|and|a)\\b"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        for ((k, v) in aliases) if (s.contains(k)) s = s.replace(k, v)
        return s
    }

    /** Pure formatter over one ESPN scoreboard payload; returns lines for games involving [team] (any game if blank). */
    internal fun format(json: String, team: String, leagueName: String): List<String> {
        val out = ArrayList<String>()
        val events = JSONObject(json).optJSONArray("events") ?: return out
        val wanted = team.lowercase().split(' ').filter { it.length > 2 }
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val teams = comp.optJSONArray("competitors") ?: continue
            val names = (0 until teams.length()).map { teams.getJSONObject(it).optJSONObject("team")?.optString("displayName").orEmpty() }
            if (wanted.isNotEmpty() && names.none { n -> wanted.all { n.lowercase().contains(it) || it in n.lowercase().replace("-", " ") } }) continue
            val home = (0 until teams.length()).map { teams.getJSONObject(it) }.firstOrNull { it.optString("homeAway") == "home" } ?: continue
            val away = (0 until teams.length()).map { teams.getJSONObject(it) }.firstOrNull { it.optString("homeAway") == "away" } ?: continue
            val status = e.optJSONObject("status")?.optJSONObject("type")
            val state = status?.optString("state").orEmpty() // pre, in, post
            val detail = status?.optString("shortDetail").orEmpty().ifBlank { status?.optString("description").orEmpty() }
            val hn = home.optJSONObject("team")?.optString("displayName").orEmpty()
            val an = away.optJSONObject("team")?.optString("displayName").orEmpty()
            val line = if (state == "pre") "$hn vs $an — kicks off $detail"
                else "$hn ${home.optString("score", "0")} - ${away.optString("score", "0")} $an (${if (state == "in") "LIVE $detail" else detail})"
            out.add("$line · $leagueName")
        }
        return out
    }

    private fun get(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "Orion/1.0") }
        try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText().take(1_500_000) } else null } finally { c.disconnect() }
    } catch (_: Exception) { null }

    fun answer(question: String): String? {
        val team = teamQuery(question)
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val to = fmt.format(cal.time); cal.add(Calendar.DAY_OF_YEAR, -1); val from = fmt.format(cal.time)
        val found = ArrayList<String>()
        for ((path, name) in leagues) {
            val body = get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard?dates=$from-$to&limit=100") ?: continue
            found.addAll(try { format(body, team, name) } catch (_: Exception) { emptyList() })
            if (team.isNotBlank() && found.size >= 3) break
        }
        if (found.isEmpty()) return null
        return (if (team.isBlank()) "Scores (yesterday and today, ESPN):" else "Latest for \"$team\" (ESPN):") + "\n" + found.take(8).joinToString("\n")
    }
}
