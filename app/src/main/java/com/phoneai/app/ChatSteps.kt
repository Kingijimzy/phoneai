package com.phoneai.app

/**
 * Pure logic behind Orion's chat step cards, the step pager strip and the avatar header status.
 * Written from the documented behaviour of the nanoMuse chat (pills with a label and duration, a "n / total" pager, a face that
 * changes with what the agent is doing). No Android types, so every rule here is unit tested.
 *
 * Input is the existing chat note stream: Agent.kt already emits "[TASK:RUNNING]\nRunning <tool>" before a tool runs and a
 * "[TASK|SHELL|BROWSER:RESULT|FAILED]" note after it. Line ids are System.nanoTime() at creation, so the gap between a start
 * note and its result note is the step's duration; ids of restored history are not real timestamps, so tiny gaps show no duration.
 */
internal object StepFormat {
    /** "3s", "2m30s", "2m", "1h12m", "1h"; "…" is appended while the step is still running. Negative values clamp to 0. */
    fun duration(seconds: Long, stillRunning: Boolean): String {
        val safe = seconds.coerceAtLeast(0L)
        val base = when {
            safe < 60L -> "${safe}s"
            safe < 3600L -> {
                val m = safe / 60L
                val s = safe % 60L
                if (s == 0L) "${m}m" else "${m}m${s}s"
            }
            else -> {
                val h = safe / 3600L
                val m = (safe % 3600L) / 60L
                if (m == 0L) "${h}h" else "${h}h${m}m"
            }
        }
        return if (stillRunning) "$base…" else base
    }

    /** A short human label for a tool step. Unknown tools become a sentence ("read_screen" → "Read screen"). */
    fun label(tool: String, detail: String? = null): String {
        val d = detail?.trim().orEmpty()
        return when {
            tool == "shell" && d.isNotEmpty() -> "Run: " + d.take(40) + if (d.length > 40) "…" else ""
            tool == "shell" -> "Run a command"
            (tool.startsWith("browser_") || tool == "open_url") && hostOf(d) != null -> {
                val verb = when (tool) {
                    "browser_open", "browser_navigate", "open_url" -> "Open"
                    "browser_screenshot" -> "Screenshot"
                    else -> "Browse"
                }
                "$verb ${hostOf(d)}"
            }
            else -> tool.trim().replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Step" }
        }
    }

    internal fun hostOf(url: String): String? =
        Regex("^https?://([^/\\s?#]+)").find(url.trim())?.groupValues?.getOrNull(1)?.removePrefix("www.")?.takeIf { it.isNotBlank() }
}

internal enum class StepStatus { RUNNING, SUCCESS, FAILED, CANCELLED }

internal data class StepLine(val id: Long, val kind: String, val text: String)

internal data class ToolStep(
    val key: Long,
    val tool: String,
    val label: String,
    val status: StepStatus,
    val startedAtNs: Long,
    val endedAtNs: Long? = null,
    val resultLineId: Long? = null
) {
    val active: Boolean get() = status == StepStatus.RUNNING

    /** Elapsed or final seconds, or null when the ids are not real timestamps (restored history) or the step was cancelled. */
    fun seconds(nowNs: Long): Long? {
        if (status == StepStatus.CANCELLED) return null
        val end = endedAtNs ?: nowNs
        val diff = end - startedAtNs
        if (startedAtNs <= 0L || diff < MIN_REAL_GAP_NS) return null
        return diff / 1_000_000_000L
    }

    fun durationLabel(nowNs: Long): String? = seconds(nowNs)?.let { StepFormat.duration(it, active) }

    companion object { const val MIN_REAL_GAP_NS = 1_000_000L } // under 1 ms is not a real gap
}

internal object StepTracker {
    private const val RUNNING_PREFIX = "[TASK:RUNNING]"

    /** Groups note lines into steps in order. Only "note" lines are read; everything else is ignored. */
    fun derive(lines: List<StepLine>): List<ToolStep> {
        val steps = ArrayList<ToolStep>()
        for (line in lines) {
            if (line.kind != "note") continue
            val text = line.text
            if (text.startsWith(RUNNING_PREFIX)) {
                val tool = text.lineSequence().drop(1).firstOrNull().orEmpty().removePrefix("Running ").trim()
                if (tool.isEmpty()) continue
                // A new step starting means any earlier unfinished step was interrupted.
                for (i in steps.indices) if (steps[i].active) steps[i] = steps[i].copy(status = StepStatus.CANCELLED)
                steps += ToolStep(line.id, tool, StepFormat.label(tool), StepStatus.RUNNING, line.id)
                continue
            }
            val result = parseResult(text) ?: continue
            val status = if (result.failed) StepStatus.FAILED else StepStatus.SUCCESS
            val openIdx = steps.indexOfLast { it.active && it.tool == result.tool }
            if (openIdx >= 0) {
                steps[openIdx] = steps[openIdx].copy(
                    status = status, endedAtNs = line.id, resultLineId = line.id,
                    label = StepFormat.label(result.tool, result.detail)
                )
            } else {
                steps += ToolStep(line.id, result.tool, StepFormat.label(result.tool, result.detail), status, line.id, line.id, line.id)
            }
        }
        return steps
    }

    private data class Result(val tool: String, val failed: Boolean, val detail: String?)

    private fun parseResult(text: String): Result? {
        val header = text.lineSequence().firstOrNull().orEmpty()
        val failed = header.endsWith(":FAILED]")
        val finished = failed || header.endsWith(":RESULT]")
        if (!finished) return null
        return when {
            header.startsWith("[SHELL:") ->
                Result("shell", failed, text.lineSequence().drop(1).firstOrNull()?.removePrefix("$ ")?.trim())
            header.startsWith("[BROWSER:") -> {
                val tool = Regex("(?m)^TOOL: (\\S+)").find(text)?.groupValues?.getOrNull(1) ?: "browser"
                val url = Regex("(?m)^URL: (\\S+)").find(text)?.groupValues?.getOrNull(1)
                Result(tool, failed, url)
            }
            header.startsWith("[TASK:") -> {
                val second = text.lineSequence().drop(1).firstOrNull().orEmpty()
                val tool = second.substringBefore(" — ").trim()
                if (tool.isEmpty()) null else Result(tool, failed, null)
            }
            else -> null
        }
    }
}

/** The "n / total" strip: follows the newest step unless the user is looking at one that is still running. */
internal object StepPager {
    fun follow(currentIndex: Int, steps: List<ToolStep>): Int {
        if (steps.isEmpty()) return 0
        val cur = steps.getOrNull(currentIndex)
        return if (cur != null && cur.active) currentIndex else steps.lastIndex
    }

    fun label(index: Int, total: Int): String? = if (total > 1) "${index.coerceIn(0, total - 1) + 1} / $total" else null
    fun showArrows(total: Int): Boolean = total > 1
    fun previous(index: Int): Int = (index - 1).coerceAtLeast(0)
    fun next(index: Int, total: Int): Int = (index + 1).coerceAtMost((total - 1).coerceAtLeast(0))
}

/** The face and the one-line status under the name, derived from what the agent is doing right now. */
internal object ChatMood {
    enum class Mood { IDLE, WORKING, WAITING, HAPPY, ERROR }

    const val HAPPY_MS = 3_000L
    const val ERROR_MS = 4_000L

    /** Waiting beats working; a recent failure beats a recent finish; both fade after a few seconds. */
    fun moodFor(running: Boolean, waitingForUser: Boolean, msSinceFinished: Long?, msSinceFailure: Long?): Mood = when {
        waitingForUser -> Mood.WAITING
        running -> Mood.WORKING
        msSinceFailure != null && msSinceFailure in 0 until ERROR_MS -> Mood.ERROR
        msSinceFinished != null && msSinceFinished in 0 until HAPPY_MS -> Mood.HAPPY
        else -> Mood.IDLE
    }

    fun statusLine(mood: Mood, currentStepLabel: String?, thinking: Boolean): String = when {
        mood == Mood.WAITING -> "Waiting for you"
        thinking && currentStepLabel == null -> "Thinking"
        mood == Mood.WORKING -> currentStepLabel ?: "Working"
        mood == Mood.ERROR -> "Something went wrong"
        mood == Mood.HAPPY -> "Done"
        else -> "Ready"
    }
}
