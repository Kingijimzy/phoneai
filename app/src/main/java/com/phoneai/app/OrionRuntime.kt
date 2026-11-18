package com.phoneai.app

import android.content.Context

/**
 * The named subsystems of Orion's one brain. Each is a thin, stable interface over the machinery that already exists,
 * so every panel (chat, voice, command, tools) talks to the same three parts instead of reaching into stores directly.
 *
 *  - Cerebro:    persistent contextual memory, retrieval, experience and learning
 *  - Prometheus: self-development, testing, repair and rollback
 *  - Chronos:    context and intent — where the owner is in time, in the phone, in the conversation
 */
internal object OrionCerebro {
    /** Goal -> retrieve: the layered memory block for this request. */
    fun retrieve(c: Context, request: String, owner: String): String = OrionMemoryLayers.block(c, request, owner)
    /** Tool -> learn: attribute a step to the goal and what led to it. */
    fun learn(c: Context, tool: String, ok: Boolean, category: String) = OrionBrain.recordStep(c, tool, ok, category)
    /** Background consolidation of actions into lessons, and of the graph into review suggestions. */
    fun dream(c: Context, force: Boolean = false): String = OrionBrain.dream(c, force = force)
    fun status(c: Context): String = "CEREBRO: ${OrionBrain.steps(c).size} attributed actions, ${OrionLessons.all(c).size} lessons.\n" + OrionBrain.graphReport(c)
}

internal object OrionPrometheus {
    /** What needs repair right now, from measured failures. */
    fun repairQueue(c: Context): List<OrionSelfModel.ToolHealth> = OrionSelfModel.degraded(c)
    fun status(c: Context): String {
        val queue = repairQueue(c)
        val tools = ToolRegistry.names().filter { it == "self_edit" || it == "extension_create" || it == "extension_test" }
        return "PROMETHEUS: self-development tools available: ${tools.joinToString().ifBlank { "none" }}. " +
            if (queue.isEmpty()) "Nothing is failing repeatedly." else "Repair queue: " + queue.take(6).joinToString { "${it.tool} (${it.consecutive}x: ${it.lastReason.take(50)})" } +
                ". Fix path: diagnose, self_edit or extension_create, test, merge through CI; revert via git if CI is red."
    }
}

internal object OrionChronos {
    /** Intent/context: what the owner has been doing on the phone, from the existing awareness store. */
    fun context(c: Context): String = runCatching { OrionChronosAwareness.summary(c) }.getOrDefault("")
    fun status(c: Context): String = "CHRONOS: " + context(c).ifBlank { "no recent activity recorded." }.take(400)
}
