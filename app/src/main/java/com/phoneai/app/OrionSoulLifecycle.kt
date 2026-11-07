package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Soul Engine lifecycle state machine.
 *
 * The Soul Engine exposes genuine runtime states — not decorative animation:
 *   Stopped   — Orion Core is off; no planning, watching, or acting.
 *   Starting  — Core just enabled; registries and packs are being discovered.
 *   Monitoring — Watcher tick is alive; standing orders and Chronos may run.
 *   Thinking  — A user request is being understood, planned, and capability-checked.
 *   Acting    — A registered tool is executing; results are being inspected.
 *   Recovering — A prior step failed or was interrupted; checkpoint reconciliation is in progress.
 *   Paused    — Owner explicitly paused; no automatic action until resumed.
 *
 * States are persisted in SharedPreferences and reported by the watcher and Central Brain.
 * A state transition is only recorded through this component; no other class mutates it.
 */
internal object OrionSoulLifecycle {
    enum class State {
        STOPPED, STARTING, MONITORING, THINKING, ACTING, RECOVERING, PAUSED
    }

    private const val PREFS = "orion_soul_lifecycle_v1"
    private const val KEY_STATE = "state"
    private const val KEY_ENTERED_AT = "entered_at"
    private const val KEY_LAST_ACTION = "last_action"

    private val lock = Any()

    /** Current lifecycle state. Defaults to STOPPED until the owner enables Orion Core. */
    fun state(context: Context): State = synchronized(lock) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STATE, "STOPPED")
        State.valueOf(raw.takeIf { it.isNotBlank() } ?: "STOPPED")
    }

    /** Enter a new lifecycle state, recording when and what triggered it. Returns the previous state. */
    fun enter(context: Context, next: State, action: String = ""): State = synchronized(lock) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = state(app)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_STATE, next.name)
            .putLong(KEY_ENTERED_AT, now)
            .putString(KEY_LAST_ACTION, action.take(200))
            .apply()
        AgentPolicy.log(app, "soul_lifecycle", next.name.lowercase(), null,
            "transition $previous -> $next" + (if (action.isNotBlank()) " ($action)" else ""))
        previous
    }

    /** Human-readable label for the current state. */
    fun label(context: Context): String = when (state(context)) {
        State.STOPPED -> "Stopped — Orion Core is disabled; no planning, watching, or acting."
        State.STARTING -> "Starting — registries, packs, and permissions are being discovered."
        State.MONITORING -> "Monitoring — watcher tick is alive; standing orders and Chronos may run."
        State.THINKING -> "Thinking — a request is being understood, planned, and capability-checked."
        State.ACTING -> "Acting — a registered tool is executing; results are being inspected."
        State.RECOVERING -> "Recovering — a prior step failed or was interrupted; checkpoint reconciliation is in progress."
        State.PAUSED -> "Paused — owner explicitly paused; no automatic action until resumed."
    }

    /** Milliseconds since the current state was entered, or -1 if unknown. */
    fun millisInState(context: Context): Long {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entered = prefs.getLong(KEY_ENTERED_AT, 0L)
        return if (entered > 0L) System.currentTimeMillis() - entered else -1L
    }

    /** Last recorded action label for the current state. */
    fun lastAction(context: Context): String {
        val app = context.applicationContext
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ACTION, "").orEmpty()
    }

    /** Convenience: record a watcher tick entering MONITORING. */
    fun watcherTick(context: Context): State =
        enter(context, State.MONITORING, "watcher_tick")

    /** Convenience: record that the Central Brain is thinking about a request. */
    fun thinking(context: Context, request: String): State =
        enter(context, State.THINKING, request.take(120))

    /** Convenience: record that a tool is acting. */
    fun acting(context: Context, tool: String): State =
        enter(context, State.ACTING, tool)

    /** Convenience: record recovery from a failed or interrupted step. */
    fun recovering(context: Context, reason: String): State =
        enter(context, State.RECOVERING, reason.take(120))

    /** Convenience: record that the owner paused. */
    fun paused(context: Context): State =
        enter(context, State.PAUSED, "owner_pause")

    /** Convenience: record that Orion Core stopped. */
    fun stopped(context: Context): State =
        enter(context, State.STOPPED, "core_stopped")

    /** Convenience: record that Orion Core is starting. */
    fun starting(context: Context): State =
        enter(context, State.STARTING, "core_starting")

    /** Snapshot as JSON for diagnostic/observability use. */
    fun snapshotJson(context: Context): String {
        val s = state(context)
        return JSONObject()
            .put("state", s.name)
            .put("label", label(context))
            .put("millis_in_state", millisInState(context))
            .put("last_action", lastAction(context))
            .toString()
    }
}
