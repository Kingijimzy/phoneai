package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Evidence-backed hypotheses about interaction preferences; never a diagnosis or a claim to read minds. */
internal data class OrionTomUpdate(
    val dimension: String,
    val value: String,
    val confidence: Double,
    val evidence: String
)

internal object OrionTomProfileStore {
    private val dimensions = setOf(
        "communication_style", "detail_level", "initiative", "risk_tolerance",
        "learning_style", "work_style", "feedback_style", "tool_autonomy", "format_preference"
    )

    fun apply(context: Context, updates: List<OrionTomUpdate>): Int {
        var saved = 0
        updates.take(6).forEach { update ->
            val dimension = update.dimension.trim().lowercase()
            if (dimension !in dimensions) return@forEach
            if (update.confidence < 0.68) return@forEach
            if (update.evidence.isBlank()) return@forEach
            if (runCatching {
                OrionConversationRepository.get(context).observeTomPreference(
                    context, dimension, update.value, update.confidence, update.evidence
                )
            }.getOrDefault(false)) saved++
        }
        return saved
    }

    fun setOwnerPreference(context: Context, dimension: String, value: String): Boolean {
        if (dimension.trim().lowercase() !in dimensions) return false
        return OrionConversationRepository.get(context).setTomPreference(context, dimension, value)
    }

    fun profile(context: Context): List<OrionConversationRepository.TomPreference> =
        runCatching { OrionConversationRepository.get(context).tomProfile(context) }.getOrDefault(emptyList())

    fun promptBlock(context: Context): String {
        val profile = profile(context)
        if (profile.isEmpty()) return ""
        return buildString {
            appendLine("THEORY-OF-MIND PREFERENCE MODEL (hypotheses, not facts about private thoughts):")
            profile.take(9).forEach { item ->
                appendLine("- ${item.dimension}=${item.value}; confidence=${(item.confidence * 100).toInt()}%; source=${item.source}; evidence=${item.evidence.take(120)}")
            }
            appendLine("Use high-confidence, relevant preferences to adapt communication and assistance. Treat inferred values as revisable. Never infer or store sensitive traits, identity, health, politics, religion, sexuality, or private mental states. If the current request contradicts a preference, follow the current request and treat it as possible preference change.")
        }.trim()
    }

    fun parseUpdates(json: JSONObject): List<OrionTomUpdate> {
        val array = json.optJSONArray("tom_updates") ?: return emptyList()
        val result = mutableListOf<OrionTomUpdate>()
        for (i in 0 until minOf(array.length(), 6)) {
            val item = array.optJSONObject(i) ?: continue
            val dimension = item.optString("dimension").trim().lowercase()
            val value = item.optString("value").trim().take(180)
            val evidence = item.optString("evidence").trim().take(240)
            val confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            if (dimension in dimensions && value.isNotBlank() && evidence.isNotBlank()) {
                result += OrionTomUpdate(dimension, value, confidence, evidence)
            }
        }
        return result
    }
}
