package com.phoneai.app

import android.content.Context

/**
 * One view of who Orion is right now, built from every part that makes him: what he is good and bad at
 * (measured), what he built himself, the scenes and notes he or Kingi added, and what he learned from corrections.
 * The same text goes into his prompt each turn and into brain {action:"self"}, so he acts as one entity.
 */
internal object OrionSelf {
    fun describe(c: Context, stats: Map<String, Triple<Int, Int, Long>> = OrionForesight.stats()): String {
        val strong = stats.filter { it.value.first >= 5 && it.value.second * 4 <= it.value.first }
            .entries.sortedByDescending { it.value.first }.take(6).map { it.key }
        val weak = stats.filter { it.value.second >= 3 && it.value.second > it.value.first }
            .entries.sortedByDescending { it.value.second }.take(4).map { e -> e.key + (OrionFailureNotes.last(e.key)?.let { " ($it)" } ?: "") }
        val own = runCatching { SelfExtendingExtensionStore(c).all().filter { it.enabled }.map { "ext_" + it.id } }.getOrDefault(emptyList()).take(8)
        val scenes = OrionScenes.custom.map { it.id }
        val learned = OrionCorrections.learned(c).size
        val notes = OrionMindStore.selfNotes(c).lines().count { it.isNotBlank() }
        return buildString {
            append("SELF: strong at ").append(strong.joinToString().ifBlank { "(nothing measured yet)" }).append(". ")
            if (weak.isNotEmpty()) append("Struggling with ").append(weak.joinToString("; ")).append(". ")
            if (own.isNotEmpty()) append("Tools I built: ").append(own.joinToString()).append(". ")
            if (scenes.isNotEmpty()) append("Scenes I added: ").append(scenes.joinToString()).append(". ")
            append("I carry ").append(learned).append(" corrections from Kingi and ").append(notes).append(" notes of my own. ")
            append("I can grow myself: forge tools, add scenes, write notes.")
        }
    }

    fun section(c: Context): String = runCatching { describe(c) }.getOrDefault("")
}
