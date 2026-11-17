package com.phoneai.app

import android.content.Context
import java.io.File

/**
 * The one persisted project selection shared by chat tools, Studio, the editor,
 * terminal and Dev Agent. Paths are workspace-relative; blank means /workspace.
 */
object OrionProjectContext {
    private const val PREFS = "orion_project_context"
    private const val KEY_ACTIVE = "active_project"
    private const val LEGACY_PREFS = "studio_state"
    private const val LEGACY_KEY = "project"

    @JvmStatic
    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ACTIVE)) return normalize(prefs.getString(KEY_ACTIVE, "").orEmpty())
        val legacy = context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_KEY, "").orEmpty()
        val migrated = normalize(legacy)
        prefs.edit().putString(KEY_ACTIVE, migrated).apply()
        return migrated
    }

    @JvmStatic
    fun set(context: Context, project: String): String {
        val normalized = normalize(project)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE, normalized).apply()
        // Keep the old preference readable for older app components during migration.
        context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit().putString(LEGACY_KEY, normalized).apply()
        return normalized
    }

    @JvmStatic
    fun root(context: Context): File {
        val workspace = ShellRunner.workspace(context)
        val active = get(context)
        return if (active.isBlank()) workspace else File(workspace, active)
    }

    private fun normalize(value: String): String =
        value.trim().replace('\\', '/').trim('/')
}
