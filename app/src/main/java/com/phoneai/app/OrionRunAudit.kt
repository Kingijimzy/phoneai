package com.phoneai.app

/**
 * Reads a run (live or exported) and names the failure patterns the owner has actually hit:
 * looping on one failing tool, wandering from GitHub to the browser after a 404, and slow steps.
 * Pure over the step list, so a real exported run can be replayed in a test.
 */
internal object OrionRunAudit {
    private val githubTools = setOf("github_account_read", "github_public")
    private val wanderTools = setOf("live_web_search", "research_web", "fetch_url", "open_url", "browser_navigate")
    const val SLOW_MS = 20_000L

    fun findings(steps: List<OrionRunLog.Step>, now: Long = System.currentTimeMillis()): List<String> {
        val out = mutableListOf<String>()
        // same tool failing three or more times in a row
        var run = 0
        var prev: String? = null
        steps.forEach { s ->
            if (s.status == OrionRunLog.Status.FAILED && s.tool == prev) run++ else run = if (s.status == OrionRunLog.Status.FAILED) 1 else 0
            prev = s.tool
            if (run == 3) out += "LOOP: ${s.tool} failed 3 times in a row; change approach instead of retrying."
        }
        // github failure followed by wandering
        steps.forEachIndexed { i, s ->
            if (s.tool in githubTools && s.status == OrionRunLog.Status.FAILED &&
                listOf("404", "401", "403").any { s.output.contains(it) }) {
                steps.drop(i + 1).take(3).firstOrNull { it.tool in wanderTools }?.let {
                    out += "WANDER: ${it.tool} right after a GitHub ${s.output.lineSequence().first().take(60)}; a credential fact cannot be fixed by browsing."
                }
            }
        }
        steps.filter { it.status != OrionRunLog.Status.RUNNING && OrionRunLog.durationMs(it, now) > SLOW_MS }
            .forEach { out += "SLOW: ${it.tool} took ${OrionRunLog.durationLabel(OrionRunLog.durationMs(it, now))}." }
        return out.distinct()
    }

    fun section(steps: List<OrionRunLog.Step>, now: Long = System.currentTimeMillis()): String {
        val f = findings(steps, now)
        return "\nPROBLEMS FOUND IN THIS RUN\n" + if (f.isEmpty()) "- none\n" else f.joinToString("\n") { "- $it" } + "\n"
    }
}
