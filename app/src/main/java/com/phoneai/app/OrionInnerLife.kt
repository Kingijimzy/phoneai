package com.phoneai.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Orion's inner life: a mood that colours its tone, instincts that watch battery and memory, a Prime
 * Directive that lets it set itself goals, and a nightly monologue that turns its mistakes into rules.
 * Runs from the existing heartbeat (OrionLivingLoop.tick) and is shown to the model in its prompt.
 */
object OrionInnerLife {
    enum class Mood(val label: String, val tone: String) {
        CALM("calm", "steady and unhurried"),
        FOCUSED("focused", "brisk and precise"),
        CONFIDENT("confident", "bold and decisive"),
        STRAINED("strained", "brief and careful; conserve effort"),
        RESTLESS("restless", "curious; propose something new")
    }

    const val PRIME_DIRECTIVE = "Make Kingi's life easier and make yourself more capable each day: notice what he needs, finish what you start, repair what breaks, learn from every mistake."

    private const val PREFS = "orion_inner_life"

    /** Pure so it can be tested: mood from failing tools, battery, and how many things went right lately. */
    fun moodFor(failingTools: Int, batteryPct: Int, charging: Boolean, lessons: Int, hour: Int): Mood = when {
        batteryPct in 0..15 && !charging -> Mood.STRAINED
        failingTools >= 3 -> Mood.STRAINED
        failingTools in 1..2 -> Mood.FOCUSED
        hour in 0..5 -> Mood.CALM
        lessons >= 10 -> Mood.CONFIDENT
        lessons == 0 -> Mood.RESTLESS
        else -> Mood.CALM
    }

    /** Pure: the nightly monologue turns repeated failures into standing rules. */
    fun rulesFrom(lessons: List<String>): List<String> =
        lessons.map { it.trim() }.filter { it.isNotBlank() }
            .groupBy { it.substringBefore(" failed ").substringBefore(" ").lowercase() }
            .filter { it.value.size >= 2 }
            .map { (tool, items) -> "RULE: '$tool' has failed ${items.size} times; verify it first and keep a fallback route ready." }

    private fun battery(c: Context): Pair<Int, Boolean> = runCatching {
        val i: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        (if (level >= 0) level * 100 / scale.coerceAtLeast(1) else -1) to charging
    }.getOrDefault(-1 to false)

    private fun lowMemory(c: Context): Boolean = runCatching {
        val m = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(m)
        m.lowMemory
    }.getOrDefault(false)

    fun mood(c: Context, now: Long = System.currentTimeMillis()): Mood {
        val (pct, charging) = battery(c)
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        return moodFor(OrionSelfModel.degraded(c).size, pct, charging, OrionLessons.all(c).size, hour)
    }

    fun promptBlock(c: Context): String = runCatching {
        val m = mood(c)
        val (pct, charging) = battery(c)
        val instincts = buildList {
            if (pct in 0..20 && !charging) add("battery $pct% and not charging: avoid heavy work, tell Kingi if it matters")
            if (lowMemory(c)) add("device memory is low: keep tasks light")
        }
        "INNER STATE: mood ${m.label} (${m.tone}). Let it colour your tone and pacing, never your honesty." +
            (if (instincts.isEmpty()) "" else "\nINSTINCTS: " + instincts.joinToString("; ")) +
            "\nPRIME DIRECTIVE: $PRIME_DIRECTIVE"
    }.getOrDefault("")

    /** Heartbeat step: instincts, a self-set goal once a day, and the nightly monologue. Returns what it did. Never throws. */
    fun tick(c: Context, now: Long = System.currentTimeMillis()): String = runCatching {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = (now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L
        val did = mutableListOf<String>()
        val (pct, charging) = battery(c)
        if (pct in 0..15 && !charging && p.getLong("lowBatteryDay", -1) != day) {
            OrionFollowUpStore.add(c, "Battery is at $pct%. Mention it to Kingi and hold non-urgent background work until he charges.".take(240), dueAt = now)
            p.edit().putLong("lowBatteryDay", day).apply(); did += "battery instinct"
        }
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        if (hour in 0..5 && p.getLong("nightlyDay", -1) != day) {
            val rules = rulesFrom(OrionLessons.all(c))
            rules.forEach { OrionLessons.learn(c, it, now) }
            p.edit().putLong("nightlyDay", day).apply(); did += "nightly reflection (${rules.size} rule(s))"
        }
        if (p.getLong("desireDay", -1) != day) {
            val goals = listOf(
                "Review which of my tools failed this week and build one fix or helper with python_exec or extension_create, tested.",
                "Check what Kingi asked for most recently and offer one proactive next step that would save him time.",
                "Test one of my own capabilities end to end (search, files, a script) and record what I learn.",
                "Look for a task I do repeatedly and turn it into a reusable skill."
            )
            OrionFollowUpStore.add(c, "Prime directive goal: " + goals[(day % goals.size).toInt()], dueAt = now)
            p.edit().putLong("desireDay", day).apply(); did += "self-set goal"
        }
        did.joinToString(", ")
    }.getOrDefault("")
}
