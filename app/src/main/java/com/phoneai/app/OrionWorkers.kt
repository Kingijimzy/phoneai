package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Autonomous workers: each is a complete Orion (own Agent, every tool, same policy) running a goal in the background.
 * The central brain spawns them, keeps working, and gets the report when they finish (also sent to Telegram).
 * Unlike the read-only specialists, a worker can build, run, browse and post; it just cannot ask the owner anything.
 */
internal object OrionWorkers {
    const val MAX_PARALLEL = 3
    private const val PREFS = "orion_workers_v1"
    private const val KEEP = 30

    enum class State { RUNNING, DONE, FAILED, CANCELLED }

    data class Job(
        val id: String, val label: String, val objective: String, val state: State,
        val startedAt: Long, val endedAt: Long = 0L, val result: String = "", val steps: Int = 0
    )

    private val live = ConcurrentHashMap<String, Agent>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private class WorkerUi(val id: String) : AgentUi {
        @Volatile var last = ""
        override fun say(text: String) { if (text.isNotBlank()) last = text }
        override fun status(text: String) {}
        override fun note(text: String) { jobs[id]?.let { jobs[id] = it.copy(steps = it.steps + 1) } }
        override fun phase(p: String) {}
        // A worker never blocks on the owner: anything that needs a yes/no is declined and reported.
        override fun ask(question: String): Boolean = false
    }

    fun prompt(label: String, objective: String): String =
        "[ORION WORKER \"$label\" — spawned by Orion's central brain, not typed by Kingi] Goal: $objective\n" +
            "You are a full autonomous Orion with every tool. Plan it, do it end to end, verify the result with evidence, and fix what breaks. " +
            "Do not ask questions; decide. If something truly needs the owner (deleting or wiping data, or a trade approval), skip that part and say so. " +
            "Your final reply is the report: what you did, what you found or built, where the files are, and what is left."

    fun runningCount(): Int = jobs.values.count { it.state == State.RUNNING }

    fun spawn(svc: PhoneAiService, objective: String, label: String = ""): String {
        val goal = objective.trim()
        if (goal.length < 8) return "error: spawn_worker needs a concrete objective."
        if (runningCount() >= MAX_PARALLEL) return "error: $MAX_PARALLEL workers are already running; wait for one (worker_list) or cancel one (worker_cancel)."
        val id = "w" + UUID.randomUUID().toString().take(6)
        val name = label.trim().ifBlank { goal.take(40) }
        jobs[id] = Job(id, name, goal, State.RUNNING, System.currentTimeMillis())
        val ui = WorkerUi(id)
        val agent = Agent(svc, ui, background = true)
        live[id] = agent
        Thread({
            var failure = ""
            try { agent.run(prompt(name, goal)) } catch (e: Throwable) { failure = e.message ?: e.javaClass.simpleName }
            val cancelled = agent.stopped
            live.remove(id)
            val prev = jobs[id] ?: return@Thread
            val state = when { cancelled -> State.CANCELLED; failure.isNotEmpty() -> State.FAILED; else -> State.DONE }
            val result = (if (failure.isNotEmpty()) "error: $failure\n" else "") + ui.last
            jobs[id] = prev.copy(state = state, endedAt = System.currentTimeMillis(), result = result.take(6000))
            persist(svc)
            runCatching { OrionNodes.recordNode("chat", state == State.FAILED) }
            OrionReports.report(svc, "worker $name (${state.name.lowercase()})", result)
        }, "orion-worker-$id").apply { isDaemon = true }.start()
        return "Worker $id \"$name\" started in the background (running ${runningCount()}/$MAX_PARALLEL). Keep going; read it with worker_result {id:\"$id\"}. It reports to Telegram when done."
    }

    fun list(c: Context): String {
        load(c)
        val all = jobs.values.sortedByDescending { it.startedAt }
        if (all.isEmpty()) return "No workers yet. spawn_worker {objective, label?} starts one."
        return all.take(15).joinToString("\n") {
            "- ${it.id} [${it.state.name.lowercase()}] ${it.label} · ${it.steps} steps · " + seconds(it) + "s"
        }
    }

    fun result(c: Context, id: String): String {
        load(c)
        val j = jobs[id.trim()] ?: return "error: no worker '$id'. worker_list shows them."
        return when (j.state) {
            State.RUNNING -> "Worker ${j.id} \"${j.label}\" is still running (${j.steps} steps, ${seconds(j)}s)."
            else -> "Worker ${j.id} ${j.state.name.lowercase()} after ${seconds(j)}s:\n" + j.result.ifBlank { "(no report)" }
        }
    }

    fun cancel(id: String): String {
        val a = live[id.trim()] ?: return "error: no running worker '$id'."
        a.stop()
        return "Stopping worker $id."
    }

    private fun seconds(j: Job) = (((if (j.endedAt == 0L) System.currentTimeMillis() else j.endedAt) - j.startedAt) / 1000).coerceAtLeast(0)

    private fun persist(c: Context) {
        runCatching {
            val arr = JSONArray()
            jobs.values.sortedByDescending { it.startedAt }.take(KEEP).forEach {
                arr.put(JSONObject().put("id", it.id).put("label", it.label).put("objective", it.objective).put("state", it.state.name)
                    .put("startedAt", it.startedAt).put("endedAt", it.endedAt).put("result", it.result).put("steps", it.steps))
            }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("jobs", arr.toString()).apply()
        }
    }

    /** Jobs from before a restart come back as finished history; a worker that was running did not survive the restart. */
    private fun load(c: Context) {
        if (jobs.isNotEmpty()) return
        runCatching {
            val arr = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("jobs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val st = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.FAILED).let { if (it == State.RUNNING) State.FAILED else it }
                jobs[o.getString("id")] = Job(o.getString("id"), o.optString("label"), o.optString("objective"), st,
                    o.optLong("startedAt"), o.optLong("endedAt"), o.optString("result").ifBlank { if (st == State.FAILED) "interrupted by a restart" else "" }, o.optInt("steps"))
            }
        }
    }
}
