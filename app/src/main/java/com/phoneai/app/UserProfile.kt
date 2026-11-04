package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One durable, user-level fact Orion learned from the user's own words. */
data class ProfileFact(val key: String, val value: String, val category: String, val updated: Long)

/**
 * Extracts stable facts about the user from ordinary conversation, without a model call.
 *
 * Deliberately conservative: it only matches clear first-person statements, ignores
 * anything that looks like a secret/contact/URL, and never reads the assistant's text.
 * Extraction is cheap and runs on every turn; the durable merge lives in [UserProfileStore].
 */
internal object MemoryExtractionPolicy {
    data class Fact(val key: String, val value: String, val category: String)
    const val MAX_FACTS = 5

    /** Keep only the first clause: "Lagos and I love rice" -> "Lagos". */
    private fun cutClause(v: String): String {
        var s = v
        for (stop in listOf(" and ", " but ", " because ", " so ", " then ")) {
            val i = s.indexOf(stop, ignoreCase = true)
            if (i > 0) s = s.substring(0, i)
        }
        return s
    }

    private fun clean(v: String): String =
        cutClause(v).trim().trim('.', ',', '!', '?', ';', ':').replace(Regex("\\s+"), " ").trim().take(120)

    private fun looksSensitive(v: String): Boolean =
        v.contains("@") || v.contains("http", true) ||
            Regex("\\d{6,}").containsMatchIn(v) ||
            listOf("password", "passcode", "token", "secret", "api key", "otp", "verification code")
                .any { v.contains(it, true) }

    fun extract(text: String): List<Fact> {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Fact>()
        fun put(key: String, value: String, category: String) {
            val v = clean(value)
            if (v.length < 2 || looksSensitive(v)) return
            out[key] = Fact(key, v, category)
        }

        Regex("(?i)\\bmy name is ([A-Z][a-z]{1,20})\\b").findAll(t).lastOrNull()?.let { put("name", it.groupValues[1], "identity") }
        Regex("(?i)\\bcall me ([A-Z][a-z]{1,20})\\b").findAll(t).lastOrNull()?.let { put("name", it.groupValues[1], "identity") }
        Regex("(?i)\\bi live in ([A-Za-z][A-Za-z .'-]{1,40})").findAll(t).lastOrNull()?.let { put("city", it.groupValues[1], "identity") }
        Regex("(?i)\\bi(?:'m| am) from ([A-Za-z][A-Za-z .'-]{1,40})").findAll(t).lastOrNull()?.let { put("home", it.groupValues[1], "identity") }
        Regex("(?i)\\bi work (?:at|for) ([A-Za-z0-9][A-Za-z0-9 .&'-]{1,50})").findAll(t).lastOrNull()?.let { put("work", it.groupValues[1], "work") }
        Regex("(?i)\\bi work as (?:an? )?([A-Za-z][A-Za-z ]{1,40})").findAll(t).lastOrNull()?.let { put("role", it.groupValues[1], "work") }
        Regex("(?i)\\bmy birthday is ([^.!?\\n]{3,40})").findAll(t).lastOrNull()?.let { put("birthday", it.groupValues[1], "identity") }
        Regex("(?i)\\bi(?:'m| am) allergic to ([^.!?\\n]{2,60})").findAll(t).lastOrNull()?.let { put("allergy", it.groupValues[1], "health") }
        Regex("(?i)\\bi (?:usually|always|often) ([^.!?\\n]{3,80})").findAll(t).lastOrNull()?.let { put("routine", it.groupValues[1], "routine") }
        Regex("(?i)\\bi (?:like|love) ([^.!?\\n]{2,80})").findAll(t).lastOrNull()?.let { put("likes", it.groupValues[1], "preference") }
        Regex("(?i)\\bi (?:don'?t like|hate|dislike) ([^.!?\\n]{2,80})").findAll(t).lastOrNull()?.let { put("dislikes", it.groupValues[1], "preference") }
        Regex("(?i)\\bmy (wife|husband|partner|girlfriend|boyfriend|son|daughter|mother|father|boss|friend) (?:is |named )?([A-Z][a-z]{1,20})\\b").findAll(t).lastOrNull()
            ?.let { put("person:" + it.groupValues[1].lowercase(), it.groupValues[2], "people") }

        return out.values.take(MAX_FACTS)
    }
}

/** Durable, inspectable profile of the user. Plain JSON beside the other Orion stores. */
object UserProfileStore {
    private const val FILE = "orion_user_profile.json"
    private const val MAX_FACTS = 60
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, FILE)

    fun all(c: Context): List<ProfileFact> = synchronized(lock) {
        try {
            val f = file(c)
            if (!f.exists()) return@synchronized emptyList()
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ProfileFact(o.optString("key"), o.optString("value"), o.optString("category"), o.optLong("updated"))
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(c: Context, list: List<ProfileFact>) {
        try {
            val a = JSONArray()
            list.forEach { f ->
                a.put(JSONObject().put("key", f.key).put("value", f.value)
                    .put("category", f.category).put("updated", f.updated))
            }
            file(c).writeText(a.toString())
        } catch (_: Exception) { }
    }

    /** Merge facts from one user turn; newest value wins, capped so the prompt stays small. */
    fun ingest(c: Context, userText: String): Int = synchronized(lock) {
        val facts = MemoryExtractionPolicy.extract(userText)
        if (facts.isEmpty()) return@synchronized 0
        val now = System.currentTimeMillis()
        val map = LinkedHashMap<String, ProfileFact>()
        all(c).forEach { map[it.key] = it }
        facts.forEach { f -> map[f.key] = ProfileFact(f.key, f.value, f.category, now) }
        save(c, map.values.sortedByDescending { it.updated }.take(MAX_FACTS))
        facts.size
    }

    fun promptBlock(c: Context): String {
        val list = all(c)
        if (list.isEmpty()) return "(nothing learned about the user yet)"
        return list.sortedWith(compareBy<ProfileFact> { it.category }.thenBy { it.key })
            .joinToString("\n") { "${it.category}: ${it.key.replace(':', ' ')} = ${it.value}" }
            .take(900)
    }

    /** Remove one key, or everything when the key is blank. Returns a user-facing message. */
    fun forget(c: Context, key: String): String = synchronized(lock) {
        val k = key.trim()
        if (k.isBlank()) {
            save(c, emptyList())
            return@synchronized "I cleared everything I had learned about you."
        }
        val list = all(c)
        val kept = list.filterNot { it.key.equals(k, true) || it.key.startsWith("$k:", true) }
        if (kept.size == list.size) return@synchronized "I have nothing saved under '$k'."
        save(c, kept)
        "I forgot '$k'."
    }
}
