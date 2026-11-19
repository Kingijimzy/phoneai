package com.phoneai.app

import android.content.Context
import android.provider.Settings

/**
 * Whether Orion's keyboard is enabled / selected. Android 14+ can deny or truncate the underlying lookups
 * (SecurityException, null lists, incomplete component names), so any failure means "unknown = not enabled",
 * never a crash on the Settings screen.
 */
internal object KeyboardStatus {
    fun enabled(packageName: String, enabledPackages: () -> List<String?>?): Boolean = try {
        enabledPackages()?.any { it?.equals(packageName, ignoreCase = true) == true } == true
    } catch (_: Exception) { false }

    fun selected(packageName: String, defaultIme: () -> String?): Boolean = try {
        defaultIme()?.substringBefore('/')?.equals(packageName, ignoreCase = true) == true
    } catch (_: Exception) { false }

    /** Live default IME id, e.g. "com.phoneai.app/com.phoneai.app.OrionKeyboardService". Never throws. */
    fun selectedIme(context: Context): String? = try {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
    } catch (_: Exception) { null }

    /** IME ids Android currently has enabled, or null when the lookup is unavailable. */
    fun enabledImes(context: Context): List<String?>? = try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.enabledInputMethodList?.map { it.id }
    } catch (_: Exception) { null }

    /** IME ids installed and displayable for the target package, or null when the lookup is unavailable. */
    fun queryImes(context: Context): List<String?>? = try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.inputMethodList?.map { it.id }
    } catch (_: Exception) { null }

    /**
     * Pure: an IME id is only usable when it is both selected and present in the enabled list.
     * Android silently drops Secure.CURRENT_INPUT_METHOD writes, so a stale "selected" string is
     * common and must never be trusted on its own.
     */
    fun usable(selectedIme: String?, enabledImes: List<String?>?): Boolean {
        if (selectedIme.isNullOrBlank()) return false
        return enabledImes?.any { it.equals(selectedIme, ignoreCase = true) } == true
    }

    /** True only when Orion's own keyboard is genuinely the active, enabled IME. */
    fun isOrionImeSelected(context: Context): Boolean {
        val current = selectedIme(context) ?: return false
        if (!selected(context.packageName) { current }) return false
        return usable(current, enabledImes(context))
    }
}
