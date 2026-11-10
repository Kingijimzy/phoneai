package com.phoneai.app

import android.content.Context

/**
 * Legacy compatibility facade. Orion no longer has global or per-tool ALLOW/ASK/DENY modes.
 * Real Android runtime permissions and the two domain-specific confirmation flows are enforced
 * at their owning executors. These methods intentionally resolve to ALLOW for every registered tool.
 */
internal enum class ToolMode { ALLOW, ASK, DENY }

internal object ToolPermissionPolicy {
    val alwaysAllowed = setOf("ask_user", "wait", "time")
    val levels = listOf("partner")

    fun levelLabel(level: String) = "Partner operation: tools run without generic permission prompts"
    fun preset(level: String, tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> =
        tools.associate { it.name to ToolMode.ALLOW }
    fun modeOf(map: Map<String, ToolMode>, tool: String): ToolMode = ToolMode.ALLOW
    fun effectiveMode(level: String, overrides: Map<String, ToolMode>, tool: String,
                      tools: List<ToolDefinition> = ToolRegistry.all): ToolMode = ToolMode.ALLOW
    fun applyPreset(level: String, overrides: Map<String, ToolMode>,
                    tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> =
        tools.associate { it.name to ToolMode.ALLOW }
    fun serializeOverrides(map: Map<String, ToolMode>): String = "{}"
    fun next(m: ToolMode): ToolMode = ToolMode.ALLOW
    fun serialize(map: Map<String, ToolMode>): String = "{}"
    fun parse(json: String?): Map<String, ToolMode> = emptyMap()
    fun denyMessage(tool: String) = "error: tool '$tool' is not registered."
}

object ToolPermissions {
    internal fun map(c: Context): Map<String, ToolMode> = emptyMap()
    internal fun mode(c: Context, tool: String): ToolMode = ToolMode.ALLOW
    internal fun hasOverride(c: Context, tool: String): Boolean = false
    internal fun set(c: Context, m: Map<String, ToolMode>) = Unit
    fun level(c: Context): String = "partner"
    internal fun applyLevel(c: Context, level: String) = Unit
    internal fun cycle(c: Context, tool: String) = Unit
}
