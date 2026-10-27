package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * A task plan Orion shows as a progress card and keeps across restarts.
 * Pure model: no Android types, so the transition rules are unit tested.
 *
 * Rules: at most one step runs at a time; a step must be started before it can finish or fail;
 * finished steps are never reopened; the next step is the first PENDING one in order.
 */
internal data class TaskPlan(val goal: String, val steps: List<Step>) {
    enum class Status { PENDING, RUNNING, DONE, FAILED, SKIPPED }
    data class Step(val id: String, val title: String, val status: Status = Status.PENDING, val note: String = "")

    val isFinished: Boolean get() = steps.isNotEmpty() && steps.all { it.status == Status.DONE || it.status == Status.SKIPPED }
    val running: Step? get() = steps.firstOrNull { it.status == Status.RUNNING }
    val nextPending: Step? get() = steps.firstOrNull { it.status == Status.PENDING }

    fun progressLabel(): String {
        val done = steps.count { it.status == Status.DONE || it.status == Status.SKIPPED }
        val failed = steps.count { it.status == Status.FAILED }
        val tail = if (failed > 0) ", $failed failed" else ""
        return "$done of ${steps.size} steps done$tail"
    }

    /** Starts the next pending step. Refused while another step is running or when nothing is pending. */
    fun start(id: String): TaskPlan? {
        if (running != null) return null
        val step = steps.firstOrNull { it.id == id } ?: return null
        if (step.status != Status.PENDING) return null
        return replace(id) { it.copy(status = Status.RUNNING) }
    }

    fun finish(id: String, note: String = ""): TaskPlan? = conclude(id, Status.DONE, note)
    fun fail(id: String, note: String): TaskPlan? = conclude(id, Status.FAILED, note)

    /** Skipping is allowed only for a step that has not started yet. */
    fun skip(id: String, note: String = ""): TaskPlan? {
        val step = steps.firstOrNull { it.id == id } ?: return null
        if (step.status != Status.PENDING) return null
        return replace(id) { it.copy(status = Status.SKIPPED, note = note) }
    }

    private fun conclude(id: String, to: Status, note: String): TaskPlan? {
        val step = steps.firstOrNull { it.id == id } ?: return null
        if (step.status != Status.RUNNING) return null
        return replace(id) { it.copy(status = to, note = note) }
    }

    private fun replace(id: String, change: (Step) -> Step): TaskPlan =
        copy(steps = steps.map { if (it.id == id) change(it) else it })

    /** Card text for chat: one line per step with a status mark. */
    fun card(): String = buildString {
        append("Plan: ").append(goal).append('\n')
        append(progressLabel()).append('\n')
        steps.forEach { s ->
            val mark = when (s.status) {
                Status.DONE -> "[x]"; Status.RUNNING -> "[>]"; Status.FAILED -> "[!]"
                Status.SKIPPED -> "[-]"; Status.PENDING -> "[ ]"
            }
            append(mark).append(' ').append(s.title)
            if (s.note.isNotBlank()) append(" — ").append(s.note)
            append('\n')
        }
    }.trimEnd()

    fun toJson(): String {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("status", s.status.name).put("note", s.note))
        }
        return JSONObject().put("goal", goal).put("steps", arr).toString()
    }

    companion object {
        const val MAX_STEPS = 12

        /** A new plan. Blank or duplicate ids, or more than [MAX_STEPS] steps, are refused. */
        fun create(goal: String, titles: List<String>): TaskPlan? {
            if (goal.isBlank() || titles.isEmpty() || titles.size > MAX_STEPS) return null
            if (titles.any { it.isBlank() }) return null
            val steps = titles.mapIndexed { i, t -> Step(id = "s${i + 1}", title = t.trim()) }
            return TaskPlan(goal.trim(), steps)
        }

        /** Returns null for missing or malformed data instead of throwing, so a bad file never crashes the app. */
        fun fromJson(text: String): TaskPlan? = try {
            val o = JSONObject(text)
            val arr = o.getJSONArray("steps")
            val steps = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                Step(
                    id = s.getString("id"),
                    title = s.getString("title"),
                    status = Status.valueOf(s.getString("status")),
                    note = s.optString("note", "")
                )
            }
            if (steps.isEmpty() || steps.size > MAX_STEPS || steps.map { it.id }.toSet().size != steps.size) null
            else TaskPlan(o.getString("goal"), steps)
        } catch (_: Exception) { null }
    }
}
