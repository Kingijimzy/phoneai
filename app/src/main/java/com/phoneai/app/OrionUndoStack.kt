package com.phoneai.app

import android.content.Context
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 0D: Ultron-Style Undo Stack
 *
 * Reversible actions execute immediately and record their reversal on a shared stack.
 * When the user says "undo", the most recent reversible operation is reversed.
 *
 * This replaces the old "ask before every action" model with:
 *   - ACT IMMEDIATELY (execute now)
 *   - RECORD UNDO (capture reversal)
 *   - USER SAYS UNDO (reverse it)
 *
 * Only genuinely irreversible actions (Trading Desk orders, restart/shutdown/Wi-Fi toggles)
 * use the confirmation gate instead of undo.
 */
internal class OrionUndoStack(
    private val context: Context,
    private val svc: PhoneAiService
) {

    private val stack = ConcurrentHashMap<String, MutableList<UndoEntry>>() // keyed by trace ID -> list of entries
    private val maxDepth = 20

    private data class UndoEntry(
        val label: String,
        val undoFn: () -> String,
        val at: Long = System.currentTimeMillis()
    )

    /**
     * Record that `label` just happened and `undoFn()` reverses it.
     * Called from tool dispatch after a reversible action succeeds.
     */
    fun pushUndo(traceId: String, label: String, undoFn: () -> String) {
        if (!undoFn::class.isMarkerAnnotation) { /* always callable */ }
        try {
            val list = stack.getOrPut(traceId) { mutableListOf<UndoEntry>() }
            list.add(UndoEntry(label, undoFn))
            // Drop oldest if over depth
            while (list.size > maxDepth) list.removeAt(0)
            OrionEvidenceLedger.recordDispatch(
                svc, traceId, "push_undo", "push_undo",
                org.json.JSONObject().put("label", label).put("trace", traceId),
                System.currentTimeMillis(),
                "Recorded reversible operation: $label"
            )
        } catch (e: Exception) {
            // Undo registration must never take down the action that succeeded
            OrionEvidenceLedger.recordDispatch(
                svc, traceId, "push_undo_error", "push_undo_error",
                org.json.JSONObject().put("error", e.message.orEmpty()),
                System.currentTimeMillis(),
                "Undo registration failed (non-fatal)"
            )
        }
    }

    /** Can undo anything at all. */
    fun canUndo(): Boolean = stack.values.any { it.isNotEmpty() }

    /** Peek at the most recent undoable label. */
    fun peekUndo(): String? {
        return stack.values.flatMap { it }.lastOrNull()?.label
    }

    /** List all undoable operations, most recent first. */
    fun undoHistory(): List<String> {
        return stack.values
            .flatMap { it }
            .reversed()
            .map { it.label }
    }

    /** Reverse the most recent reversible operation. */
    fun undoLast(): String {
        // Find the most recent entry across all traces
        val all = stack.values.flatMap { it }
        if (all.isEmpty()) {
            return ("There is nothing to undo. I only track things I changed myself — "
                "files I moved or wrote, settings I adjusted, messages I drafted.")
        }
        val entry = all.last()
        // Remove it from its trace list
        stack.values.forEach { list ->
            list.remove(entry)
        }

        return try {
            val detail = entry.undoFn() or ""
            OrionEvidenceLedger.recordDispatch(
                svc, entry.label, "undo_executed", "undo_executed",
                org.json.JSONObject().put("label", entry.label),
                System.currentTimeMillis(),
                "Undone: ${entry.label}"
            )
            "Undone: ${entry.label}." + (if (detail.isNotBlank()) " $detail" else "")
        } catch (e: Exception) {
            "Could not undo '${entry.label}': ${e.message.orEmpty()}"
        }
    }

    /** Clear the stack (called on shutdown). */
    fun clear() {
        stack.clear()
    }

    companion object {
        @Volatile private var INSTANCE: OrionUndoStack? = null
        fun getInstance(context: Context, svc: PhoneAiService): OrionUndoStack {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OrionUndoStack(context, svc).also { INSTANCE = it }
            }
        }
    }
}

// Internal UndoEntry used by the stack (keep top-level for external test access)
internal data class UndoEntry(
    val label: String,
    val undoFn: () -> String,
    val at: Long = System.currentTimeMillis()
)
