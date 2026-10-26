package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One lesson learned from a real run. Privacy-minimal: only a short task gist (keywords from the
 * request), the tool, a failure category and a derived tip are kept — never arguments, screen text
 * or credentials.
 */
data class Experience(
    val task: String,
    val tool: String,
    val kind: String,          // "failure" or "worked"
    val category: String,      // failure category, or "" for worked
    val tip: String,
    val count: Int,
    val lastAt: Long,
    val steps: String = ""     // for "worked": validated tool sequence
)

/** Pure ranking/merge rules so the learning loop is unit-testable without Android. */
internal object ExperiencePolicy {
    const val MAX = 60
    private const val DAY = 86_400_000L

    fun gist(request: String): String =
        MemoryRanking.tokens(request).take(8).sorted().joinToString(" ")

    fun tipFor(tool: String, category: String): String = when (category) {
        "target_not_found" -> "$tool could not find its target: re-read the screen first, then use visible text instead of a guessed id."
        "permission_or_accessibility" -> "$tool needs a permission/accessibility access: tell the user which one to enable instead of retrying."
        "network_or_timeout" -> "$tool hit a network problem: try once more later or use another source; never rapid-retry."
        "rate_limited" -> "$tool was rate limited: slow down or use the configured backup provider."
        "invalid_arguments" -> "$tool got bad arguments: re-check the tool contract before calling it again."
        "unsupported_capability" -> "$tool is not supported here: pick a different tool for this job."
        "approval_required" -> "$tool needs the user's approval: ask them, do not work around the gate."
        else -> "$tool failed: inspect fresh state and change approach instead of repeating the same call."
    }

    /** Merge a new observation into the list. A later success of a tool retires its older failures for similar tasks. */
    fun merge(list: List<Experience>, new: Experience): List<Experience> {
        val out = list.toMutableList()
        if (new.kind == "worked") {
            out.removeAll { it.kind == "failure" && it.tool in new.steps.split(">") && MemoryRanking.similarity(it.task, new.task) >= 0.5 }
        }
        val i = out.indexOfFirst {
            it.kind == new.kind && it.tool == new.tool && it.category == new.category &&
                MemoryRanking.similarity(it.task, new.task) >= 0.5
        }
        if (i >= 0) {
            val old = out[i]
            out[i] = new.copy(count = old.count + 1, steps = new.steps.ifBlank { old.steps })
        } else out.add(new)
        out.sortByDescending { it.lastAt }
        return out.take(MAX)
    }

    /** similarity x recency x reinforcement; unrelated lessons stay out of the prompt. */
    fun rank(list: List<Experience>, request: String, now: Long, limit: Int): List<Experience> {
        val q = gist(request)
        if (q.isBlank()) return emptyList()
        return list.map { e ->
            val sim = MemoryRanking.similarity(e.task, q)
            val ageDays = ((now - e.lastAt) / DAY).coerceAtLeast(0)
            val recency = 1.0 / (1.0 + ageDays / 30.0)
            val reinforce = 1.0 + (e.count - 1).coerceAtMost(4) * 0.25
            e to sim * recency * reinforce
        }.filter { it.second >= 0.12 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    fun age(now: Long, at: Long): String {
        val d = ((now - at) / DAY).coerceAtLeast(0)
        return if (d == 0L) "today" else "${d}d ago"
    }

    fun block(items: List<Experience>, now: Long): String = items.joinToString("\n") { e ->
        if (e.kind == "worked") "- WORKED (${e.count}x, ${age(now, e.lastAt)}): for a similar task this sequence succeeded: ${e.steps.replace(">", " → ")}. Re-verify before reusing."
        else "- AVOID (${e.count}x, ${age(now, e.lastAt)}): ${e.tip}"
    }
}

object ExperienceStore {
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "orion_experience.json")

    private fun load(c: Context): List<Experience> = try {
        val f = file(c)
        if (!f.exists()) emptyList() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { val o = a.getJSONObject(it)
                Experience(o.optString("task"), o.optString("tool"), o.optString("kind"), o.optString("category"),
                    o.optString("tip"), o.optInt("count", 1), o.optLong("lastAt"), o.optString("steps")) }
        }
    } catch (_: Exception) { emptyList() }

    private fun save(c: Context, list: List<Experience>) {
        try {
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("task", it.task).put("tool", it.tool).put("kind", it.kind)
                .put("category", it.category).put("tip", it.tip).put("count", it.count).put("lastAt", it.lastAt).put("steps", it.steps)) }
            val tmp = File(c.filesDir, "orion_experience.json.tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(file(c))) { file(c).writeText(a.toString()); tmp.delete() }
        } catch (_: Exception) { }
    }

    fun recordFailure(c: Context, request: String, tool: String, result: String) = synchronized(lock) {
        val cat = ActionLearningPolicy.classifyFailure(result).ifBlank { return@synchronized }
        val task = ExperiencePolicy.gist(request).ifBlank { return@synchronized }
        save(c, ExperiencePolicy.merge(load(c), Experience(task, tool, "failure", cat,
            ExperiencePolicy.tipFor(tool, cat), 1, System.currentTimeMillis())))
    }

    /** Only call after the completion gate passed, so unvalidated routines never become "what works". */
    fun recordSuccess(c: Context, request: String, tools: List<String>) = synchronized(lock) {
        val seq = tools.filter { it.isNotBlank() && it != "done" }.distinct().take(6)
        val task = ExperiencePolicy.gist(request)
        if (seq.isEmpty() || task.isBlank()) return@synchronized
        save(c, ExperiencePolicy.merge(load(c), Experience(task, seq.first(), "worked", "", "", 1,
            System.currentTimeMillis(), seq.joinToString(">"))))
    }

    fun promptBlock(c: Context, request: String?, limit: Int = 5): String = synchronized(lock) {
        if (request.isNullOrBlank()) return@synchronized ""
        val now = System.currentTimeMillis()
        ExperiencePolicy.block(ExperiencePolicy.rank(load(c), request, now, limit), now)
    }

    fun all(c: Context): List<Experience> = synchronized(lock) { load(c) }
    fun clear(c: Context) = synchronized(lock) { file(c).delete() }
}
