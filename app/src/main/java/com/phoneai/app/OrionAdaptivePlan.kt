package com.phoneai.app

import android.content.Context

/**
 * Designed around what Orion can actually reach right now, not around a fixed picture of the ideal setup.
 *
 * Inputs are measured: which providers are configured, chat-capable and healthy (OrionProviderHealth) and which tools
 * have been proven or are degraded (OrionBrain registry). The output is an execution envelope:
 *   FULL           a healthy tool-capable provider and no degraded tool families → use the best model and every tool.
 *   REDUCED        something is down → still act, but route around it (fallback provider order, substitute tools).
 *   CONVERSATION   no usable provider for tool use → say plainly that it can only talk, do not pretend to act.
 *   OFFLINE        nothing configured at all → tell the owner what to add.
 * The pure [plan] function holds all the rules so they are unit tested; [assess] only gathers the inputs.
 */
internal object OrionAdaptivePlan {
    enum class Mode { FULL, REDUCED, CONVERSATION, OFFLINE }

    data class ProviderState(val id: String, val label: String, val configured: Boolean, val chatCapable: Boolean, val state: String, val ok: Int)
    data class Plan(
        val mode: Mode,
        val primary: String?,
        val fallbacks: List<String>,
        val avoid: List<String>,
        val substitutions: Map<String, String>,
        val notes: List<String>
    )

    private fun rank(s: String) = when (s) { "HEALTHY" -> 0; "UNTESTED" -> 1; "FLAKY" -> 2; else -> 3 }

    fun plan(providers: List<ProviderState>, caps: List<OrionBrain.Capability>): Plan {
        val usable = providers.filter { it.configured && it.chatCapable && it.state != "DOWN" }
            .sortedWith(compareBy<ProviderState> { rank(it.state) }.thenByDescending { it.ok })
        val configured = providers.filter { it.configured }
        val degraded = caps.filter { it.status == OrionBrain.Status.DEGRADED }
        val notes = ArrayList<String>()
        val subs = LinkedHashMap<String, String>()
        degraded.forEach { d ->
            val alt = caps.filter { it.backend == d.backend && it.tool != d.tool && it.write == d.write && it.status != OrionBrain.Status.DEGRADED }
                .sortedByDescending { it.successes }.firstOrNull()
            if (alt != null) subs[d.tool] = alt.tool
        }
        degraded.filter { it.tool !in subs }.forEach { notes += "${it.tool} is degraded and has no healthy sibling in ${it.backend}: report it unavailable instead of retrying." }
        val mode = when {
            configured.isEmpty() -> Mode.OFFLINE
            usable.isEmpty() -> Mode.CONVERSATION
            usable.first().state != "HEALTHY" && usable.first().ok == 0 || degraded.isNotEmpty() || usable.size < configured.size -> Mode.REDUCED
            else -> Mode.FULL
        }
        when (mode) {
            Mode.OFFLINE -> notes += "No provider has a key: add one in Settings; nothing can run until then."
            Mode.CONVERSATION -> notes += "Every configured provider is down or not a chat model: do not claim to have acted; say what is blocked."
            Mode.REDUCED -> if (usable.size == 1) notes += "Single usable provider: no failover available."
            Mode.FULL -> {}
        }
        return Plan(mode, usable.firstOrNull()?.id, usable.drop(1).map { it.id }, degraded.map { it.tool }, subs, notes)
    }

    fun assess(c: Context): Plan {
        val health = OrionProviderHealth.all(c)
        val providers = Providers.all.map { p ->
            val model = Prefs.model(c, p, false)
            val h = health[p.id]
            ProviderState(p.id, p.label, Prefs.hasKey(c, p.id) || p.noKey, ProviderModelCapabilityPolicy.isChatCapable(model), h?.state ?: "UNTESTED", h?.ok ?: 0)
        }
        return plan(providers, OrionBrain.registry(c))
    }

    fun render(p: Plan): String = buildString {
        append("EXECUTION ENVELOPE (measured now): mode=${p.mode.name}")
        append("; primary=").append(p.primary ?: "none")
        if (p.fallbacks.isNotEmpty()) append("; fail over to ").append(p.fallbacks.joinToString(" > "))
        if (p.avoid.isNotEmpty()) append("; avoid ").append(p.avoid.take(6).joinToString())
        if (p.substitutions.isNotEmpty()) append("; use instead ").append(p.substitutions.entries.take(6).joinToString { "${it.value} for ${it.key}" })
        p.notes.forEach { append(". ").append(it) }
        append(".")
    }
}
