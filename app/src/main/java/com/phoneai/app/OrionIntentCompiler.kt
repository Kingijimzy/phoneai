package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * DAWN, the duty-aware wake network: Orion learns when the owner tends to ask for what (hour of the week x task shape,
 * counted by distinct days), then predicts what he is likely to need in the next hours so Orion can prepare it
 * unprompted instead of waiting. Stores task shape only (the same redacted gist the lesson store uses), never the text.
 */
internal object OrionIntentCompiler {
    data class Prediction(val gist: String, val days: Int, val inHours: Int)

    /** Pure model: slot (0..167) -> gist -> set of day keys. */
    class Model(private val data: MutableMap<Int, MutableMap<String, MutableSet<Int>>> = HashMap()) {
        fun observe(gist: String, slot: Int, dayKey: Int) {
            if (gist.isBlank()) return
            data.getOrPut(slot) { HashMap() }.getOrPut(gist) { HashSet() }.add(dayKey)
            trim()
        }

        fun predict(slot: Int, horizonHours: Int, minDays: Int): List<Prediction> {
            val out = HashMap<String, Prediction>()
            for (h in 0..horizonHours) {
                val s = (slot + h) % 168
                data[s]?.forEach { (g, days) ->
                    if (days.size >= minDays) {
                        val p = Prediction(g, days.size, h)
                        val prev = out[g]
                        if (prev == null || p.days > prev.days) out[g] = p
                    }
                }
            }
            return out.values.sortedWith(compareByDescending<Prediction> { it.days }.thenBy { it.inHours }).take(4)
        }

        private fun trim() {
            val total = data.values.sumOf { it.size }
            if (total <= 600) return
            data.values.forEach { m -> m.entries.removeAll { it.value.size <= 1 } }
            data.entries.removeAll { it.value.isEmpty() }
        }

        fun toJson(): String {
            val o = JSONObject()
            data.forEach { (slot, m) -> o.put(slot.toString(), JSONObject().also { j -> m.forEach { (g, d) -> j.put(g, org.json.JSONArray(d.toList())) } }) }
            return o.toString()
        }

        companion object {
            fun fromJson(s: String): Model {
                val m = Model()
                runCatching {
                    val o = JSONObject(s)
                    o.keys().forEach { k ->
                        val j = o.getJSONObject(k)
                        j.keys().forEach { g -> val a = j.getJSONArray(g); for (i in 0 until a.length()) m.observe(g, k.toInt(), a.getInt(i)) }
                    }
                }
                return m
            }
        }
    }

    fun slotOf(now: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return ((c.get(Calendar.DAY_OF_WEEK) - 1) * 24 + c.get(Calendar.HOUR_OF_DAY)) % 168
    }

    fun dayKey(now: Long): Int = (now / 86_400_000L).toInt()

    fun shape(text: String): String = ExperiencePolicy.gist(text).split(' ').filter { it.isNotBlank() }.take(3).joinToString(" ")

    private const val PREFS = "orion_intent_v1"
    private fun load(c: Context) = Model.fromJson(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("m", "{}") ?: "{}")

    fun record(c: Context, text: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            val g = shape(text)
            if (g.isBlank()) return
            synchronized(this) {
                val m = load(c); m.observe(g, slotOf(now), dayKey(now))
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("m", m.toJson()).apply()
            }
        }
    }

    fun predictions(c: Context, now: Long = System.currentTimeMillis()): List<Prediction> =
        runCatching { synchronized(this) { load(c).predict(slotOf(now), 2, 3) } }.getOrDefault(emptyList())

    fun hint(c: Context, now: Long = System.currentTimeMillis()): String {
        val p = predictions(c, now)
        if (p.isEmpty()) return ""
        return "ANTICIPATION (learned from his routine): around now he usually asks about " +
            p.joinToString("; ") { "\"${it.gist}\" (${it.days} days${if (it.inHours > 0) ", in ~${it.inHours}h" else ""})" } +
            ". Prepare it quietly before he asks (fetch, check, draft) and have it ready; mention it only if it is useful."
    }
}
