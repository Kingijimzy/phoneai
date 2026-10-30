package com.phoneai.app

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns durable task records, specialist delegation and standing-order configuration.
 * Actual task planning/recovery, shared policy and execution verification stay in Agent/runtime.
 */
internal object OrionTasksCapabilityExecutor {
    private val supportedTools = setOf(
        "ask_agent", "create_agent", "task_list", "task_add", "task_update", "task_delete",
        "undo_last", "undo_status", "morning_brief", "standing_order"
    )

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(
        tool: String,
        args: JSONObject,
        service: PhoneAiService,
        tools: Tools,
        stopped: () -> Boolean,
        confirm: (String) -> Boolean
    ): String = when (tool) {
        "ask_agent" -> if (stopped()) "error: task was stopped before specialist delegation."
            else tools.askAgent(args.optString("agent"), args.optString("task"))
        "create_agent" -> if (stopped()) "error: task was stopped before specialist creation."
            else tools.createAgent(args.optString("id"), args.optString("name"), args.optString("tagline"), args.optString("persona"))
        "morning_brief" -> if (stopped()) "error: task was stopped before building the brief."
            else tools.morningBrief()
        "standing_order" -> {
            if (stopped()) "error: task was stopped before changing standing orders."
            else when (args.optString("action").trim().lowercase()) {
                "pause" -> StandingOrders.setEnabled(service, args.optString("kind").trim().lowercase(), false)
                "resume", "enable" -> StandingOrders.setEnabled(service, args.optString("kind").trim().lowercase(), true)
                else -> tools.standingOrder(
                    args.optString("action"), args.optString("kind"), args.optInt("every_minutes", 60)
                )
            }
        }
        "task_list" -> {
            val tasks = TaskStore.all(service).take(30)
            val taskLines = if (tasks.isEmpty()) "No saved tasks yet."
            else tasks.joinToString("\n") { task ->
                val due = task.dueAt?.let {
                    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(it))
                } ?: "no due date"
                "#${task.id} [${task.status}/${task.priority}] ${task.title}" +
                    (if (task.details.isBlank()) "" else " — ${task.details.take(100)}") +
                    " (due: $due)" +
                    (if (task.resultSummary.isBlank()) "" else "\n  Last result: ${task.resultSummary.take(180)}")
            }
            val checkpoint = AgentCheckpointStore.pending(service)
            val recovery = checkpoint?.let { pending ->
                val status = pending.optString("status", "unknown")
                val steps = pending.optJSONArray("steps")?.length() ?: 0
                val inFlightTool = pending.optJSONObject("inFlight")?.optString("tool")?.takeIf { it.isNotBlank() }
                "\nINTERRUPTED AGENT RUN: status=$status; recorded_steps=$steps" +
                    (inFlightTool?.let { "; uncertain_in_flight_tool=$it" } ?: "") +
                    ". Continue only through the normal resume/recovery flow; verify uncertain outcomes and never blindly replay an in-flight action."
            }.orEmpty()
            taskLines + recovery
        }
        "task_add" -> {
            if (stopped()) "error: task was stopped before saving the task."
            else {
                val title = args.optString("title").trim()
                val dueAt = if (args.has("dueAt") && !args.isNull("dueAt"))
                    args.optLong("dueAt").takeIf { it > System.currentTimeMillis() } else null
                val task = TaskStore.add(
                    service, title, args.optString("details", ""),
                    args.optString("priority", "normal"), dueAt
                )
                if (task == null) "error: task was not saved; provide a title and check device storage"
                else "Created task #${task.id}: ${task.title} [${task.priority}]"
            }
        }
        "task_update" -> {
            if (stopped()) "error: task was stopped before updating the saved task."
            else {
                val id = args.optInt("id", -1)
                val dueAt = if (args.has("dueAt") && !args.isNull("dueAt")) args.optLong("dueAt") else null
                val ok = TaskStore.update(
                    service, id,
                    title = if (args.has("title")) args.optString("title") else null,
                    details = if (args.has("details")) args.optString("details") else null,
                    status = if (args.has("status")) args.optString("status") else null,
                    priority = if (args.has("priority")) args.optString("priority") else null,
                    dueAt = dueAt,
                    clearDueAt = args.optBoolean("clearDueAt", false)
                )
                if (ok) "Updated task #$id." else "error: task #$id was not updated; it may not exist or device storage may be unavailable"
            }
        }
        "task_delete" -> {
            val id = args.optInt("id", -1)
            when {
                stopped() -> "error: task was stopped before deletion."
                !confirm("Delete task #$id permanently?") -> "Task deletion cancelled."
                stopped() -> "error: task was stopped after approval; task was not deleted."
                TaskStore.delete(service, id) -> "Deleted task #$id."
                else -> "error: task #$id was not deleted; it may not exist or device storage may be unavailable"
            }
        }
        "undo_status" -> UndoStore.status(service)
        "undo_last" -> if (stopped()) "error: task was stopped before undo."
            else UndoStore.undoLast(service)
        else -> "error: tasks executor does not implement '$tool'."
    }
}
