package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Event-driven coordinator: persists bounded work, but never calls providers or executes actions itself. */
internal class OrionRuntime(context: Context, private val clock: () -> Long = { System.currentTimeMillis() }) {
    enum class State { STOPPED, ACTIVE, BUSY, PAUSED, DEGRADED }
    enum class WorkType { LEARN_FROM_RESULT, CAPABILITY_RECHECK, RESTORE_PENDING, SOUL_WATCH }
    data class WorkItem(val id: String, val key: String, val type: WorkType, val priority: Int,
                        val createdAt: Long, val attempts: Int = 0, val notBefore: Long = 0L,
                        val goal: String = "", val checkpoint: String = "", val lastError: String = "")
    data class Snapshot(val state: State, val queued: Int, val lastEvent: String, val lastChangedAt: Long)

    private val appContext = context.applicationContext
    private val lock = Any()
    private val file get() = File(appContext.filesDir, "orion_runtime.json")
    private val atomicFile get() = AtomicFile(file)
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

    fun enqueue(type: WorkType, key: String, priority: Int = 0, goal: String = "", checkpoint: String = ""): Boolean = synchronized(lock) {
        val safeKey = key.trim().take(100)
        if (safeKey.isBlank() || queue.any { it.type == type && it.key == safeKey }) return@synchronized false
        if (queue.size >= maxQueue) {
            val lowest = queue.indices.minByOrNull { queue[it].priority } ?: return@synchronized false
            if (queue[lowest].priority >= priority) return@synchronized false
            queue.removeAt(lowest)
        }
        queue.add(WorkItem(UUID.randomUUID().toString(), safeKey, type, priority.coerceIn(-10, 10), clock(),
            goal = goal.trim().take(500), checkpoint = checkpoint.trim().take(500)))
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

    /** A stale/duplicate acknowledgement must never complete a different in-flight task. */
    fun complete(item: WorkItem): Boolean = synchronized(lock) {
        if (inflight?.id != item.id) return@synchronized false
        inflight = null
        state = if (state == State.BUSY) State.ACTIVE else state
        eventLocked("work_completed:" + item.type.name.lowercase())
        persistLocked()
        true
    }

    /** Only the exact claimed item may be retried; stale failures cannot enqueue phantom work. */
    fun fail(item: WorkItem, error: String = "unknown_failure", checkpoint: String = item.checkpoint): Boolean = synchronized(lock) {
        if (inflight?.id != item.id) return@synchronized false
        inflight = null
        val attempts = item.attempts + 1
        if (!OrionRuntimePolicy.shouldRetry(attempts)) {
            // One exhausted item must not strand unrelated queued work.
            state = if (queue.isNotEmpty()) State.ACTIVE else State.DEGRADED
            eventLocked("work_abandoned:" + item.type.name.lowercase())
            persistLocked()
            return@synchronized false
        }
        val retry = OrionRuntimePolicy.retryItem(item, clock(), error, checkpoint)
            ?: return@synchronized false
        queue.removeAll { it.id == item.id }
        queue.add(retry)
        queue.sortWith(compareByDescending<WorkItem> { it.priority }.thenBy { it.createdAt })
        state = State.ACTIVE
        eventLocked("work_backoff:" + attempts)
        persistLocked()
        true
    }

    /** Durable in-flight and queued items, including the last recoverable checkpoint. */
    fun pendingItems(): List<WorkItem> = synchronized(lock) {
        (listOfNotNull(inflight) + queue).map { it.copy() }
    }

    /** Update the exact active item after a verified step; stale IDs are rejected. */
    fun checkpoint(item: WorkItem, value: String): Boolean = synchronized(lock) {
        val active = inflight ?: return@synchronized false
        if (active.id != item.id) return@synchronized false
        inflight = active.copy(checkpoint = value.trim().take(500))
        eventLocked("checkpoint_saved:" + active.type.name.lowercase())
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
            val root = JSONObject(atomicFile.openRead().bufferedReader().use { it.readText() })
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
                    o.optInt("attempts").coerceIn(0, OrionRuntimePolicy.MAX_ATTEMPTS), o.optLong("notBefore", 0L),
                    o.optString("goal").take(500), o.optString("checkpoint").take(500), o.optString("lastError").take(240))
            }.distinctBy { it.type.name + ":" + it.key }.take(maxQueue).toMutableList()
            // Recover work claimed immediately before process death.
            val active = root.optJSONObject("inflight")
            inflight = active?.let { o ->
                val type = runCatching { WorkType.valueOf(o.optString("type")) }.getOrNull()
                val key = o.optString("key").trim().take(100)
                if (type == null || key.isBlank()) null else WorkItem(
                    o.optString("id").ifBlank { UUID.randomUUID().toString() }, key, type,
                    o.optInt("priority").coerceIn(-10, 10), o.optLong("createdAt", clock()),
                    o.optInt("attempts").coerceIn(0, OrionRuntimePolicy.MAX_ATTEMPTS), o.optLong("notBefore", 0L),
                    o.optString("goal").take(500), o.optString("checkpoint").take(500), o.optString("lastError").take(240))
            }
            inflight?.let { activeItem ->
                // SOUL_WATCH may process an opted-in standing order with an external effect.
                // After a process death its outcome is ambiguous, so never replay that tick blindly.
                if (!OrionRuntimePolicy.canReplayAfterProcessDeath(activeItem.type)) {
                    // Never repeat an uncertain standing-order tick. Queue a read-only
                    // reconciliation instead so the next service start can inspect state.
                    val recoveryKey = "reconcile:" + activeItem.id.take(64)
                    if (queue.none { it.type == WorkType.CAPABILITY_RECHECK && it.key == recoveryKey } && queue.size < maxQueue) {
                        queue.add(WorkItem(
                            id = UUID.randomUUID().toString(),
                            key = recoveryKey,
                            type = WorkType.CAPABILITY_RECHECK,
                            priority = 7,
                            createdAt = clock(),
                            goal = "Reconcile standing-order state after an interrupted watcher tick",
                            checkpoint = "original watcher outcome is unknown; do not replay external effects",
                            lastError = "process_interrupted_outcome_unknown"
                        ))
                    }
                    lastEvent = "recovery_required:soul_watch_outcome_unknown"
                    lastChangedAt = clock()
                } else if (queue.none { it.type == activeItem.type && it.key == activeItem.key } && queue.size < maxQueue) {
                    queue.add(activeItem.copy(
                        notBefore = 0L,
                        lastError = "process_interrupted; resuming checkpointed local work"
                    ))
                }
                inflight = null
            }
        } catch (_: Exception) { queue = mutableListOf(); inflight = null; state = State.STOPPED }
    }

    private fun persistLocked() {
        try {
            val items = JSONArray()
            queue.forEach { w -> items.put(JSONObject().put("id", w.id).put("key", w.key).put("type", w.type.name)
                .put("priority", w.priority).put("createdAt", w.createdAt).put("attempts", w.attempts).put("notBefore", w.notBefore)
                .put("goal", w.goal).put("checkpoint", w.checkpoint).put("lastError", w.lastError)) }
            val root = JSONObject().put("state", state.name).put("lastEvent", lastEvent)
                .put("lastChangedAt", lastChangedAt).put("queue", items)
            inflight?.let { w ->
                root.put("inflight", JSONObject().put("id", w.id).put("key", w.key).put("type", w.type.name)
                    .put("priority", w.priority).put("createdAt", w.createdAt).put("attempts", w.attempts)
                    .put("notBefore", w.notBefore).put("goal", w.goal).put("checkpoint", w.checkpoint)
                    .put("lastError", w.lastError))
            }
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomicFile.startWrite()
                stream.write(root.toString().toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                if (stream != null) runCatching { atomicFile.failWrite(stream) }
                throw e
            }
        } catch (_: Exception) {
            // Persistence must never crash the accessibility service; AtomicFile retains
            // the last complete snapshot if a write is interrupted.
        }
    }
}
