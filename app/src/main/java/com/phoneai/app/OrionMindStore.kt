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
    // Notes Orion wrote for himself (visible and editable on the mind page)
    fun selfNotes(c: Context): String = sp(c).getString("selfnotes", "").orEmpty()
    fun setSelfNotes(c: Context, text: String?) = put(c, "selfnotes", text)
    fun addSelfNote(c: Context, note: String): String {
        val n = note.trim().replace('\n', ' ').take(240)
        if (n.length < 6) return "error: note is too short"
        if (OrionActionLedger.looksSecret(n)) return "error: that looks like a secret; notes never hold secrets"
        val lines = selfNotes(c).lines().filter { it.isNotBlank() }
        if (lines.any { it.equals(n, true) }) return "ok: already noted"
        setSelfNotes(c, (lines + n).takeLast(20).joinToString("\n"))
        return "ok: noted for myself, Kingi can see and edit it on the mind page"
    }

    // Scenes created at runtime
    fun customScenes(c: Context): List<org.json.JSONObject> = runCatching {
        org.json.JSONArray(sp(c).getString("custom_scenes", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    }.getOrDefault(emptyList())

    fun addCustomScene(c: Context, id: String, words: String, tools: String, brief: String): String {
        val scene = OrionScenes.makeScene(id, words, tools, brief) ?: return "error: a scene needs an id, 1-8 trigger phrases (3+ letters, comma separated) and a brief"
        if (scene.id in OrionScenes.builtInIds()) return "error: ${scene.id} is a built-in scene; pick another id, or edit that one on the mind page"
        val list = customScenes(c).filterNot { it.optString("id") == scene.id } + org.json.JSONObject()
            .put("id", scene.id).put("words", words.trim()).put("tools", tools.trim()).put("brief", scene.brief)
        sp(c).edit().putString("custom_scenes", org.json.JSONArray(list.takeLast(12)).toString()).apply()
        sync(c)
        return "ok: scene ${scene.id} created; it fires when Kingi says: $words"
    }

    fun removeCustomScene(c: Context, id: String): String {
        val kept = customScenes(c).filterNot { it.optString("id") == id }
        sp(c).edit().putString("custom_scenes", org.json.JSONArray(kept).toString()).apply()
        sync(c)
        return "ok: scene $id removed"
    }

    fun rulesBlock(c: Context): String = (ownerRules(c).trim().takeIf { it.isNotEmpty() }
        ?.let { "OWNER'S OWN RULES (his words, highest priority after safety):\n$it\n" }.orEmpty()) + OrionCorrections.block(c) +
        selfNotes(c).lines().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { "HIS OWN STANDING NOTES (he wrote these for himself):\n" + it.joinToString("\n") { l -> "- $l" } + "\n" }.orEmpty()

    /** Pushes the stored scene choices into the detector, which stays pure and context-free. */
    fun sync(c: Context) {
        OrionScenes.custom = customScenes(c).mapNotNull {
            OrionScenes.makeScene(it.optString("id"), it.optString("words"), it.optString("tools"), it.optString("brief"))
        }
        OrionScenes.disabled = OrionScenes.all().map { it.id }.filterNot { sceneEnabled(c, it) }.toSet()
        OrionScenes.briefOverrides = OrionScenes.all().mapNotNull { s -> sceneBrief(c, s.id)?.let { s.id to it } }.toMap()
    }

    private fun put(c: Context, key: String, text: String?) {
        val value = text?.trim().orEmpty().take(MAX_TEXT)
        sp(c).edit().apply { if (value.isBlank()) remove(key) else putString(key, value) }.apply()
    }
}
