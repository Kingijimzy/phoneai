package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Event-driven coordinator: persists bounded work, but never calls providers or executes actions itself. */
internal class OrionRuntime(context: Context, private val clock: () -> Long = { System.currentTimeMillis() }) {
    enum class State { STOPPED, ACTIVE, BUSY, PAUSED, DEGRADED }
    enum class WorkType { LEARN_FROM_RESULT, CAPABILITY_RECHECK, RESTORE_PENDING, SOUL_WATCH }
    data class WorkItem(val id: String, val key: String, val type: WorkType, val priority: Int,
                        val createdAt: Long, val attempts: Int = 0, val notBefore: Long = 0L)
    data class Snapshot(val state: State, val queued: Int, val lastEvent: String, val lastChangedAt: Long)

    private val appContext = context.applicationContext
    private val lock = Any()
    private val file get() = File(appContext.filesDir, "orion_runtime.json")
    private val maxQueue = OrionRuntimePolicy.MAX_QUEUE
    private var state = State.STOPPED
    private var lastEvent = "created"
    private var lastChangedAt = clock()
    private var queue = mutableListOf<WorkItem>()
    private var inflight: WorkItem? = null

    init { synchronized(lock) { loadLocked() } }

    fun start() = synchronized(lock) { state = State.ACTIVE; eventLocked("service_connected"); persistLocked() }
    fun stop() = synchronized(lock) { state = State.STOPPED; eventLocked("service_disconnected"); persistLocked() }
    fun pause(reason: String = "paused") = synchronized(lock) { state = State.PAUSED; eventLocked(reason); persistLocked() }
    fun resume() = synchronized(lock) { state = State.ACTIVE; eventLocked("resumed"); persistLocked() }

    fun enqueue(type: WorkType, key: String, priority: Int = 0): Boolean = synchronized(lock) {
        val safeKey = key.trim().take(100)
        if (safeKey.isBlank() || queue.any { it.type == type && it.key == safeKey }) return@synchronized false
        if (queue.size >= maxQueue) {
            val lowest = queue.indices.minByOrNull { queue[it].priority } ?: return@synchronized false
            if (queue[lowest].priority >= priority) return@synchronized false
            queue.removeAt(lowest)
        }
        queue.add(WorkItem(UUID.randomUUID().toString(), safeKey, type, priority.coerceIn(-10, 10), clock()))
        queue.sortWith(compareByDescending<WorkItem> { it.priority }.thenBy { it.createdAt })
        state = OrionRuntimePolicy.stateAfterEnqueue(state, queue.isNotEmpty())
        eventLocked("work_queued:" + type.name.lowercase())
        persistLocked()
        true
    }

    fun claimNext(): WorkItem? = synchronized(lock) {
        if (state != State.ACTIVE) return@synchronized null
        val index = queue.indexOfFirst { it.notBefore <= clock() }
        if (index < 0) return@synchronized null
        val item = queue.removeAt(index)
        inflight = item
        state = State.BUSY
        eventLocked("work_claimed:" + item.type.name.lowercase())
        persistLocked()
        item
    }

    fun complete(item: WorkItem) = synchronized(lock) {
        if (inflight?.id == item.id) inflight = null
        state = if (state == State.BUSY) State.ACTIVE else state
        eventLocked("work_completed:" + item.type.name.lowercase())
        persistLocked()
    }

    fun fail(item: WorkItem): Boolean = synchronized(lock) {
        if (inflight?.id == item.id) inflight = null
        val attempts = item.attempts + 1
        if (!OrionRuntimePolicy.shouldRetry(attempts)) {
            // One exhausted item must not strand unrelated queued work.
            state = if (queue.isNotEmpty()) State.ACTIVE else State.DEGRADED
            eventLocked("work_abandoned:" + item.type.name.lowercase())
            persistLocked()
            return@synchronized false
        }
        val delay = OrionRuntimePolicy.retryDelayMs(attempts)
        queue.removeAll { it.id == item.id }
        queue.add(item.copy(attempts = attempts, notBefore = clock() + delay))
        queue.sortWith(compareByDescending<WorkItem> { it.priority }.thenBy { it.createdAt })
        state = State.ACTIVE
        eventLocked("work_backoff:" + attempts)
        persistLocked()
        true
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(state, queue.size, lastEvent, lastChangedAt) }

    /** Delay until the next queued item is eligible; null means there is no pending retry. */
    fun nextEligibleDelayMs(): Long? = synchronized(lock) {
        if (queue.isEmpty() || state == State.STOPPED || state == State.PAUSED || state == State.DEGRADED) return@synchronized null
        OrionRuntimePolicy.nextEligibleDelayMs(clock(), queue.map { it.notBefore })
    }

    private fun eventLocked(event: String) { lastEvent = event.take(80); lastChangedAt = clock() }

    private fun loadLocked() {
        try {
            if (!file.isFile) return
            val root = JSONObject(file.readText())
            state = State.STOPPED
            lastEvent = root.optString("lastEvent", "recovered").take(80)
            lastChangedAt = root.optLong("lastChangedAt", clock())
            val items = root.optJSONArray("queue") ?: JSONArray()
            queue = (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching { WorkType.valueOf(o.optString("type")) }.getOrNull() ?: return@mapNotNull null
                val key = o.optString("key").trim().take(100)
                if (key.isBlank()) return@mapNotNull null
                WorkItem(o.optString("id").ifBlank { UUID.randomUUID().toString() }, key, type,
                    o.optInt("priority").coerceIn(-10, 10), o.optLong("createdAt", clock()),
                    o.optInt("attempts").coerceIn(0, OrionRuntimePolicy.MAX_ATTEMPTS), o.optLong("notBefore", 0L))
            }.distinctBy { it.type.name + ":" + it.key }.take(maxQueue).toMutableList()
            // Recover work claimed immediately before process death.
            val active = root.optJSONObject("inflight")
            inflight = active?.let { o ->
                val type = runCatching { WorkType.valueOf(o.optString("type")) }.getOrNull()
                val key = o.optString("key").trim().take(100)
                if (type == null || key.isBlank()) null else WorkItem(
                    o.optString("id").ifBlank { UUID.randomUUID().toString() }, key, type,
                    o.optInt("priority").coerceIn(-10, 10), o.optLong("createdAt", clock()),
                    o.optInt("attempts").coerceIn(0, OrionRuntimePolicy.MAX_ATTEMPTS), o.optLong("notBefore", 0L))
            }
            inflight?.let { activeItem ->
                if (queue.none { it.type == activeItem.type && it.key == activeItem.key } && queue.size < maxQueue) {
                    queue.add(activeItem.copy(notBefore = 0L))
                }
                inflight = null
            }
        } catch (_: Exception) { queue = mutableListOf(); inflight = null; state = State.STOPPED }
    }

    private fun persistLocked() {
        try {
            val items = JSONArray()
            queue.forEach { w -> items.put(JSONObject().put("id", w.id).put("key", w.key).put("type", w.type.name)
                .put("priority", w.priority).put("createdAt", w.createdAt).put("attempts", w.attempts).put("notBefore", w.notBefore)) }
            val root = JSONObject().put("state", state.name).put("lastEvent", lastEvent)
                .put("lastChangedAt", lastChangedAt).put("queue", items)
            inflight?.let { w ->
                root.put("inflight", JSONObject().put("id", w.id).put("key", w.key).put("type", w.type.name)
                    .put("priority", w.priority).put("createdAt", w.createdAt).put("attempts", w.attempts)
                    .put("notBefore", w.notBefore))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) { file.writeText(root.toString()); tmp.delete() }
        } catch (_: Exception) { /* Persistence must never crash the accessibility service. */ }
    }
}
