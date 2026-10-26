package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Minimal durable cursor for routine replay; it stores no action arguments or screen text. */
internal object RoutineReplayCheckpointStore {
    data class Snapshot(
        val routine: String,
        val nextStep: Int,
        val inFlightStep: Int,
        val totalSteps: Int,
        val updatedAt: Long
    )

    private val lock = Any()
    private fun file(context: Context) = File(context.applicationContext.filesDir, "orion_routine_checkpoint.json")

    fun load(context: Context): Snapshot? = synchronized(lock) {
        try {
            val f = file(context)
            if (!f.isFile) return@synchronized null
            val o = JSONObject(f.readText())
            val name = o.optString("routine").trim()
            val total = o.optInt("totalSteps", -1)
            val next = o.optInt("nextStep", -1)
            val flight = o.optInt("inFlightStep", -1)
            if (!RoutineReplayCheckpointPolicy.isValid(name, total, next, flight)) return@synchronized null
            Snapshot(name, next, flight, total, o.optLong("updatedAt", 0L))
        } catch (_: Exception) { null }
    }

    fun begin(context: Context, routine: String, totalSteps: Int): Boolean = synchronized(lock) {
        if (!RoutineReplayCheckpointPolicy.canBegin(load(context) != null, routine, totalSteps)) return@synchronized false
        writeLocked(context, Snapshot(routine, 0, -1, totalSteps, System.currentTimeMillis()))
    }

    fun markInFlight(context: Context, routine: String, totalSteps: Int, index: Int): Boolean = synchronized(lock) {
        val old = load(context) ?: return@synchronized false
        if (!RoutineReplayCheckpointPolicy.canMarkInFlight(old, routine, totalSteps, index)) return@synchronized false
        writeLocked(context, old.copy(inFlightStep = index, updatedAt = System.currentTimeMillis()))
    }

    fun markCompleted(context: Context, routine: String, index: Int): Boolean = synchronized(lock) {
        val old = load(context) ?: return@synchronized false
        if (!RoutineReplayCheckpointPolicy.canMarkCompleted(old, routine, index)) return@synchronized false
        writeLocked(context, old.copy(nextStep = index + 1, inFlightStep = -1, updatedAt = System.currentTimeMillis()))
    }

    fun pauseAt(context: Context, routine: String, index: Int): Boolean = synchronized(lock) {
        val old = load(context) ?: return@synchronized false
        if (!RoutineReplayCheckpointPolicy.canPause(old, routine, index)) return@synchronized false
        writeLocked(context, old.copy(nextStep = index, inFlightStep = -1, updatedAt = System.currentTimeMillis()))
    }

    fun clear(context: Context): Boolean = synchronized(lock) {
        try { !file(context).exists() || file(context).delete() } catch (_: Exception) { false }
    }

    private fun writeLocked(context: Context, s: Snapshot): Boolean {
        return try {
            val target = file(context)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(JSONObject()
                .put("routine", s.routine)
                .put("nextStep", s.nextStep)
                .put("inFlightStep", s.inFlightStep)
                .put("totalSteps", s.totalSteps)
                .put("updatedAt", s.updatedAt).toString())
            if (tmp.renameTo(target)) true else {
                target.writeText(tmp.readText()); tmp.delete(); true
            }
        } catch (_: Exception) { false }
    }
}
