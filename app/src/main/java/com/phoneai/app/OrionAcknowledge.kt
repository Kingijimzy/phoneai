package com.phoneai.app

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Never stay silent while working." A person says "one second" before going to fetch something; Orion does the same
 * on voice, but without guessing what the request is. It is pure latency masking: if the owner has been waiting longer
 * than [delayMs] and Orion has not yet said anything real, one short in-character line is spoken. A fast answer never
 * triggers it, and the line rotates so the same opener is never used twice in a row.
 */
internal object OrionAcknowledge {
    val lines = listOf("On it.", "One second.", "Give me a moment.", "Right, looking into it.", "Working on it.", "Hold on, I'm on it.")
    private val next = AtomicInteger(0)

    fun nextLine(): String = lines[Math.floorMod(next.getAndIncrement(), lines.size)]

    class Handle internal constructor() {
        internal val spoke = AtomicBoolean(false)
        internal val cancelled = AtomicBoolean(false)
        fun cancel() { cancelled.set(true) }
        /** Call whenever Orion says anything real; it makes the pending acknowledgement unnecessary. */
        fun markSpoken() { spoke.set(true) }
    }

    @Volatile private var active: Handle? = null

    /** Called from the speech path so real output silences a pending acknowledgement. */
    fun noteSpeech() { active?.markSpoken() }

    fun arm(delayMs: Long = 1400L, speak: (String) -> Unit): Handle {
        val h = Handle()
        active = h
        Thread({
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            if (!h.cancelled.get() && !h.spoke.get()) {
                h.spoke.set(true)
                runCatching { speak(nextLine()) }
            }
        }, "orion-ack").apply { isDaemon = true }.start()
        return h
    }
}
