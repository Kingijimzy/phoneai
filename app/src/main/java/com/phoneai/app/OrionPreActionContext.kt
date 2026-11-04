package com.phoneai.app

import android.content.Context

/**
 * Local context assessment before Orion dispatches tools or fast intents.
 * Reads live capability / graph state so actions are grounded (not an LLM call).
 */
internal object OrionPreActionContext {
    data class Assessment(
        val summary: String,
        val allowFastPath: Boolean,
        val blockers: List<String>
    )

    fun assess(context: Context, command: String): Assessment {
        val app = context.applicationContext
        val blockers = ArrayList<String>()
        val bits = ArrayList<String>()

        val packsOn = runCatching {
            AgentPolicy.packs.count { it.implemented && AgentPolicy.enabled(app, it.id) }
        }.getOrDefault(0)
        val tools = runCatching { ToolRegistry.names().size }.getOrDefault(0)
        bits += "packs=$packsOn tools=$tools"

        runCatching { OrionCentralBrain.contextFor(app, command) }.onSuccess {
            if (it.isNotBlank()) bits += "brain_context_ok" else blockers += "central_brain_empty"
        }.onFailure {
            blockers += "central_brain_unavailable"
        }

        val graph = runCatching { OrionObjectiveGraph.load(app) }.getOrNull()
        if (graph != null) {
            bits += "objectives=${graph.nodes.size}"
        }

        val lower = command.lowercase()
        if (Regex("\\b(open|launch)\\b").containsMatchIn(lower) &&
            Regex("\\b(music|spotify|youtube music)\\b").containsMatchIn(lower) &&
            !Regex("\\b(play|search)\\b").containsMatchIn(lower)
        ) {
            bits += "route=native_music_app"
        }

        val summary = buildString {
            append("Pre-action context: ")
            append(bits.joinToString(" · "))
            if (blockers.isNotEmpty()) append(" | blockers=").append(blockers.joinToString(","))
        }
        return Assessment(summary = summary, allowFastPath = true, blockers = blockers)
    }
}
