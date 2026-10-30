package com.phoneai.app

/**
 * The single rule for what Orion may offer and use right now.
 * Facts come from the real backend (packs, permissions, setup, health checks); this object only derives state from them,
 * so a capability is usable only when the facts say so. Pure Kotlin, so the rules are unit tested.
 *
 * Precedence: unsupported > disabled > permission > setup > missing dependency > temporarily unavailable >
 * failed health > limited > available. A capability that has never been tested is "available with limitations",
 * never plain "available".
 */
internal object CapabilityRegistry {
    enum class State {
        AVAILABLE, AVAILABLE_LIMITED, PERMISSION_REQUIRED, SETUP_REQUIRED, MISSING_DEPENDENCY,
        TEMPORARILY_UNAVAILABLE, FAILED_HEALTH, DISABLED, UNSUPPORTED
    }
    enum class Source { BUILT_IN, LOCAL_INSTALLED, EXTERNAL_CONNECTED, EXPERIMENTAL }
    enum class Health { OK, DEGRADED, FAILED, UNKNOWN }

    data class Facts(
        val id: String,
        val title: String,
        val source: Source,
        val supported: Boolean = true,
        val enabledByUser: Boolean = true,
        val permissionNeeded: String? = null,      // non-null when a permission/consent is required and not granted
        val setupNeeded: String? = null,           // non-null when setup (credentials, install step) is not done
        val missingDependencies: List<String> = emptyList(),
        val temporarilyDown: String? = null,       // non-null with a reason while the backend is busy or offline
        val health: Health = Health.UNKNOWN,
        val limitation: String? = null
    )

    data class Entry(val facts: Facts, val state: State, val reason: String) {
        val usable: Boolean get() = state == State.AVAILABLE || state == State.AVAILABLE_LIMITED
    }

    fun evaluate(f: Facts): Entry = when {
        !f.supported -> Entry(f, State.UNSUPPORTED, f.limitation ?: "not supported on this device")
        !f.enabledByUser -> Entry(f, State.DISABLED, f.limitation ?: "turned off in Tools & Skills")
        f.permissionNeeded != null -> Entry(f, State.PERMISSION_REQUIRED, f.permissionNeeded)
        f.setupNeeded != null -> Entry(f, State.SETUP_REQUIRED, f.setupNeeded)
        f.missingDependencies.isNotEmpty() -> Entry(f, State.MISSING_DEPENDENCY, "missing: " + f.missingDependencies.joinToString(", "))
        f.temporarilyDown != null -> Entry(f, State.TEMPORARILY_UNAVAILABLE, f.temporarilyDown)
        f.health == Health.FAILED -> Entry(f, State.FAILED_HEALTH, "last health check failed; run Test to see why")
        f.health == Health.DEGRADED -> Entry(f, State.AVAILABLE_LIMITED, f.limitation ?: "works with reduced reliability")
        f.health == Health.UNKNOWN -> Entry(f, State.AVAILABLE_LIMITED, f.limitation ?: "not tested yet on this device")
        f.limitation != null -> Entry(f, State.AVAILABLE_LIMITED, f.limitation)
        else -> Entry(f, State.AVAILABLE, "ready")
    }

    /**
     * A built-in tool's entry from its gate inputs: the user's DENY setting and whether its pack is on.
     * ToolGate uses this for the pre-flight decision, so the gate and the registry share one rule.
     */
    fun forBuiltInTool(tool: String, denied: Boolean, packOn: Boolean): Entry = evaluate(
        Facts(id = tool, title = tool, source = Source.BUILT_IN, enabledByUser = !denied && packOn, health = Health.OK)
    )

    /** The only list Orion is allowed to offer as usable. Anything else must be shown with its reason, never used. */
    fun offerable(entries: List<Entry>): List<Entry> = entries.filter { it.usable }

    /** One tool's gate inputs and its registry entry. [pack] is null for tools that belong to no pack. */
    data class Row(val tool: String, val pack: String?, val denied: Boolean, val entry: Entry)

    /**
     * The live status block for the system prompt, built only from registry entries.
     * Same wording as the older status text, so the model's instructions do not change. Returns "" when all is ready.
     */
    fun statusText(rows: List<Row>, titleOf: (String) -> String, howToEnable: (String) -> String): String {
        val offByPack = LinkedHashMap<String, MutableList<String>>()
        val denied = ArrayList<String>()
        rows.forEach { r ->
            if (r.entry.usable) return@forEach
            if (r.denied) denied += r.tool
            else r.pack?.let { offByPack.getOrPut(it) { ArrayList() } += r.tool }
        }
        if (offByPack.isEmpty() && denied.isEmpty()) return ""
        val sb = StringBuilder("TOOL STATUS RIGHT NOW (true for this moment; use it, do not guess):\n")
        offByPack.forEach { (pack, list) ->
            sb.append("- ").append(titleOf(pack)).append(" is OFF, so these are unavailable: ")
                .append(list.take(6).joinToString(", ")).append(if (list.size > 6) " and ${list.size - 6} more" else "")
                .append(". Tell the user to switch it on in ").append(howToEnable(pack)).append(". The feature exists; it is not missing.\n")
        }
        if (denied.isNotEmpty()) {
            sb.append("- The user has set these to DENY: ").append(denied.take(8).joinToString(", "))
                .append(". Do not use them; say the user can change this in Tool permissions.\n")
        }
        sb.append("- Everything else is ready. Tools marked ASK ask the user once per task.")
        return sb.toString()
    }

    /** Prompt block: one line per capability that is not usable, so the model never claims it can use one. */
    fun blockedLines(entries: List<Entry>): String = entries
        .filterNot { it.usable }
        .joinToString("\n") { "- ${it.facts.title} (${it.facts.id}): ${it.state.name.lowercase()} — ${it.reason}" }
}
