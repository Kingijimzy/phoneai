package com.phoneai.app

import android.content.Context

/**
 * Everything about Orion's character that the owner can see and change: the character text, his own standing
 * rules, and each mood scene. Changes apply on the next turn. Defaults stay in code; only edits are stored.
 */
internal object OrionMindStore {
    private const val PREFS = "orion_mind"
    const val MAX_TEXT = 4_000

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun character(c: Context): String = sp(c).getString("character", null)?.takeIf { it.isNotBlank() } ?: OrionPersona.CHARACTER
    fun setCharacter(c: Context, text: String?) = put(c, "character", text)

    fun ownerRules(c: Context): String = sp(c).getString("rules", "").orEmpty()
    fun setOwnerRules(c: Context, text: String?) = put(c, "rules", text)

    fun sceneEnabled(c: Context, id: String): Boolean = sp(c).getBoolean("scene_on_$id", true)
    fun setSceneEnabled(c: Context, id: String, on: Boolean) {
        sp(c).edit().putBoolean("scene_on_$id", on).apply()
        sync(c)
    }

    fun sceneBrief(c: Context, id: String): String? = sp(c).getString("scene_brief_$id", null)?.takeIf { it.isNotBlank() }
    fun setSceneBrief(c: Context, id: String, text: String?) {
        put(c, "scene_brief_$id", text)
        sync(c)
    }

    /** The block the system prompt carries for the owner's own rules; empty when none. */
    fun rulesBlock(c: Context): String = ownerRules(c).trim().takeIf { it.isNotEmpty() }
        ?.let { "OWNER'S OWN RULES (his words, highest priority after safety):\n$it\n" }.orEmpty()

    /** Pushes the stored scene choices into the detector, which stays pure and context-free. */
    fun sync(c: Context) {
        OrionScenes.disabled = OrionScenes.all().map { it.id }.filterNot { sceneEnabled(c, it) }.toSet()
        OrionScenes.briefOverrides = OrionScenes.all().mapNotNull { s -> sceneBrief(c, s.id)?.let { s.id to it } }.toMap()
    }

    private fun put(c: Context, key: String, text: String?) {
        val value = text?.trim().orEmpty().take(MAX_TEXT)
        sp(c).edit().apply { if (value.isBlank()) remove(key) else putString(key, value) }.apply()
    }
}
