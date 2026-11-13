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

/** User-facing copy for execution events. Internal checkpoint keys and correlation IDs stay out of chat. */
internal object OrionActivityPresentation {
    private val internalTrace = Regex("(?i)(work=|checkpoint=|attempt=|key=notification:|RUNNING=|DONE=|task(session|id)[-:=])")

    fun clean(raw: String): String? {
        val value = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !internalTrace.containsMatchIn(it) }
            .filterNot { it.startsWith("Task session-", ignoreCase = true) }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(220)
            .trim()
        return value.takeIf { it.isNotBlank() }
    }

    fun title(event: OrionActivityEvent): String =
        clean(event.title) ?: when {
            event.title.contains("notification", true) || event.summary.contains("notification", true) -> "System notification"
            event.kind == OrionActivityKind.TOOL -> "Phone activity"
            else -> when (event.kind) {
                OrionActivityKind.THINKING -> "Thinking"
                OrionActivityKind.RESEARCH -> "Research"
                OrionActivityKind.CODING -> "Coding"
                OrionActivityKind.TOOL -> "Phone activity"
                OrionActivityKind.DELEGATION -> "Specialist work"
                OrionActivityKind.APPROVAL -> "Approval required"
                OrionActivityKind.ERROR -> "Action failed"
                OrionActivityKind.COMPLETION -> "Completed"
                OrionActivityKind.INTERRUPTED -> "Interrupted"
                OrionActivityKind.CANCELLED -> "Cancelled"
            }
        }

    fun summary(event: OrionActivityEvent): String = clean(event.summary) ?: when (event.status) {
        OrionActivityStatus.ACTIVE -> "Orion is working on this step."
        OrionActivityStatus.WAITING -> "Waiting for your decision."
        OrionActivityStatus.SUCCEEDED -> "This step completed."
        OrionActivityStatus.FAILED -> "This step failed. Open task details for the error."
        OrionActivityStatus.CANCELLED -> "This step was cancelled."
        OrionActivityStatus.INTERRUPTED -> "This step was interrupted."
    }

    fun evidence(items: List<String>): List<String> = items.mapNotNull(::clean).takeLast(6)
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
        /** Convert persisted lifecycle records into the exact event model used by live chat. */
        fun fromPersisted(events: List<OrionConversationRepository.ChatEvent>): List<OrionActivityEvent> =
            events.map { event ->
                val type = event.type.lowercase()
                val status = event.status.lowercase()
                val kind = when {
                    type.contains("approval") -> OrionActivityKind.APPROVAL
                    type.contains("cancel") || status in setOf("cancelled", "canceled") -> OrionActivityKind.CANCELLED
                    type.contains("interrupt") || status == "interrupted" -> OrionActivityKind.INTERRUPTED
                    type.contains("error") || status in setOf("failed", "error", "denied", "rejected") -> OrionActivityKind.ERROR
                    type.contains("tool") || type.contains("terminal") || type.contains("python") || type.contains("file") -> OrionActivityKind.TOOL
                    type.contains("browser") || type.contains("research") -> OrionActivityKind.RESEARCH
                    type.contains("agent") || type.contains("specialist") || type.contains("delegate") -> OrionActivityKind.DELEGATION
                    type.contains("complete") || status in setOf("completed", "success", "succeeded", "result", "approved", "accepted") -> OrionActivityKind.COMPLETION
                    else -> OrionActivityKind.THINKING
                }
                val eventStatus = when {
                    status in setOf("pending", "waiting", "needs_approval", "approval_required", "needs_review") -> OrionActivityStatus.WAITING
                    status in setOf("failed", "error", "denied", "rejected") -> OrionActivityStatus.FAILED
                    status in setOf("cancelled", "canceled") -> OrionActivityStatus.CANCELLED
                    status == "interrupted" -> OrionActivityStatus.INTERRUPTED
                    status in setOf("completed", "success", "succeeded", "result", "approved", "accepted") -> OrionActivityStatus.SUCCEEDED
                    else -> OrionActivityStatus.ACTIVE
                }
                OrionActivityEvent(
                    eventId = event.id,
                    taskId = taskKey(event.traceId, event.messageId, event.sessionId),
                    kind = kind,
                    status = eventStatus,
                    title = event.summary.lineSequence().firstOrNull()?.take(120)?.ifBlank { event.type } ?: event.type,
                    summary = event.summary.take(500),
                    timestampMs = event.createdAt.coerceAtLeast(0L),
                    evidence = listOf(
                        (when (eventStatus) {
                            OrionActivityStatus.ACTIVE -> "RUNNING"
                            OrionActivityStatus.WAITING -> "WAITING"
                            OrionActivityStatus.SUCCEEDED -> "DONE"
                            OrionActivityStatus.FAILED -> "FAILED"
                            OrionActivityStatus.CANCELLED -> "CANCELLED"
                            OrionActivityStatus.INTERRUPTED -> "INTERRUPTED"
                        }) + " · " + event.summary.take(500)
                    ),
                    typedEvidence = listOfNotNull(OrionActivityEvidence.fromJson(event.evidenceJson)),
                    correlationId = event.correlationId
                )
            }.sortedWith(compareBy<OrionActivityEvent> { it.timestampMs }.thenBy { it.eventId })

        /** Keep objective identity distinct from per-tool correlation IDs and conversation IDs. */
        fun taskKey(traceId: String?, messageId: String?, sessionId: String): String =
            traceId?.takeIf { it.isNotBlank() }?.let { "trace-$it" }
                ?: messageId?.takeIf { it.isNotBlank() }?.let { "message-$it" }
                ?: "session-$sessionId"

        data class ProgressStep(val state: String, val title: String, val detail: String = "")

        /** Keep useful action details visible and distinguish a raw result from verified completion. */
        fun progressSteps(evidence: List<String>): List<ProgressStep> {
            val byTitle = LinkedHashMap<String, ProgressStep>()
            fun stateName(raw: String): String = when (raw.trim().uppercase()) {
                "DONE", "SUCCESS", "SUCCEEDED", "COMPLETE", "COMPLETED" -> "DONE"
                "FAILED", "FAIL", "ERROR" -> "FAILED"
                "WAITING", "APPROVAL" -> "WAITING"
                "VERIFYING", "RESULT", "UNVERIFIED" -> "VERIFYING"
                "CANCELLED", "CANCELED", "STOPPED" -> "CANCELLED"
                "INTERRUPTED", "PAUSED" -> "INTERRUPTED"
                else -> "RUNNING"
            }
            evidence.forEach { line ->
                val cut = line.indexOf(" · ")
                if (cut <= 0) return@forEach
                var shownState = stateName(line.substring(0, cut))
                val rest = line.substring(cut + 3).trim()
                val rawTitle = rest.substringBefore(": ").trim()
                val rawDetail = rest.substringAfter(": ", "").trim()
                val marker = Regex("(?i)tool=([a-z0-9_]+);\\s*outcome=([a-z_-]+)").find("$rawTitle $rawDetail")
                val title: String
                val detail: String
                if (marker != null) {
                    title = marker.groupValues[1].replace('_', ' ').replaceFirstChar { it.uppercase() }
                    val outcome = marker.groupValues[2].lowercase()
                    shownState = when (outcome) {
                        "failed", "error" -> "FAILED"
                        "verified", "pass" -> "DONE"
                        "cancelled", "canceled", "stopped" -> "CANCELLED"
                        else -> "VERIFYING"
                    }
                    detail = when (shownState) {
                        "FAILED" -> "This operation failed. Expand the task or inspect its result for the reported error."
                        "DONE" -> "The result was explicitly verified."
                        "CANCELLED" -> "The operation was cancelled before verification."
                        else -> "The tool returned a result; Orion has not yet verified the requested outcome."
                    }
                } else {
                    title = when {
                        rawTitle.equals("request accepted", true) -> "Request received"
                        rawTitle.startsWith("tool=", true) -> rawTitle.substringAfter("tool=").substringBefore(';').replace('_', ' ').replaceFirstChar { it.uppercase() }
                        else -> rawTitle.removeSuffix(" failed").removeSuffix(" completed").take(80)
                    }
                    detail = rawDetail
                }
                if (title.isNotBlank()) {
                    byTitle.remove(title)
                    byTitle[title] = ProgressStep(shownState, title, detail.take(220))
                }
            }
            return byTitle.values.toList()
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
                line.startsWith("[SHELL:FAILED]") -> {
                    kind = OrionActivityKind.CODING; status = OrionActivityStatus.FAILED
                    title = detail.ifBlank { "Terminal operation failed" }
                }
                line.startsWith("[SHELL:RESULT]") -> {
                    // A shell receipt is not proof that a requested file exists or a test passed.
                    kind = OrionActivityKind.CODING; status = OrionActivityStatus.WAITING
                    title = detail.ifBlank { "Terminal result received" }
                }
                line.startsWith("[TASK:RESULT]") -> {
                    kind = OrionActivityKind.TOOL; status = OrionActivityStatus.WAITING
                    title = detail.substringBefore(" — ").ifBlank { "Action result received" }
                }
                else -> return null
            }
            val stable = "legacy-${taskId}-${timestampMs}-${line.hashCode()}"
            return OrionActivityEvent(stable, taskId, kind, status, title, detail, timestampMs)
        }
    }
}
