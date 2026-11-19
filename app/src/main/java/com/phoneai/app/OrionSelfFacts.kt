package com.phoneai.app

import android.content.Context

/**
 * What Orion can truthfully say about its own build, computed from the running code, not remembered.
 *
 * Self-analysis kept going wrong the same way: it read GitHub `main`, or its own earlier summaries, and reported old
 * limits as current (a regex gate that no longer chooses the model, a "16x" context gap that never existed, a router
 * that was dead code). These facts are read from the live constants and settings, are stamped with the installed
 * version, and the persona tells Orion to start any self-audit here and to say when it is reading something other
 * than the installed build.
 */
internal object OrionSelfFacts {
    fun build(c: Context): String = runCatching {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        "${info.versionName ?: "?"} (code ${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
    }.getOrDefault("unknown")

    /** Pure so it can be tested: turns measured values into the fact lines. */
    fun render(
        build: String, contextBudgetTokens: Int, lessonCap: Int, speechCapChars: Int,
        streaming: List<String>, notStreaming: List<String>, configured: Int, speedLine: String
    ): String = buildString {
        appendLine("SELF-FACTS (computed from the installed build $build; they outrank anything remembered or read on GitHub main, which can be newer or older than this phone)")
        appendLine("- Model choice: every turn runs on the one tool-capable model. Request wording never selects a weaker model; regex rules only choose fast paths and safety confirmations.")
        appendLine("- Context: budget $contextBudgetTokens tokens per turn. Core sections (identity, objective, memory, envelope) always ride along; other large sections are deferred unless the request relates to them, and brain {action:\"context\"} reads any deferred one.")
        appendLine("- Learning: lessons are capped at $lessonCap; verified routes become reflexes only when they match strongly or repeated, and never when a tool in them is degraded.")
        appendLine("- Voice: replies are spoken as a concise cut of at most $speechCapChars characters; the full answer stays in chat. Providers that stream: ${streaming.joinToString().ifBlank { "none" }}. Providers read whole (speech starts after the reply arrives): ${notStreaming.joinToString().ifBlank { "none" }}.")
        appendLine("- Providers with a key: $configured.")
        appendLine("- $speedLine")
        append("Before claiming a limitation, check it against these facts and the tools; if you cannot verify it on this build, say it is unverified instead of stating it.")
    }

    fun block(c: Context): String = runCatching {
        val all = Providers.all
        val speed = OrionSpeedTrace.turns().let { t ->
            if (t.isEmpty()) "Speed: not measured yet in this process."
            else "Speed: whole-turn median ${OrionSpeedTrace.percentile(t.map { it.totalMs }, 0.5) / 1000.0}s over ${t.size} turns; brain {action:\"speed\"} has the breakdown."
        }
        render(build(c), OrionCentralBrain.contextBudgetTokens(), ExperiencePolicy.MAX, OrionSpeechStreamer().maxSpokenCharsValue(),
            all.filter { Nim.supportsStreaming(it) }.map { it.label }, all.filter { !Nim.supportsStreaming(it) }.map { it.label },
            all.count { Prefs.hasKey(c, it.id) || it.noKey }, speed)
    }.getOrDefault("")
}
