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

    companion object {
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
