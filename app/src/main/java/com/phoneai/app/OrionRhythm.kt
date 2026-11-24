package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Learns Kingi's daily rhythm from how he actually talks to Orion (no sensors, nothing leaves the phone):
 * what he asks about at which hour and weekday, and how he writes. Used to expect what he will want next and to
 * answer in his register instead of a generic one.
 */
internal object OrionRhythm {
    private const val PREFS = "orion_rhythm"
    private const val MAX_KEYS = 400

    /** So Kingi can see this is only learned from what he types, and wipe it if he wants to. */
    fun reset(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    fun messageCount(c: Context): Int = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("msgs", 0)

    fun topic(request: String): String {
        OrionScenes.detect(request)?.let { return it.id }
        val r = request.lowercase()
        return when {
            Regex("""\b(github|push|merge|pr|repo|commit|apk|kotlin|python|script|code|build)\b""").containsMatchIn(r) -> "code"
            Regex("""\b(bybit|trade|btc|eth|chart|position|smc|signal|market)\b""").containsMatchIn(r) -> "trading"
            Regex("""\b(play|music|song|spotify|youtube)\b""").containsMatchIn(r) -> "music"
            Regex("""\b(whatsapp|telegram|email|gmail|message|text)\b""").containsMatchIn(r) -> "messages"
            Regex("""\b(remind|alarm|timer|calendar|schedule)\b""").containsMatchIn(r) -> "planning"
            else -> "general"
        }
    }

    fun record(c: Context, request: String, now: Long = System.currentTimeMillis()) {
        val text = request.trim()
        if (text.isEmpty() || text.startsWith("[ORION") || text.equals("continue", true)) return
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val counts = runCatching { JSONObject(sp.getString("counts", "{}")) }.getOrDefault(JSONObject())
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val t = topic(text)
        listOf("h${cal.get(Calendar.HOUR_OF_DAY)}|$t", "d${cal.get(Calendar.DAY_OF_WEEK)}|$t", "all|$t").forEach { k -> counts.put(k, counts.optInt(k) + 1) }
        if (counts.length() > MAX_KEYS) counts.keys().asSequence().filter { it.startsWith("d") }.take(counts.length() - MAX_KEYS).toList().forEach { counts.remove(it) }
        sp.edit().putString("counts", counts.toString())
            .putInt("msgs", sp.getInt("msgs", 0) + 1)
            .putInt("words", sp.getInt("words", 0) + text.split(Regex("\\s+")).size.coerceAtMost(200))
            .apply()
    }

    /** Pure: the topic he most often raises around [hour], if it is a real pattern and not a guess. */
    fun predictFrom(counts: Map<String, Int>, hour: Int): Pair<String, Pair<Int, Int>>? {
        val near = listOf((hour + 23) % 24, hour, (hour + 1) % 24)
        val byTopic = HashMap<String, Int>()
        counts.forEach { (k, v) ->
            val h = k.substringBefore('|'); if (h.startsWith("h") && h.drop(1).toIntOrNull() in near) byTopic.merge(k.substringAfter('|'), v, Int::plus)
        }
        val total = byTopic.values.sum()
        val top = byTopic.filterKeys { it != "general" }.maxByOrNull { it.value } ?: return null
        return if (total >= 5 && top.value >= 3 && top.value * 10 >= total * 4) top.key to (top.value to total) else null
    }

    fun section(c: Context, now: Long = System.currentTimeMillis()): String {
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val counts = runCatching { JSONObject(sp.getString("counts", "{}")) }.getOrDefault(JSONObject())
            .let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }
        val msgs = sp.getInt("msgs", 0)
        if (msgs < 5) return ""
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val avg = sp.getInt("words", 0) / msgs
        val register = when { avg <= 8 -> "short and fast; answer in kind, result first"; avg <= 25 -> "plain and direct"; else -> "detailed; he is fine with fuller answers" }
        return buildString {
            append("HOW KINGI USES YOU: he writes ").append(avg).append(" words a message (").append(register).append("). ")
            predictFrom(counts, hour)?.let { (topic, n) ->
                append("Around ").append(hour).append(":00 he usually asks about ").append(topic).append(" (")
                append(n.first).append(" of ").append(n.second).append(" times near this hour); expect it, and if he opens without a task offer it first. ")
            }
            val top = counts.filterKeys { it.startsWith("all|") }.entries.sortedByDescending { it.value }.take(3)
            if (top.isNotEmpty()) append("Overall he mostly asks about ").append(top.joinToString { it.key.substringAfter('|') }).append('.')
        }.trim()
    }
}
