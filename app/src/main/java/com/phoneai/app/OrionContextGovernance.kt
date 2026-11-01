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

    fun read(context: Context, scope: OrionSensorScope, now: Long = System.currentTimeMillis()): OrionSensorGrant {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val grant = OrionSensorGrant(
            scope, p.getBoolean("${scope.name}_enabled", false),
            p.getLong("${scope.name}_granted_at", 0L),
            p.getLong("${scope.name}_expires_at", 0L),
            p.getBoolean("${scope.name}_foreground_only", true)
        )
        if (grant.enabled && !grant.active(now)) revoke(context, scope)
        return grant.copy(enabled = grant.active(now))
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
    fun capabilities(providerId: String, modelId: String, transport: String = "chat"): OrionModelCapabilities {
        val p = providerId.lowercase()
        val m = modelId.lowercase()
        val isGeminiLive = transport == "gemini_live" && p == "gemini" && m.contains("live")
        // These flags describe the selected request route, not broad model-family marketing.
        // Chat-completions routes support images only for known multimodal families. Audio/video
        // are exposed only on the dedicated Gemini Live transport.
        val vision = transport == "chat" && (
            (p == "gemini" && m.startsWith("gemini-")) ||
            m.startsWith("claude-") ||
            m.contains("gpt-4o") || m.contains("gpt-4.1") ||
            m.contains("llama-4") || m.contains("gemma-3") ||
            m.contains("pixtral") || m.contains("llava") || m.contains("-vl") ||
            m.contains("vila") || m.contains("paligemma") ||
            m.contains("nemotron-3.5-super-vl") || m.contains("qwen2.5-vl") ||
            m.contains("qwen-vl")
        )
        val context = when {
            m.contains("gemini-2.5") || m.contains("gemini-3") ||
                m.contains("gemini-flash-latest") || m.contains("gemini-flash-lite-latest") -> 1_000_000
            m.startsWith("claude-") -> 200_000
            m.contains("gpt-4.1") -> 1_047_576
            m.contains("gpt-4o") -> 128_000
            m.contains("llama-4") -> 128_000
            m.contains("nemotron-3.5") -> 128_000
            m.contains("deepseek-v3") -> 64_000
            m.contains("llama-3.3") -> 128_000
            p == "local" -> 8_192
            else -> 16_384
        }
        val output = when {
            m.contains("gemini") || m.startsWith("claude-") -> 8_192
            m.contains("gpt-4.1") || m.contains("gpt-4o") -> 4_096
            else -> 2_048
        }
        return OrionModelCapabilities(
            context, output, vision,
            audioInput = isGeminiLive,
            audioOutput = isGeminiLive,
            videoInput = isGeminiLive
        )
    }

    fun inputBudget(capabilities: OrionModelCapabilities, requestedOutput: Int): Int =
        (capabilities.contextTokens - requestedOutput.coerceIn(256, capabilities.maxOutputTokens) - 1_024)
            .coerceAtLeast(1_024)

    private fun messageCost(item: JSONObject): Int {
        val content = item.opt("content")
        val tokens = when (content) {
            is String -> ContextWindowBudget.estimateTokens(content)
            is JSONArray -> {
                var total = 0
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i)
                    if (part == null) {
                        total += ContextWindowBudget.estimateTokens(content.opt(i).toString())
                    } else when (part.optString("type")) {
                        "text" -> total += ContextWindowBudget.estimateTokens(part.optString("text"))
                        "image_url", "input_image" -> total += 1_024
                        else -> total += ContextWindowBudget.estimateTokens(part.toString())
                    }
                }
                total
            }
            else -> ContextWindowBudget.estimateTokens(content?.toString().orEmpty())
        }
        return tokens + 12
    }

    private fun clipped(item: JSONObject, maxTokens: Int): JSONObject {
        val copy = JSONObject(item.toString())
        val content = copy.opt("content")
        val maxChars = maxTokens.coerceAtLeast(1) * 4
        when (content) {
            is String -> copy.put("content", content.take(maxChars))
            is JSONArray -> {
                val out = JSONArray()
                var remaining = maxTokens
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i)
                    if (part == null) continue
                    when (part.optString("type")) {
                        "text" -> {
                            if (remaining <= 0) continue
                            val text = part.optString("text")
                            val keep = minOf(text.length, remaining * 4)
                            out.put(JSONObject(part.toString()).put("text", text.take(keep)))
                            remaining -= ContextWindowBudget.estimateTokens(text.take(keep))
                        }
                        "image_url", "input_image" -> {
                            if (remaining >= 1_024) {
                                out.put(JSONObject(part.toString()))
                                remaining -= 1_024
                            }
                        }
                        else -> if (remaining > 0) {
                            out.put(JSONObject(part.toString()))
                            remaining -= ContextWindowBudget.estimateTokens(part.toString())
                        }
                    }
                }
                copy.put("content", out)
            }
        }
        return copy
    }

    /** Keep the complete instruction prefix where possible and always preserve the newest user turn. */
    fun fitMessages(
        messages: JSONArray,
        capabilities: OrionModelCapabilities,
        requestedOutput: Int
    ): JSONArray {
        val budget = inputBudget(capabilities, requestedOutput)
        val system = mutableListOf<JSONObject>()
        val conversation = mutableListOf<JSONObject>()
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i)?.let { JSONObject(it.toString()) } ?: continue
            if (item.optString("role") in setOf("system", "developer")) system.add(item)
            else conversation.add(item)
        }

        val selected = mutableListOf<JSONObject>()
        var used = 0
        var systemRemaining = (budget * 45 / 100).coerceAtLeast(512)
        system.forEach { item ->
            if (systemRemaining <= 0) return@forEach
            val cost = messageCost(item)
            val keep = if (cost <= systemRemaining) item else clipped(item, systemRemaining - 12)
            val keptCost = messageCost(keep)
            if (keptCost <= systemRemaining && keptCost > 0) {
                selected.add(keep)
                used += keptCost
                systemRemaining -= keptCost
            }
        }

        var remaining = (budget - used).coerceAtLeast(1)
        val newestUser = conversation.indexOfLast { it.optString("role") == "user" }
        val retained = mutableListOf<Pair<Int, JSONObject>>()
        for (index in conversation.indices.reversed()) {
            val item = conversation[index]
            val cost = messageCost(item)
            if (cost <= remaining) {
                retained.add(index to item)
                remaining -= cost
            } else if (index == newestUser && remaining > 24) {
                val keep = clipped(item, remaining - 12)
                retained.add(index to keep)
                remaining -= messageCost(keep).coerceAtMost(remaining)
            }
            if (remaining <= 24) break
        }
        selected.addAll(retained.sortedBy { it.first }.map { it.second })
        return JSONArray().also { out -> selected.forEach { out.put(it) } }
    }
}
