package com.phoneai.app

import java.util.LinkedHashMap

/** A truthful, timestamped event emitted by the execution layer and rendered in chat. */
internal enum class OrionActivityKind {
    THINKING, RESEARCH, CODING, TOOL, DELEGATION, APPROVAL, ERROR, COMPLETION, INTERRUPTED, CANCELLED
}

internal enum class OrionActivityStatus { ACTIVE, WAITING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }

internal data class OrionActivityEvent(
    val eventId: String,
    val taskId: String,
    val kind: OrionActivityKind,
    val status: OrionActivityStatus,
    val title: String,
    val summary: String,
    val timestampMs: Long,
    val agentId: String? = null,
    val parentTaskId: String? = null,
    val evidence: List<String> = emptyList(),
    val elapsedMs: Long? = null,
    val typedEvidence: List<OrionActivityEvidence> = emptyList(),
    val correlationId: String? = null
) {
    init {
        require(eventId.isNotBlank())
        require(taskId.isNotBlank())
        require(title.isNotBlank())
        require(timestampMs >= 0L)
    }
}

/**
 * In-memory ordered projection. Persistence belongs to the conversation repository; this class
 * provides deterministic deduplication/replay semantics and never fabricates progress.
 */
internal class OrionActivityTimeline(private val maxEvents: Int = 300) {
    private val events = LinkedHashMap<String, OrionActivityEvent>()

    init { require(maxEvents > 0) }

    @Synchronized fun append(event: OrionActivityEvent): Boolean {
        if (events.containsKey(event.eventId)) return false
        events[event.eventId] = event
        while (events.size > maxEvents) events.remove(events.keys.first())
        return true
    }

    @Synchronized fun replay(incoming: Iterable<OrionActivityEvent>) {
        incoming.sortedWith(compareBy<OrionActivityEvent> { it.timestampMs }.thenBy { it.eventId })
            .forEach(::append)
    }

    @Synchronized fun snapshot(taskId: String? = null): List<OrionActivityEvent> =
        events.values.filter { taskId == null || it.taskId == taskId }
            .sortedWith(compareBy<OrionActivityEvent> { it.timestampMs }.thenBy { it.eventId })

    @Synchronized fun clear() = events.clear()

    /**
     * Chat renders one persistent stream card per task, not one card per event/tool.
     * Each update replaces the same task card while the event summaries remain available
     * as expandable evidence inside it.
     */
    @Synchronized fun compactForChat(): List<OrionActivityEvent> =
        snapshot().groupBy { it.taskId }.values.mapNotNull { taskEvents ->
            val ordered = taskEvents.sortedWith(compareBy<OrionActivityEvent> { it.timestampMs }.thenBy { it.eventId })
            val first = ordered.firstOrNull() ?: return@mapNotNull null
            val latest = ordered.last()
            val history = ordered.map { event ->
                val state = when (event.status) {
                    OrionActivityStatus.ACTIVE -> "RUNNING"
                    OrionActivityStatus.WAITING -> "WAITING"
                    OrionActivityStatus.SUCCEEDED -> "DONE"
                    OrionActivityStatus.FAILED -> "FAILED"
                    OrionActivityStatus.CANCELLED -> "CANCELLED"
                    OrionActivityStatus.INTERRUPTED -> "INTERRUPTED"
                }
                "$state · ${event.title}: ${event.summary}".take(360)
            }.distinct().takeLast(40)
            latest.copy(
                eventId = "task-stream-${latest.taskId}",
                title = first.title.ifBlank { "Task activity" },
                summary = latest.summary,
                evidence = history,
                typedEvidence = ordered.flatMap { it.typedEvidence }.distinct().takeLast(20),
                elapsedMs = (latest.timestampMs - first.timestampMs).coerceAtLeast(0L),
                correlationId = null
            )
        }.sortedBy { it.timestampMs }

    companion object {
        data class ProgressStep(val state: String, val title: String)

        /** Turns the task card's history lines ("RUNNING · title: summary") into one row per step, keeping each step's latest state. */
        fun progressSteps(evidence: List<String>): List<ProgressStep> {
            val byTitle = LinkedHashMap<String, String>()
            evidence.forEach { line ->
                val cut = line.indexOf(" · ")
                if (cut <= 0) return@forEach
                val state = line.substring(0, cut).trim()
                val rest = line.substring(cut + 3)
                val title = rest.substringBefore(": ").trim().take(80)
                if (title.isNotBlank()) { byTitle.remove(title); byTitle[title] = state }
            }
            return byTitle.map { ProgressStep(it.value, it.key) }
        }

        /** Chat keeps only the latest task card visible; the Tasks screen owns full history. */
        fun latestChatCard(events: List<OrionActivityEvent>): OrionActivityEvent? =
            events.maxWithOrNull(compareBy<OrionActivityEvent> { it.timestampMs }.thenBy { it.eventId })

        fun elapsedLabel(startedAtMs: Long, nowMs: Long): String {
            val seconds = ((nowMs - startedAtMs).coerceAtLeast(0L)) / 1000L
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m${seconds % 60}s"
                else -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
            }
        }

        fun fromLegacyNote(taskId: String, note: String, timestampMs: Long): OrionActivityEvent? {
            val line = note.lineSequence().firstOrNull().orEmpty()
            val detail = note.lineSequence().drop(1).firstOrNull().orEmpty().trim()
            val kind: OrionActivityKind
            val status: OrionActivityStatus
            val title: String
            when {
                line.startsWith("[TASK:OUTPUT]") -> {
                    kind = OrionActivityKind.TOOL; status = OrionActivityStatus.ACTIVE
                    title = "Python terminal"
                }
                line.startsWith("[TASK:RUNNING]") -> {
                    kind = OrionActivityKind.TOOL; status = OrionActivityStatus.ACTIVE
                    title = detail.removePrefix("Running ").ifBlank { "Working" }
                }
                line.startsWith("[TASK:APPROVAL]") -> {
                    kind = OrionActivityKind.APPROVAL; status = OrionActivityStatus.WAITING
                    title = "Approval required"
                }
                line.startsWith("[TASK:FAILED]") -> {
                    kind = OrionActivityKind.ERROR; status = OrionActivityStatus.FAILED
                    title = detail.ifBlank { "Action failed" }
                }
                line.startsWith("[TASK:CANCELLED]") -> {
                    kind = OrionActivityKind.CANCELLED; status = OrionActivityStatus.CANCELLED
                    title = "Task cancelled"
                }
                line.startsWith("[TASK:INTERRUPTED]") -> {
                    kind = OrionActivityKind.INTERRUPTED; status = OrionActivityStatus.INTERRUPTED
                    title = "Task interrupted"
                }
                line.startsWith("[TASK:COMPLETE]") || line.startsWith("[TASK:RECORD]") -> {
                    kind = OrionActivityKind.COMPLETION; status = OrionActivityStatus.SUCCEEDED
                    title = detail.ifBlank { "Task completed" }
                }
                line.startsWith("[TASK:PLAN]") -> {
                    kind = OrionActivityKind.THINKING; status = OrionActivityStatus.ACTIVE
                    title = "Planning"
                }
                line.startsWith("[BROWSER:") -> {
                    kind = OrionActivityKind.RESEARCH
                    status = if (line.endsWith(":FAILED]")) OrionActivityStatus.FAILED else OrionActivityStatus.SUCCEEDED
                    title = detail.ifBlank { "Browser research" }
                }
                line.startsWith("[SHELL:") -> {
                    kind = OrionActivityKind.CODING
                    status = if (line.endsWith(":FAILED]")) OrionActivityStatus.FAILED else OrionActivityStatus.SUCCEEDED
                    title = detail.ifBlank { "Terminal operation" }
                }
                else -> return null
            }
            val stable = "legacy-${taskId}-${timestampMs}-${line.hashCode()}"
            return OrionActivityEvent(stable, taskId, kind, status, title, detail, timestampMs)
        }
    }
}
