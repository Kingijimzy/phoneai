package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Memory(
    val id: Int, var category: String, var text: String, val created: Long, var updated: Long,
    var pinned: Boolean = false, var sourceRole: String = "agent", var confidence: Double = 0.65,
    var sensitivity: String = "general", var reviewStatus: String = "pending", var evidence: String = ""
)

/** Orion's long-term memory. Saved in a file on the phone, so it survives closing the app. */
object MemoryStore {
    val CATEGORIES = listOf("preference", "project", "decision", "task", "conversation", "fact")
    private val lock = Any()
    private var cache: MutableList<Memory>? = null

    private fun file(c: Context) = File(c.filesDir, "orion_memory.json")

    fun cat(s: String?): String {
        val v = (s ?: "").lowercase().trim()
        return if (v in CATEGORIES) v else "fact"
    }

    private fun load(c: Context): MutableList<Memory> {
        val existing = cache
        if (existing != null) return existing
        val out = ArrayList<Memory>()
        try {
            val f = file(c)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        Memory(
                            o.getInt("id"), cat(o.optString("category", "fact")), o.optString("text", ""),
                            o.optLong("created", 0L), o.optLong("updated", 0L), o.optBoolean("pinned", false),
                            o.optString("sourceRole", "legacy"), o.optDouble("confidence", 0.5),
                            o.optString("sensitivity", "general"), o.optString("reviewStatus", "pending"),
                            o.optString("evidence", "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        cache = out
        return out
    }

    /** Atomic replacement where supported; return failure instead of silently claiming data was saved. */
    private fun save(c: Context): Boolean {
        val list = cache ?: return false
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject().put("id", m.id).put("category", m.category).put("text", m.text)
                     .put("created", m.created).put("updated", m.updated).put("pinned", m.pinned)
                     .put("sourceRole", m.sourceRole).put("confidence", MemoryGovernancePolicy.normalizeConfidence(m.confidence))
                     .put("sensitivity", m.sensitivity).put("reviewStatus", m.reviewStatus)
                     .put("evidence", m.evidence.take(240))
            )
        }
        val target = file(c)
        val temp = File(c.filesDir, "orion_memory.json.tmp")
        return try {
            temp.writeText(arr.toString())
            if (temp.renameTo(target)) {
                true
            } else {
                // Fallback for filesystems where rename-over-existing is unsupported.
                target.writeText(arr.toString())
                temp.delete()
                true
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private fun copyOf(m: Memory) = Memory(
        m.id, m.category, m.text, m.created, m.updated, m.pinned,
        m.sourceRole, m.confidence, m.sensitivity, m.reviewStatus, m.evidence
    )

    fun all(c: Context): List<Memory> = synchronized(lock) { load(c).map { copyOf(it) }.sortedWith(compareByDescending<Memory> { it.pinned }.thenByDescending { it.updated }) }

    /** Model/tool-originated writes remain proposals until the owner reviews them. */
    fun add(c: Context, category: String?, text: String): Memory? =
        addWithProvenance(c, category, text, "agent", 0.65, "pending", "general", "model/tool proposal")

    /** Direct entries typed into the Memory screen are owner-confirmed. */
    fun addOwnerConfirmed(c: Context, category: String?, text: String): Memory? =
        addWithProvenance(c, category, text, "owner", 1.0, "approved", "general", "entered directly by owner")

    private fun addWithProvenance(
        c: Context, category: String?, text: String, sourceRole: String, confidence: Double,
        reviewStatus: String, sensitivity: String, evidence: String
    ): Memory? = synchronized(lock) {
        val t = text.trim().replace("\n", " ").take(300)
        if (t.isEmpty()) return@synchronized null
        val list = load(c)
        val now = System.currentTimeMillis()
        val safeSensitivity = MemoryGovernancePolicy.sensitivity(t, sensitivity)
        val dup = list.firstOrNull { it.text.equals(t, true) }
        if (dup != null) {
            val old = copyOf(dup)
            dup.updated = now
            dup.sourceRole = sourceRole
            dup.confidence = MemoryGovernancePolicy.normalizeConfidence(confidence)
            dup.sensitivity = safeSensitivity
            dup.reviewStatus = reviewStatus
            dup.evidence = evidence.take(240)
            if (!save(c)) { restoreFields(dup, old); return@synchronized null }
            return@synchronized copyOf(dup)
        }
        val replaced = MemoryRanking.findSuperseded(list, cat(category), t)
        if (replaced != null) {
            val old = copyOf(replaced)
            replaced.text = t
            replaced.updated = now
            replaced.sourceRole = sourceRole
            replaced.confidence = MemoryGovernancePolicy.normalizeConfidence(confidence)
            replaced.sensitivity = safeSensitivity
            replaced.reviewStatus = reviewStatus
            replaced.evidence = evidence.take(240)
            if (!save(c)) { restoreFields(replaced, old); return@synchronized null }
            return@synchronized copyOf(replaced)
        }
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val m = Memory(id, cat(category), t, now, now, false, sourceRole,
            MemoryGovernancePolicy.normalizeConfidence(confidence), safeSensitivity, reviewStatus, evidence.take(240))
        list.add(m)
        val removed = mutableListOf<Memory>()
        while (list.size > 300) removed.add(list.removeAt(0))
        if (!save(c)) {
            list.removeAll { it.id == id }
            list.addAll(0, removed)
            return@synchronized null
        }
        copyOf(m)
    }

    private fun restoreFields(target: Memory, source: Memory) {
        target.category = source.category
        target.text = source.text
        target.updated = source.updated
        target.pinned = source.pinned
        target.sourceRole = source.sourceRole
        target.confidence = source.confidence
        target.sensitivity = source.sensitivity
        target.reviewStatus = source.reviewStatus
        target.evidence = source.evidence
    }

    fun setReviewStatus(c: Context, id: Int, status: String): Boolean = synchronized(lock) {
        if (status !in setOf("approved", "rejected")) return@synchronized false
        val memory = load(c).firstOrNull { it.id == id } ?: return@synchronized false
        val old = memory.reviewStatus
        memory.reviewStatus = status
        if (!save(c)) { memory.reviewStatus = old; return@synchronized false }
        true
    }

    fun update(c: Context, id: Int, category: String?, text: String?): Boolean = synchronized(lock) {
        val m = load(c).firstOrNull { it.id == id } ?: return@synchronized false
        val oldText = m.text
        val oldCategory = m.category
        val oldUpdated = m.updated
        if (text != null) {
            val t = text.trim().replace("\n", " ").take(300)
            if (t.isNotEmpty()) m.text = t
        }
        if (category != null) m.category = cat(category)
        m.updated = System.currentTimeMillis()
        if (!save(c)) {
            m.text = oldText
            m.category = oldCategory
            m.updated = oldUpdated
            return@synchronized false
        }
        true
    }

    fun updateOwnerConfirmed(c: Context, id: Int, category: String?, text: String?): Boolean {
        if (!update(c, id, category, text)) return false
        return synchronized(lock) {
            val memory = load(c).firstOrNull { it.id == id } ?: return@synchronized false
            val oldRole = memory.sourceRole
            val oldConfidence = memory.confidence
            val oldStatus = memory.reviewStatus
            val oldEvidence = memory.evidence
            memory.sourceRole = "owner"
            memory.confidence = 1.0
            memory.reviewStatus = "approved"
            memory.evidence = "edited directly by owner"
            if (!save(c)) {
                memory.sourceRole = oldRole
                memory.confidence = oldConfidence
                memory.reviewStatus = oldStatus
                memory.evidence = oldEvidence
                false
            } else true
        }
    }

    fun setPinned(c: Context, id: Int, pinned: Boolean): Boolean = synchronized(lock) {
        val memory = load(c).firstOrNull { it.id == id } ?: return@synchronized false
        val previous = memory.pinned
        memory.pinned = pinned
        if (!save(c)) {
            memory.pinned = previous
            return@synchronized false
        }
        true
    }

    fun delete(c: Context, id: Int): Boolean = synchronized(lock) {
        val list = load(c)
        val removed = list.filter { it.id == id }
        if (removed.isEmpty()) return@synchronized false
        list.removeAll { it.id == id }
        if (!save(c)) {
            list.addAll(removed)
            return@synchronized false
        }
        true
    }

    fun clear(c: Context): Boolean = synchronized(lock) {
        val list = load(c)
        val previous = list.map { copyOf(it) }
        list.clear()
        if (!save(c)) {
            list.addAll(previous)
            return@synchronized false
        }
        true
    }

    /** Restore a deleted memory with its original id; refuses to overwrite or duplicate data. */
    fun restore(c: Context, id: Int, category: String, text: String, created: Long, updated: Long, sourceRole: String = "restored", confidence: Double = 0.5, sensitivity: String = "general", reviewStatus: String = "pending", evidence: String = "restored from undo history"): Boolean = synchronized(lock) {
        val clean = text.trim().replace("\n", " ").take(300)
        if (id < 1 || clean.isEmpty()) return@synchronized false
        val list = load(c)
        if (list.any { it.id == id || it.text.equals(clean, true) }) return@synchronized false
        val memory = Memory(id, cat(category), clean, created, updated, false, sourceRole, MemoryGovernancePolicy.normalizeConfidence(confidence), MemoryGovernancePolicy.sensitivity(clean, sensitivity), reviewStatus, evidence.take(240))
        list.add(memory)
        if (!save(c)) {
            list.remove(memory)
            return@synchronized false
        }
        true
    }

    /** Rank relevant memories first, rather than returning the first loose substring match. */
    fun search(c: Context, query: String): List<Memory> {
        val memories = all(c)
        if (MemoryRanking.tokens(query).isEmpty()) return memories.sortedByDescending { it.updated }.take(10)
        val hybrid = MemoryRanking.hybridRank(memories, { it.text + " " + it.category }, query, 15)
        if (hybrid.isNotEmpty()) return hybrid
        return memories.map { it to MemoryRanking.score(it, query) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Memory, Int>> { it.second }.thenByDescending { it.first.updated })
            .map { it.first }
    }

    /** Text added to the AI's instructions: pinned and request-relevant memories first, then recent ones. */
    fun promptBlock(c: Context, query: String? = null): String {
        val items = MemoryRanking.select(all(c), query)
        if (items.isEmpty()) return "(nothing saved yet)"
        return items.filter { MemoryGovernancePolicy.canUseInPrompt(it.reviewStatus) }.joinToString("\n") {
            (if (it.pinned) "[${it.id}] (${it.category}, pinned) " else "[${it.id}] (${it.category}) ") + it.text
        }.ifBlank { "(no owner-approved memories yet; proposals require review)" }
    }
}
