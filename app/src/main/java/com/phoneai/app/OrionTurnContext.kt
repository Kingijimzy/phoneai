package com.phoneai.app

import android.content.Context

/**
 * One typed context snapshot per turn. Every surface (chat, Talk, Command, voice, resume,
 * specialist return, image analysis, pre-action probe) asks here instead of rebuilding the
 * ~29-section Central Brain context, so a turn builds it once and reuses it.
 */
internal object OrionTurnContext {
    data class Snapshot(val request: String, val text: String, val builtAtMs: Long)

    const val TTL_MS = 8_000L
    @Volatile private var last: Snapshot? = null
    @Volatile var builds: Int = 0
        private set

    /** Pure cache decision, unit-testable without Android. */
    internal fun reusable(s: Snapshot?, request: String, now: Long): Boolean =
        s != null && s.text.isNotBlank() && now - s.builtAtMs in 0..TTL_MS && s.request == request

    fun snapshot(context: Context, request: String, now: Long = System.currentTimeMillis(),
                 build: () -> String = { OrionCentralBrain.contextFor(context.applicationContext, request) }): Snapshot {
        OrionEntityLoop.mark(OrionEntityLoop.Stage.RECALL)
        val cached = last
        if (reusable(cached, request, now)) return cached!!
        OrionEntityLoop.mark(OrionEntityLoop.Stage.RECALL)
        val fresh = Snapshot(request, build(), now)
        builds++
        last = fresh
        return fresh
    }

    private val turn = ThreadLocal<Snapshot?>()

    /** Pin the turn's one snapshot on this thread so every later caller in the turn reuses it, whatever wording it passes. */
    fun <T> withTurn(context: Context, request: String, block: () -> T): T {
        turn.set(runCatching { snapshot(context, request) }.getOrNull())
        try { return block() } finally { turn.set(null) }
    }

    fun text(context: Context, request: String): String = turn.get()?.text ?: snapshot(context, request).text

    internal fun resetForTest() { last = null; builds = 0 }
}
