package com.phoneai.app

import android.content.Context
import org.json.JSONObject

internal enum class ToolMode { ALLOW, ASK, DENY }

/** Per-tool controls are persisted locally; Android OS permissions remain mandatory. */
internal object ToolPermissionPolicy {
    val alwaysAllowed = setOf("ask_user", "wait", "time")
    val levels = listOf("autonomous", "careful", "read_only")
    fun levelLabel(level: String) = when (level.lowercase()) {
        "read_only" -> "Read-only: write tools are denied."
        "careful" -> "Careful: write tools require approval."
        else -> "Autonomous: approved registered tools run directly; Android permissions and hard blocks still apply."
    }
    fun preset(level: String, tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> =
        tools.associate { it.name to when {
            it.name in alwaysAllowed -> ToolMode.ALLOW
            level.equals("read_only", true) && it.access == ToolAccess.WRITE -> ToolMode.DENY
            level.equals("careful", true) && it.access == ToolAccess.WRITE -> ToolMode.ASK
            else -> ToolMode.ALLOW
        } }
    fun modeOf(map: Map<String, ToolMode>, tool: String): ToolMode = map[tool] ?: ToolMode.ALLOW
    fun applyPreset(level: String, overrides: Map<String, ToolMode>, tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> {
        val known = tools.mapTo(mutableSetOf()) { it.name }
        return preset(level, tools).toMutableMap().apply { overrides.filterKeys { it in known }.forEach { (k, v) -> put(k, v) } }
    }
    fun serializeOverrides(map: Map<String, ToolMode>): String = JSONObject().apply { map.forEach { (k, v) -> put(k, v.name) } }.toString()
    fun serialize(map: Map<String, ToolMode>) = serializeOverrides(map)
    fun next(mode: ToolMode) = when (mode) { ToolMode.ALLOW -> ToolMode.ASK; ToolMode.ASK -> ToolMode.DENY; ToolMode.DENY -> ToolMode.ALLOW }
    fun parse(json: String?): Map<String, ToolMode> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val mode = runCatching { ToolMode.valueOf(obj.optString(key).uppercase()) }.getOrNull()
                    if (!key.isBlank() && mode != null) put(key, mode)
                }
            }
        }.getOrDefault(emptyMap())
    }
    fun denyMessage(tool: String) = "Tool '$tool' is not registered in Orion's tool registry."
}

object ToolPermissions {
    private const val PREFS = "phoneai"
    private const val MODES = "tool_modes"
    private const val LEVEL = "tool_level"
    internal fun map(c: Context) = ToolPermissionPolicy.parse(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODES, null))
    internal fun mode(c: Context, tool: String) = map(c)[tool] ?: ToolMode.ALLOW
    internal fun hasOverride(c: Context, tool: String) = map(c).containsKey(tool)
    internal fun set(c: Context, modes: Map<String, ToolMode>) {
        val known = ToolRegistry.all.mapTo(mutableSetOf()) { it.name }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MODES, ToolPermissionPolicy.serializeOverrides(modes.filterKeys { it in known })).apply()
    }
    fun level(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(LEVEL, "autonomous")?.lowercase()?.takeIf { it in ToolPermissionPolicy.levels } ?: "autonomous"
    internal fun applyLevel(c: Context, level: String) {
        val normalized = level.lowercase().takeIf { it in ToolPermissionPolicy.levels } ?: "autonomous"
        val merged = ToolPermissionPolicy.preset(normalized).toMutableMap().apply { putAll(map(c)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LEVEL, normalized).putString(MODES, ToolPermissionPolicy.serializeOverrides(merged)).apply()
    }
    internal fun cycle(c: Context, tool: String) {
        if (ToolRegistry.all.none { it.name == tool }) return
        set(c, map(c) + (tool to ToolPermissionPolicy.next(mode(c, tool))))
    }
}
