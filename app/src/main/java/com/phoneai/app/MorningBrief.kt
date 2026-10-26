package com.phoneai.app

import android.content.Context
import java.util.Calendar
import java.util.Locale

/** What goes into one brief. Every part is optional: a failed fetch simply drops its section. */
internal data class BriefParts(
    val name: String = "",
    val weather: String = "",
    val team: String = "",
    val markets: List<String> = emptyList(),
    val openTasks: List<String> = emptyList(),
    val overdue: Int = 0,
    val lessons: Int = 0
)

internal object BriefComposer {
    fun greeting(hour: Int, name: String): String {
        val part = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello" }
        return if (name.isBlank()) part else "$part, $name"
    }

    fun compose(p: BriefParts, hour: Int): String {
        val out = ArrayList<String>()
        out += greeting(hour, p.name) + "."
        if (p.weather.isNotBlank()) out += "Weather: " + p.weather.trim()
        if (p.team.isNotBlank()) out += "Your team: " + p.team.trim()
        if (p.markets.isNotEmpty()) out += "Markets: " + p.markets.joinToString(" · ")
        if (p.openTasks.isNotEmpty() || p.overdue > 0) {
            out += "Tasks: ${p.openTasks.size} open" + (if (p.overdue > 0) ", ${p.overdue} overdue" else "") +
                (if (p.openTasks.isNotEmpty()) " - " + p.openTasks.take(3).joinToString("; ") else "")
        }
        if (p.lessons > 0) out += "I'm carrying ${p.lessons} lesson(s) from past runs so I make fewer mistakes."
        if (out.size == 1) out += "Nothing new to report. Tell me what you'd like to do today."
        return out.joinToString("\n")
    }

    /** First meaningful line only, capped, so notifications stay readable. */
    fun oneLine(text: String, max: Int = 160): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(max)
}

object MorningBrief {
    private fun fact(c: Context, vararg rels: String): String =
        KnowledgeGraph.all(c).firstOrNull { it.a == "user" && it.rel in rels }?.b.orEmpty()

    /** Gathers live public data (no AI call). Blocking; call off the main thread. */
    fun build(c: Context): String {
        val now = System.currentTimeMillis()
        val tasks = try { TaskStore.all(c).filter { it.status !in setOf("completed", "done", "failed", "cancelled") } } catch (_: Exception) { emptyList() }
        val place = fact(c, "lives in", "city", "location", "hometown", "town")
        val weather = if (place.isBlank()) "" else try { BriefComposer.oneLine(WeatherService.current(place)) } catch (_: Exception) { "" }
        val team = fact(c, "favorite team", "favourite team", "supports", "support", "team").let { t ->
            if (t.isBlank()) "" else try { BriefComposer.oneLine(LiveSports.answer("$t score").orEmpty()) } catch (_: Exception) { "" }
        }
        val markets = ArrayList<String>()
        Prefs.watchlist(c).split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(3).forEach { sym ->
            try {
                val p = Bybit.price(c, sym)
                markets += sym.removeSuffix("USDT") + " " + Ta.price(p)
            } catch (_: Exception) { }
        }
        val lessons = try { ExperienceStore.all(c).size } catch (_: Exception) { 0 }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return BriefComposer.compose(
            BriefParts(Prefs.userName(c), weather, team, markets, tasks.sortedBy { it.dueAt ?: Long.MAX_VALUE }.map { it.title.take(40) },
                tasks.count { it.dueAt != null && it.dueAt!! <= now }, lessons),
            hour
        )
    }
}
