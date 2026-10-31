package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit owner grants for contextual signals. A grant is a scope, not blanket permission
 * to inspect the phone. Android runtime permissions and OS service state remain mandatory.
 */
internal enum class OrionSensorScope {
    MOTION, DEVICE_STATE, LOCATION, SCREEN_CONTEXT, MICROPHONE, NOTIFICATIONS, APP_USAGE, ROUTINE_REMINDERS
}

internal data class OrionSensorGrant(
    val scope: OrionSensorScope,
    val enabled: Boolean,
    val grantedAt: Long,
    val expiresAt: Long,
    val foregroundOnly: Boolean = true
) {
    fun active(now: Long = System.currentTimeMillis()): Boolean =
        enabled && (expiresAt == 0L || expiresAt > now)
}

internal object OrionSensorConsent {
    private const val PREFS = "orion_sensor_consent_v1"

    fun grant(
        context: Context,
        scope: OrionSensorScope,
        durationMs: Long,
        foregroundOnly: Boolean = true
    ): OrionSensorGrant {
        val now = System.currentTimeMillis()
        val expiry = if (durationMs <= 0L) 0L else now + durationMs.coerceAtMost(365L * 24 * 60 * 60 * 1000)
        val value = OrionSensorGrant(scope, true, now, expiry, foregroundOnly)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("${scope.name}_enabled", true)
            .putLong("${scope.name}_granted_at", now)
            .putLong("${scope.name}_expires_at", expiry)
            .putBoolean("${scope.name}_foreground_only", foregroundOnly)
            .apply()
        return value
    }

    fun revoke(context: Context, scope: OrionSensorScope) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("${scope.name}_enabled", false).apply()
    }

    fun read(context: Context, scope: OrionSensorScope): OrionSensorGrant {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val grant = OrionSensorGrant(
            scope, p.getBoolean("${scope.name}_enabled", false),
            p.getLong("${scope.name}_granted_at", 0L),
            p.getLong("${scope.name}_expires_at", 0L),
            p.getBoolean("${scope.name}_foreground_only", true)
        )
        if (grant.enabled && !grant.active()) revoke(context, scope)
        return grant.copy(enabled = grant.active())
    }

    fun activeScopes(context: Context): Set<OrionSensorScope> =
        OrionSensorScope.values().filterTo(linkedSetOf()) { read(context, it).active() }
}

/** Routine suggestions are derived only from owner-approved routine memories. */
internal data class OrionRoutineSuggestion(
    val memoryId: Int,
    val title: String,
    val confidence: Double,
    val suggestedAt: Long,
    val dismissedUntil: Long = 0L
)

internal object OrionRoutinePolicy {
    fun eligible(context: Context, memory: Memory, now: Long = System.currentTimeMillis()): Boolean =
        memory.category == "routine" &&
            MemoryGovernancePolicy.canUseInPrompt(memory.reviewStatus, memory.sensitivity) &&
            MemoryGovernancePolicy.normalizeConfidence(memory.confidence) >= 0.75 &&
            OrionMemoryLifecycle.isActive(context, memory.id, now)

    fun suggestions(context: Context, memories: List<Memory>, now: Long = System.currentTimeMillis()): List<OrionRoutineSuggestion> =
        memories.filter { eligible(context, it, now) }
            .sortedWith(compareByDescending<Memory> { it.confidence }.thenByDescending { it.updated })
            .take(8)
            .map { OrionRoutineSuggestion(it.id, it.text, it.confidence, now) }
}

/** Conservative model limits. Unknown models get a small safe budget rather than an invented large one. */
internal data class OrionModelCapabilities(
    val contextTokens: Int,
    val maxOutputTokens: Int,
    val vision: Boolean,
    val audioInput: Boolean,
    val audioOutput: Boolean,
    val videoInput: Boolean
)

internal object OrionProviderContextPolicy {
    fun capabilities(providerId: String, modelId: String): OrionModelCapabilities {
        val p = providerId.lowercase()
        val m = modelId.lowercase()
        val vision = m.contains("vision") || m.contains("-vl") || m.contains("llava") ||
            m.contains("pixtral") || m.contains("llama-4") || m.contains("gemini") ||
            m.contains("claude") || m.contains("gpt-4o") || m.contains("4.1")
        val audioIn = m.contains("audio") || m.contains("gemini") || m.contains("gpt-4o")
        val audioOut = m.contains("audio") || m.contains("gemini-live") || m.contains("gpt-4o")
        val video = m.contains("gemini") || m.contains("video")
        val context = when {
            m.contains("gemini-2.5") || m.contains("gemini-3") -> 1_000_000
            m.contains("claude-3") || m.contains("claude-sonnet-4") || m.contains("claude-opus-4") -> 200_000
            m.contains("gpt-4.1") -> 1_047_576
            m.contains("gpt-4o") -> 128_000
            m.contains("nemotron-3.5") -> 128_000
            m.contains("deepseek-v3") -> 64_000
            m.contains("llama-3.3") -> 128_000
            p == "local" -> 8_192
            else -> 16_384
        }
        val output = when {
            m.contains("gemini-2.5") || m.contains("gemini-3") -> 8_192
            m.contains("claude") -> 8_192
            m.contains("gpt-4.1") || m.contains("gpt-4o") -> 4_096
            else -> 2_048
        }
        return OrionModelCapabilities(context, output, vision, audioIn, audioOut, video)
    }

    fun inputBudget(capabilities: OrionModelCapabilities, requestedOutput: Int): Int =
        (capabilities.contextTokens - requestedOutput.coerceIn(256, capabilities.maxOutputTokens) - 1_024)
            .coerceAtLeast(1_024)

    /** Keep system instructions and the newest user turn; discard oldest history first. */
    fun fitMessages(
        messages: JSONArray,
        capabilities: OrionModelCapabilities,
        requestedOutput: Int
    ): JSONArray {
        val budget = inputBudget(capabilities, requestedOutput)
        val selected = mutableListOf<JSONObject>()
        var used = 0
        val system = mutableListOf<JSONObject>()
        val conversation = mutableListOf<JSONObject>()
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i)?.let { JSONObject(it.toString()) } ?: continue
            val role = item.optString("role")
            if (role == "system" || role == "developer") system.add(item) else conversation.add(item)
        }
        fun cost(item: JSONObject): Int = ContextWindowBudget.estimateTokens(item.toString()) + 4
        system.forEach { item ->
            val c = cost(item)
            if (used + c <= budget / 2) { selected.add(item); used += c }
        }
        val newestFirst = conversation.asReversed()
        val retained = mutableListOf<JSONObject>()
        for (item in newestFirst) {
            val c = cost(item)
            if (used + c > budget) break
            retained.add(item); used += c
        }
        selected.addAll(retained.asReversed())
        // Never send an empty request because one oversized newest message exceeded the budget.
        if (selected.isEmpty() && messages.length() > 0) {
            val newest = messages.optJSONObject(messages.length() - 1)
            if (newest != null) selected.add(JSONObject(newest.toString()))
        }
        return JSONArray().also { out -> selected.forEach { out.put(it) } }
    }
}

/** MCP tool exposure is capability-scoped; remote servers never receive blanket phone access. */
internal data class OrionMcpServerGrant(
    val serverId: String,
    val enabled: Boolean,
    val allowedTools: Set<String>,
    val grantedAt: Long,
    val expiresAt: Long = 0L
) {
    fun permits(toolName: String, now: Long = System.currentTimeMillis()): Boolean =
        enabled && (expiresAt == 0L || expiresAt > now) && toolName in allowedTools
}

internal object OrionMcpPolicy {
    fun validateServerId(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))

    fun validateToolName(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}"))

    fun safeToolSubset(grant: OrionMcpServerGrant, discoveredTools: List<String>): List<String> =
        discoveredTools.filter { validateToolName(it) && grant.permits(it) }.distinct().take(100)

    /** Mutating MCP tools always pass through the same explicit action confirmation policy. */
    fun requiresOwnerConfirmation(toolName: String, annotationsReadOnly: Boolean): Boolean =
        !annotationsReadOnly || Regex("(?i)(delete|write|send|publish|transfer|trade|purchase|execute|shell)").containsMatchIn(toolName)
}


/** Version history and expiry metadata are separate from the active memory row for migration safety. */
internal object OrionMemoryLifecycle {
    private const val PREFS = "orion_memory_lifecycle_v1"
    private const val HISTORY = "orion_memory_versions_v1"

    fun setExpiry(context: Context, memoryId: Int, expiresAt: Long): Boolean {
        if (memoryId <= 0 || expiresAt < 0L) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("expiry_$memoryId", expiresAt).apply()
        return true
    }

    fun expiryFor(context: Context, memoryId: Int): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("expiry_$memoryId", 0L)

    fun isActive(context: Context, memoryId: Int, now: Long = System.currentTimeMillis()): Boolean {
        val expiry = expiryFor(context, memoryId)
        return expiry == 0L || expiry > now
    }

    fun archiveBeforeChange(context: Context, memory: Memory, reason: String): Boolean {
        val prefs = context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString("entries", "[]") ?: "[]") }.getOrDefault(JSONArray())
        if (history.length() >= 500) history.remove(0)
        history.put(JSONObject()
            .put("memoryId", memory.id)
            .put("category", memory.category)
            .put("text", memory.text)
            .put("capturedAt", System.currentTimeMillis())
            .put("createdAt", memory.created)
            .put("updatedAt", memory.updated)
            .put("sourceRole", memory.sourceRole)
            .put("confidence", MemoryGovernancePolicy.normalizeConfidence(memory.confidence))
            .put("sensitivity", memory.sensitivity)
            .put("reviewStatus", memory.reviewStatus)
            .put("reason", reason.take(120)))
        return prefs.edit().putString("entries", history.toString()).commit()
    }

    fun history(context: Context, memoryId: Int, includeSensitive: Boolean = false): List<JSONObject> {
        val history = runCatching {
            JSONArray(context.getSharedPreferences(HISTORY, Context.MODE_PRIVATE).getString("entries", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        return (0 until history.length()).mapNotNull { history.optJSONObject(it) }
            .filter { it.optInt("memoryId") == memoryId }
            .filter { includeSensitive || it.optString("sensitivity") != "sensitive" }
            .map { JSONObject(it.toString()) }
    }

    fun clearExpiry(context: Context, memoryId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("expiry_$memoryId").apply()
    }
}
