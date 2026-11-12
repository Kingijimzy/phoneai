package com.phoneai.app

import android.content.Context

/** Compatibility value used by GatePolicy; Orion does not keep per-tool permission scopes. */
internal enum class ToolMode { ALLOW, ASK, DENY }

/** Legacy adapter kept for source compatibility. Every registered tool resolves to ALLOW. */
internal object ToolPermissionPolicy {
    val alwaysAllowed = setOf("ask_user", "wait", "time")
    val levels = listOf("autonomous")

    fun levelLabel(level: String) = "Direct execution: registered tools run without Orion per-tool permission scopes."
    fun preset(level: String, tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> = emptyMap()
    fun modeOf(map: Map<String, ToolMode>, tool: String): ToolMode = ToolMode.ALLOW
    fun applyPreset(level: String, overrides: Map<String, ToolMode>, tools: List<ToolDefinition> = ToolRegistry.all): Map<String, ToolMode> = emptyMap()
    fun serializeOverrides(map: Map<String, ToolMode>): String = "{}"
    fun next(mode: ToolMode): ToolMode = ToolMode.ALLOW
    fun serialize(map: Map<String, ToolMode>): String = "{}"
    fun parse(json: String?): Map<String, ToolMode> = emptyMap()
    fun denyMessage(tool: String) = "Tool '$tool' is not registered in Orion's tool registry."
}

object ToolPermissions {
    /** Old saved settings are ignored; Orion has no per-tool permission matrix. */
    internal fun map(c: Context): Map<String, ToolMode> = emptyMap()
    @Suppress("UNUSED_PARAMETER")
    internal fun mode(c: Context, tool: String): ToolMode = ToolMode.ALLOW
    @Suppress("UNUSED_PARAMETER")
    internal fun hasOverride(c: Context, tool: String): Boolean = false
    @Suppress("UNUSED_PARAMETER")
    internal fun set(c: Context, modes: Map<String, ToolMode>) = Unit
    /** Compatibility value: GatePolicy treats requested actions as autonomous by default. */
    @Suppress("UNUSED_PARAMETER")
    fun level(c: Context): String = "autonomous"
    @Suppress("UNUSED_PARAMETER")
    internal fun applyLevel(c: Context, level: String) = Unit
    @Suppress("UNUSED_PARAMETER")
    internal fun cycle(c: Context, tool: String) = Unit
}
