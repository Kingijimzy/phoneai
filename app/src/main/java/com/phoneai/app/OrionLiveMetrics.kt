package com.phoneai.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Real, measured numbers for the turn Orion is running now: shown live in the chat, never estimated for show. */
internal object OrionLiveMetrics {
    data class Snapshot(
        val active: Boolean = false,
        val startedAt: Long = 0L,
        val firstWordAt: Long = 0L,
        val lastWordAt: Long = 0L,
        val chars: Int = 0,
        val tools: Int = 0,
        val failedTools: Int = 0,
        val model: String = "",
        val endedAt: Long = 0L
    )

    var state by mutableStateOf(Snapshot())
        private set

    fun begin(now: Long = System.currentTimeMillis()) { state = Snapshot(active = true, startedAt = now) }

    fun onDelta(chars: Int, model: String, now: Long = System.currentTimeMillis()) {
        val s = state
        if (!s.active) return
        state = s.copy(
            firstWordAt = if (s.firstWordAt == 0L) now else s.firstWordAt,
            lastWordAt = now, chars = s.chars + chars,
            model = model.ifBlank { s.model }
        )
    }

    fun onTools(total: Int, failed: Int) {
        val s = state
        if (s.active) state = s.copy(tools = s.tools + total, failedTools = s.failedTools + failed)
    }

    fun end(model: String, now: Long = System.currentTimeMillis()) {
        val s = state
        if (s.active) state = s.copy(active = false, endedAt = now, model = model.ifBlank { s.model })
    }

    fun elapsedMs(s: Snapshot, now: Long): Long = if (s.startedAt == 0L) 0L else ((if (s.active) now else s.endedAt) - s.startedAt).coerceAtLeast(0L)
    fun firstWordMs(s: Snapshot): Long = if (s.firstWordAt == 0L || s.startedAt == 0L) -1L else (s.firstWordAt - s.startedAt).coerceAtLeast(0L)

    /** Output speed while the model was actually writing: ~4 characters per token. */
    fun tokensPerSecond(s: Snapshot, now: Long): Int {
        if (s.chars < 8 || s.firstWordAt == 0L) return 0
        val end = if (s.active) maxOf(now, s.lastWordAt) else s.lastWordAt
        val seconds = ((end - s.firstWordAt).coerceAtLeast(300L)) / 1000.0
        return ((s.chars / 4.0) / seconds).toInt()
    }

    fun seconds(ms: Long): String = if (ms < 10_000) "%.1fs".format(ms / 1000.0) else "${ms / 1000}s"

    /** Chip texts in display order. */
    fun chips(s: Snapshot, now: Long): List<String> = buildList {
        add(seconds(elapsedMs(s, now)))
        val ft = firstWordMs(s)
        if (ft >= 0) add("first word " + seconds(ft))
        val tps = tokensPerSecond(s, now)
        if (tps > 0) add("$tps tok/s")
        if (s.tools > 0) add("${s.tools} tool${if (s.tools == 1) "" else "s"}")
        if (s.failedTools > 0) add("${s.failedTools} failed")
        if (s.model.isNotBlank()) add(s.model.substringAfterLast('/').take(28))
    }
}
