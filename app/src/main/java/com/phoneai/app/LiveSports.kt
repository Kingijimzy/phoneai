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
        val s = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ")
        if (Regex("\\b(credit|test|exam|bmi|score my|my score)\\b").containsMatchIn(s)) return false
        val scoreLanguage = Regex("\\b(score|scoreline|score line|result|results|final score|who won|did .+ win|fixture|playing today|match today|game today|live score|won|winning)\\b").containsMatchIn(s)
        val fixtureLanguage = Regex("\\b(when|what|which|is|are|next|upcoming|latest|last|today|tonight|playing|play|plays|match|matches|game|games|fixture|fixtures|kick ?off)\\b").containsMatchIn(s)
        val teamOrLeague = Regex("\\b(real madrid|madrid|barcelona|barca|manchester united|man united|man utd|manchester city|man city|arsenal|chelsea|liverpool|tottenham|spurs|bayern|juventus|juve|psg|paris saint germain|inter milan|ac milan|atletico|atlético|premier league|la liga|champions league|europa league|bundesliga|serie a|ligue 1|nba|nfl|nhl|mlb|lakers|warriors|celtics|chiefs|yankees)\\b").containsMatchIn(s)
        return scoreLanguage || (teamOrLeague && fixtureLanguage)
    }

    /** The team/club words in the question, with filler removed. */
    fun teamQuery(text: String): String {
        var s = text.lowercase().replace(Regex("[^a-z0-9' .-]"), " ")
        s = s.replace(Regex("\\b(what|whats|what's|is|are|the|score|scoreline|score line|line|result|results|final|of|for|on|in|today|tonight|yesterday|now|live|latest|current|last|next|upcoming|when|do|does|they|their|play|plays|playing|kickoff|kick off|please|tell|me|how|doing|vs|against|and|a|match|matches|game|games|fixture|fixtures|win|won|who)\\b"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        // Keep the full club name intact; only expand standalone aliases.
        for ((k, v) in aliases.entries.sortedByDescending { it.key.length }) {
            if (k == "real") continue
            s = s.replace(Regex("\\b" + Regex.escape(k) + "\\b"), v)
        }
        if (Regex("\\breal\\s+madrid\\b").containsMatchIn(s)) s = s.replace(Regex("\\breal\\s+madrid\\b"), "real madrid")
        else s = s.replace(Regex("\\breal\\b"), "real madrid")
        return s.replace(Regex("\\s+"), " ").trim()
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
        val upcoming = Regex("\\b(next|upcoming|when|future)\\b").containsMatchIn(question.lowercase()) &&
            Regex("\\b(play|plays|playing|match|matches|game|games|fixture|fixtures|kick ?off)\\b").containsMatchIn(question.lowercase())
        val today = fmt.format(cal.time)
        val from: String
        val to: String
        if (upcoming) {
            from = today
            cal.add(Calendar.DAY_OF_YEAR, 7)
            to = fmt.format(cal.time)
        } else {
            to = today
            cal.add(Calendar.DAY_OF_YEAR, -1)
            from = fmt.format(cal.time)
        }
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
