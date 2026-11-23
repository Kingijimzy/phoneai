package com.phoneai.app

/**
 * One exportable account of what Orion actually did: the recent tool steps with outcomes and timings,
 * and the measured record per tool. Built from real runtime data so a failure can be fixed from evidence.
 */
internal object OrionDiagnostics {
    fun report(
        steps: List<OrionRunLog.Step> = OrionRunLog.steps,
        stats: Map<String, Triple<Int, Int, Long>> = OrionForesight.stats(),
        now: Long = System.currentTimeMillis()
    ): String = buildString {
        append("ORION DIAGNOSTICS\n")
        append("Tool surface per turn: up to ").append(ToolRegistry.FOCUSED_LIMIT).append(" of ").append(ToolRegistry.all.size).append(" registered tools\n\n")
        append("RECENT STEPS (newest last)\n")
        if (steps.isEmpty()) append("- none this session\n")
        steps.takeLast(25).forEach { s ->
            append("- ").append(s.tool).append(" | ").append(s.status.name).append(" | ")
                .append(OrionRunLog.durationLabel(OrionRunLog.durationMs(s, now)))
            if (s.status == OrionRunLog.Status.FAILED) append(" | ").append(s.output.lineSequence().firstOrNull().orEmpty().take(160))
            append('\n')
        }
        append(OrionRunAudit.section(steps, now))
        append("\n").append(OrionEvolution.report(stats)).append("\n")
        append("\nMEASURED RECORD (tool: ok/failed, worst first)\n")
        val rows = stats.filter { it.value.first + it.value.second > 0 }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Triple<Int, Int, Long>>> { it.value.second }.thenBy { it.key })
            .take(25)
        if (rows.isEmpty()) append("- nothing measured yet\n")
        rows.forEach { append("- ").append(it.key).append(": ").append(it.value.first).append('/').append(it.value.second).append('\n') }
    }
}
