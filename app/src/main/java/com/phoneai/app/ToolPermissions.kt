package com.phoneai.app

import android.content.Context
import org.json.JSONObject

internal enum class ToolMode { ALLOW, ASK, DENY }

/** Pure allow / ask / deny rules, so autonomy levels are unit-testable. */
internal object ToolPermissionPolicy {
    /** Tools Orion always needs to talk to the user; they can never be denied. */
    val alwaysAllowed = setOf("ask_user", "wait", "time")

    val levels = listOf("careful", "balanced", "autonomous", "read_only")

    fun levelLabel(level: String) = when (level) {
        "careful" -> "Careful: ask before every action that changes something"
        "autonomous" -> "Autonomous: act without asking (safety confirmations still apply)"
        "read_only" -> "Read-only: look and answer, never change anything"
        else -> "Balanced: default behaviour"
    }

    /** Builds the per-tool map for a preset level from the real registry access classes. */
    fun preset(level: String, tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> = when (level) {
        "careful" -> tools.filter { it.access == ToolAccess.WRITE && it.name !in alwaysAllowed }.associate { it.name to ToolMode.ASK }
        "read_only" -> tools.filter { it.access == ToolAccess.WRITE && it.name !in alwaysAllowed }.associate { it.name to ToolMode.DENY }
        else -> emptyMap()
    }

    fun modeOf(map: Map<String, ToolMode>, tool: String): ToolMode =
        if (tool in alwaysAllowed) ToolMode.ALLOW else map[tool] ?: ToolMode.ALLOW

    /** Apply user overrides after the selected preset; explicit ALLOW is a real override too. */
    fun applyPreset(level: String, overrides: Map<String, ToolMode>, tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> {
        val registered = tools.map { it.name }.toSet() - alwaysAllowed
        return preset(level, tools) + overrides.filterKeys { it in registered }
    }

    fun serializeOverrides(map: Map<String, ToolMode>): String =
        JSONObject().also { o -> map.forEach { (k, v) -> if (k !in alwaysAllowed) o.put(k, v.name) } }.toString()

    fun next(m: ToolMode): ToolMode = when (m) { ToolMode.ALLOW -> ToolMode.ASK; ToolMode.ASK -> ToolMode.DENY; ToolMode.DENY -> ToolMode.ALLOW }

    fun serialize(map: Map<String, ToolMode>): String =
        JSONObject().also { o -> map.filterValues { it != ToolMode.ALLOW }.forEach { (k, v) -> o.put(k, v.name) } }.toString()

    fun parse(json: String?): Map<String, ToolMode> = try {
        val o = JSONObject(json ?: "{}")
        o.keys().asSequence().mapNotNull { k -> runCatching { ToolMode.valueOf(o.getString(k)) }.getOrNull()?.let { k to it } }.toMap()
    } catch (_: Exception) { emptyMap() }

    fun denyMessage(tool: String) =
        "error: the user has denied the tool '$tool' in Orion's Tool permissions. Do not try to work around it with another tool; tell the user it is blocked and how to allow it (Settings > Tool permissions)."
}

object ToolPermissions {
    private fun sp(c: Context) = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE)
    internal fun map(c: Context): Map<String, ToolMode> = ToolPermissionPolicy.parse(sp(c).getString("tool_modes", "{}"))
    internal fun mode(c: Context, tool: String): ToolMode = ToolPermissionPolicy.modeOf(map(c), tool)
    internal fun set(c: Context, m: Map<String, ToolMode>) { sp(c).edit().putString("tool_modes", ToolPermissionPolicy.serialize(m)).apply() }

    /** Return the selected preset even when the UI labels the effective per-tool map as custom. */
    fun level(c: Context): String = baseLevel(c)

    private fun baseLevel(c: Context): String {
        val p = sp(c)
        val saved = p.getString("tool_base_level", null)
        if (!saved.isNullOrBlank()) return saved
        val current = p.getString("tool_level", "balanced") ?: "balanced"
        return if (current == "custom") "balanced" else current
    }

    private fun overrides(c: Context): Map<String, ToolMode> {
        val p = sp(c)
        if (p.contains("tool_overrides")) return ToolPermissionPolicy.parse(p.getString("tool_overrides", "{}"))
        // One-time migration: older builds stored a flattened effective map after the user
        // edited a single tool. Preserve those choices rather than silently discarding them.
        return if (p.getString("tool_level", "balanced") == "custom") {
            ToolPermissionPolicy.parse(p.getString("tool_modes", "{}"))
        } else emptyMap()
    }

    internal fun applyLevel(c: Context, level: String) {
        val p = sp(c)
        val selected = level.takeIf { it in ToolPermissionPolicy.levels } ?: "balanced"
        val userOverrides = overrides(c)
        val effective = ToolPermissionPolicy.applyPreset(selected, userOverrides)
        p.edit()
            .putString("tool_level", selected)
            .putString("tool_base_level", selected)
            .putString("tool_overrides", ToolPermissionPolicy.serializeOverrides(userOverrides))
            .putString("tool_modes", ToolPermissionPolicy.serialize(effective))
            .apply()
    }

    internal fun cycle(c: Context, tool: String) {
        val p = sp(c)
        val base = baseLevel(c)
        val userOverrides = overrides(c).toMutableMap()
        val effective = ToolPermissionPolicy.applyPreset(base, userOverrides)
        userOverrides[tool] = ToolPermissionPolicy.next(ToolPermissionPolicy.modeOf(effective, tool))
        val updated = ToolPermissionPolicy.applyPreset(base, userOverrides)
        p.edit()
            .putString("tool_level", "custom")
            .putString("tool_base_level", base)
            .putString("tool_overrides", ToolPermissionPolicy.serializeOverrides(userOverrides))
            .putString("tool_modes", ToolPermissionPolicy.serialize(updated))
            .apply()
    }
}
