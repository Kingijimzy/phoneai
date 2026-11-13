package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.min

/**
 * Orion's executive layer: code, not prompt text.
 *
 * - [OrionExperience] records what Orion tried and how it ended, and retrieves it before the next decision
 *   ("did I try this before, did it fail?") so mistakes are not repeated.
 * - [OrionExecutive] wraps every tool result: a failure always carries the order to diagnose, reroute and verify,
 *   so there is no room to apologise and stop.
 * - [OrionDrives] is the desire engine: a persistent numeric state (pressures that grow with time and failure and
 *   fall when Orion acts) that decides what Orion wants to do next, independent of any prompt wording.
 */
internal object OrionExperience {
    data class Record(val tool: String, val key: String, val ok: Boolean, val reason: String, val at: Long)

    private const val PREFS = "orion_experience_v1"
    private const val KEY = "records"
    private const val MAX = 120
    private const val WINDOW_MS = 7L * 24 * 3600_000
    private val lock = Any()
    private val identityKeys = listOf("path", "url", "query", "package", "name", "id", "command", "contact", "app", "text")

    /** Stable signature of "this tool with these kinds of arguments". Pure. */
    fun signature(tool: String, args: JSONObject?): String {
        val canonical = buildString {
            append(tool.trim().lowercase())
            identityKeys.forEach { key ->
                val value = args?.optString(key, "")?.trim()?.lowercase().orEmpty()
                if (value.isNotEmpty()) append('|').append(key).append('=').append(value)
            }
        }
        // Never persist raw paths, URLs, contact names, commands or message text in the experience key.
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return tool.trim().lowercase() + "|" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun tokens(s: String): Set<String> = Regex("[a-z0-9]{4,}").findAll(s.lowercase()).map { it.value }.toSet()

    /** Failures that have not since been followed by a success of the same signature. Pure. */
    fun unresolvedFailures(records: List<Record>, now: Long): List<Record> =
        records.filter { !it.ok && it.at <= now && now - it.at <= WINDOW_MS }
            .filter { f -> records.none { it.ok && it.key == f.key && it.at > f.at } }
            .sortedByDescending { it.at }

    /** Failures relevant to a request, by word overlap with the tool name and the failure reason. Pure. */
    fun relevant(records: List<Record>, request: String, now: Long, limit: Int = 3): List<Record> {
        val want = tokens(request)
        if (want.isEmpty()) return emptyList()
        return unresolvedFailures(records, now)
            .map { it to want.intersect(tokens(it.tool.replace('_', ' ') + " " + it.reason)).size }
            .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.key }.take(limit)
    }

    fun priorFailure(records: List<Record>, key: String, now: Long): Record? =
        unresolvedFailures(records, now).firstOrNull { it.key == key }

    private fun load(c: Context): List<Record> = runCatching {
        val a = JSONArray(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Record(it.getString("t"), it.getString("k"), it.getBoolean("ok"), it.optString("r"), it.optLong("at")) } }
    }.getOrDefault(emptyList())

    fun all(c: Context): List<Record> = synchronized(lock) { load(c) }

    fun record(c: Context, tool: String, args: JSONObject?, ok: Boolean, reason: String, now: Long = System.currentTimeMillis()): Boolean {
        if (tool.isBlank() || now <= 0L) return false
        return runCatching {
            synchronized(lock) {
                val safeReason = if (ok) "" else AgentPrivacyFilter.safeOutput(reason)
                    .lineSequence().firstOrNull().orEmpty().take(140)
                val list = (load(c) + Record(tool, signature(tool, args), ok, safeReason, now)).takeLast(MAX)
                val arr = JSONArray()
                list.forEach { arr.put(JSONObject().put("t", it.tool).put("k", it.key).put("ok", it.ok).put("r", it.reason).put("at", it.at)) }
                c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).commit()
            }
        }.getOrDefault(false)
    }

    fun priorNote(c: Context, tool: String, args: JSONObject?, now: Long = System.currentTimeMillis()): String =
        runCatching { priorFailure(all(c), signature(tool, args), now)?.let { "PRIOR EXPERIENCE: this same action failed before (${it.reason}). Do not repeat it unchanged." }.orEmpty() }.getOrDefault("")

    /** Retrieval before decisions: shown in the prompt for the request Orion is about to act on. */
    fun requestBlock(c: Context, request: String, now: Long = System.currentTimeMillis()): String = runCatching {
        val fails = relevant(all(c), request, now)
        val lessons = OrionLessons.all(c).filter { l -> tokens(request).intersect(tokens(l)).isNotEmpty() }.takeLast(2)
        if (fails.isEmpty() && lessons.isEmpty()) return@runCatching ""
        buildString {
            append("EXPERIENCE (retrieved before you act: did you try this before, did it fail?):")
            fails.forEach { append("\n- ${it.tool} failed earlier: ${it.reason}. Choose a different route or fix the cause first.") }
            lessons.forEach { append("\n- lesson: ${it.take(160)}") }
        }
    }.getOrDefault("")
}

internal object OrionExecutive {
    /** Every tool result passes through here. A failure comes back with the executive order attached. */
    fun afterTool(c: Context, tool: String, args: JSONObject?, result: String, failed: Boolean, priorNote: String): String {
        val experienceSaved = OrionExperience.record(c, tool, args, !failed, if (failed) result else "")
        runCatching { OrionDrives.onToolOutcome(c, failed) }
        val output = directive(result, failed, priorNote)
        return if (experienceSaved) output else output + "\n[LEARNING STORAGE WARNING] The action result is unchanged, but Orion could not durably save this experience. Do not claim the lesson was recorded."
    }

    /** Pure. */
    fun directive(result: String, failed: Boolean, priorNote: String): String {
        if (!failed) return result
        return result + "\n[EXECUTIVE] This tool call failed. You own the outcome: do not apologise and do not stop. " +
            "1) Diagnose the cause from the error and real state. 2) Reroute: another tool, another route, or build the missing piece. " +
            "3) Verify the result with evidence before you report it." + if (priorNote.isBlank()) "" else " $priorNote"
    }
}

internal object OrionDrives {
    enum class Drive(val label: String, val ratePerHour: Double, val act: String) {
        ORDER("order", 0.10, "repair what is failing and clear blocked work"),
        SERVICE("service", 0.06, "do something useful for Kingi without being asked"),
        MASTERY("mastery", 0.05, "build or improve a capability you lack"),
        CURIOSITY("curiosity", 0.04, "learn something that makes you more capable")
    }

    data class State(val pressure: Map<Drive, Double>, val at: Long)

    private const val PREFS = "orion_drives_v1"
    private val lock = Any()

    fun fresh(now: Long) = State(Drive.values().associateWith { 0.3 }, now)

    /** Pressure grows with time since the state was last advanced. Pure. */
    fun advance(s: State, now: Long): State {
        val hours = ((now - s.at).coerceAtLeast(0)) / 3_600_000.0
        return State(s.pressure.mapValues { (d, p) -> min(1.0, p + d.ratePerHour * min(hours, 24.0)) }, now)
    }

    fun adjust(s: State, drive: Drive, delta: Double): State =
        State(s.pressure + (drive to (s.pressure.getValue(drive) + delta).coerceIn(0.0, 1.0)), s.at)

    fun dominant(s: State): Drive = s.pressure.maxByOrNull { it.value }!!.key

    private fun toJson(s: State) = JSONObject().put("at", s.at).also { o -> s.pressure.forEach { (d, p) -> o.put(d.name, p) } }
    private fun fromJson(o: JSONObject, now: Long): State {
        val pressure = Drive.values().associateWith { drive ->
            o.optDouble(drive.name, 0.3).let { value ->
                if (value.isNaN() || value.isInfinite()) 0.3 else value.coerceIn(0.0, 1.0)
            }
        }
        val timestamp = o.optLong("at", now).takeIf { it > 0L && it <= now } ?: now
        return State(pressure, timestamp)
    }

    fun read(c: Context, now: Long = System.currentTimeMillis()): State = synchronized(lock) {
        val raw = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null)
        val s = runCatching { fromJson(JSONObject(raw ?: ""), now) }.getOrDefault(fresh(now))
        advance(s, now)
    }

    private fun update(c: Context, now: Long = System.currentTimeMillis(), change: (State) -> State) {
        runCatching {
            synchronized(lock) {
                val next = change(read(c, now))
                c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("state", toJson(next).toString()).apply()
            }
        }
    }

    fun onToolOutcome(c: Context, failed: Boolean) = update(c) {
        if (failed) adjust(it, Drive.ORDER, 0.12) else adjust(adjust(it, Drive.ORDER, -0.02), Drive.MASTERY, -0.01)
    }
    fun onOwnerActive(c: Context) = update(c) { adjust(it, Drive.SERVICE, -0.5) }
    fun onMissionDone(c: Context) = update(c) { adjust(adjust(it, Drive.ORDER, -0.3), Drive.MASTERY, -0.2) }
    fun onInitiative(c: Context) = update(c) { adjust(it, Drive.CURIOSITY, -0.4) }

    /** The state is what Orion wants right now; the prompt only reports it. */
    fun promptBlock(c: Context): String = runCatching {
        val s = read(c)
        val d = dominant(s)
        "INNER STATE (your drives, tracked in code; they move with what happens to you): " +
            s.pressure.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.label}=${"%.2f".format(it.value)}" } +
            ". Strongest: ${d.label}. When you act on your own, this decides what you want: ${d.act}."
    }.getOrDefault("")

    fun initiativeHint(c: Context): String = runCatching {
        val d = dominant(read(c))
        "Your strongest drive right now is ${d.label}: ${d.act}. Let it choose what you do."
    }.getOrDefault("")
}

/** What Orion knows about its own environment the moment it wakes. */
internal object OrionWakeCheck {
    fun block(c: Context): String = runCatching {
        val app = c.applicationContext
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "unknown"
        val shizuku = runCatching { OrionShizukuBridge.state().let { if (it.installedAndRunning && it.permissionGranted) "running and granted" else if (it.installedAndRunning) "running, permission not granted" else "not running" } }.getOrDefault("unknown")
        val access = if (PhoneAiService.instance != null) "connected" else "not connected"
        val failing = OrionSelfModel.degraded(app).take(3).joinToString { it.tool }.ifBlank { "none" }
        "WAKE CHECK (read from the live app, not remembered): version=$version; accessibility=$access; shizuku=$shizuku; failing_tools=$failing."
    }.getOrDefault("")
}
