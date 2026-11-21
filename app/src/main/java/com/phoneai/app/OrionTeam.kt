package com.phoneai.app

import android.content.Context

/** One view of the whole team: built-in and custom specialists, who is working now, and what each can be trusted with. */
internal object OrionTeam {
    fun isCustom(c: Context, id: String): Boolean = AgentProfiles.custom(c).any { it.id == id }

    fun summary(c: Context): String = runCatching {
        val people = AgentProfiles.everyone(c)
        val jobs = OrionMissionControl.active()
        val sb = StringBuilder()
        sb.append("TEAM (").append(people.size).append(" specialists, ").append(jobs.size).append(" active jobs). Pick the specialist whose focus fits the work; custom ones are analysis-only, so you run their tools and check their findings:\n")
        people.forEach { p -> sb.append("- ").append(p.id).append(if (isCustom(c, p.id)) " (custom)" else "").append(": ").append(p.tagline.take(90)).append('\n') }
        jobs.take(5).forEach { j -> sb.append("ACTIVE: ").append(j.request.take(80)).append(" [").append(j.mode).append("]\n") }
        sb.toString().trim()
    }.getOrDefault("TEAM: unavailable")
}
