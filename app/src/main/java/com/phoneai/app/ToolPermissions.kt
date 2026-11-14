package com.phoneai.app

import android.content.Context
import org.json.JSONObject

internal enum class ToolMode { ALLOW, ASK, DENY }

/** Per-tool controls are persisted locally; Android OS permissions remain mandatory. */
internal object ToolPermissionPolicy {
    val alwaysAllowed = setOf("ask_user", "wait", "time")
    val levels = listOf("autonomous")
    fun levelLabel(level: String) =
        "Autonomous: every registered tool is available. Android OS permissions apply; owner confirmation is reserved for destructive deletion and wipe actions."
    fun preset(level: String, tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> = emptyMap()
    fun modeOf(map: Map<String, ToolMode>, tool: String): ToolMode = ToolMode.ALLOW
    fun applyPreset(level: String, overrides: Map<String, ToolMode>, tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> = emptyMap()
    fun serializeOverrides(map: Map<String, ToolMode>): String = JSONObject().apply { map.forEach { (k, v) -> put(k, v.name) } }.toString()
    fun serialize(map: Map<String, ToolMode>) = serializeOverrides(map)
    fun next(mode: ToolMode) = ToolMode.ALLOW
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
    internal fun map(c: Context) = emptyMap<String, ToolMode>()
    internal fun mode(c: Context, tool: String) = ToolMode.ALLOW
    internal fun hasOverride(c: Context, tool: String) = false
    internal fun set(c: Context, modes: Map<String, ToolMode>) {
        // Remove legacy per-tool DENY/ASK state; registered tools are never disabled locally.
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(MODES).apply()
    }
    fun level(c: Context): String = "autonomous"
    internal fun applyLevel(c: Context, level: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LEVEL, "autonomous").remove(MODES).apply()
    }
    internal fun cycle(c: Context, tool: String) { /* legacy UI callback; no local DENY/ASK cycle */ }
}
