package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dedicated append-only store for shell command execution records.
 *
 * Every shell command (builtin, termux, linux) and its output are recorded here with
 * secret redaction applied by [AgentPrivacyFilter]. Commands are never stored in plaintext
 * when they contain API keys, passwords, tokens, emails, URLs or phone numbers.
 *
 * This is separate from the general [AgentPolicy.log] action log so shell history can be
 * queried and reviewed independently of other tool activity.
 */
internal object ShellCommandStore {
    private const val MAX_RECORDS = 200
    private const val KEY = "shell_command_store"

    private fun prefs(c: Context) = c.getSharedPreferences("phoneai_shell_commands", Context.MODE_PRIVATE)

    /** Record a shell command and its (redacted) output. Safe to call from any thread. */
    fun record(c: Context, backend: String, command: String, exitCode: Int?, output: String, verified: Boolean) {
        val row = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("backend", backend.take(40))
            .put("command", AgentPrivacyFilter.safeCommand(command).take(2000))
            .put("exitCode", exitCode)
            .put("verified", verified)
            .put("output", AgentPrivacyFilter.safeOutput(output).take(6000))

        synchronized(this) {
            val p = prefs(c)
            val old = runCatching { JSONArray(p.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val next = JSONArray()
            for (i in maxOf(0, old.length() - (MAX_RECORDS - 1)) until old.length()) {
                val row2 = runCatching { old.getJSONObject(i) }.getOrNull() ?: continue
                next.put(row2)
            }
            next.put(row)
            p.edit().putString(KEY, next.toString()).apply()
        }
    }

    /** Return the most recent shell command records, newest first. */
    fun recent(c: Context, limit: Int = 30): List<String> {
        val rows = runCatching { JSONArray(prefs(c).getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        return (maxOf(0, rows.length() - limit.coerceIn(1, 100)) until rows.length()).mapNotNull { i ->
            val n = rows.length() - 1 - i // newest first
            val row = runCatching { rows.optJSONObject(n) }.getOrNull() ?: return@mapNotNull null
            val at = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(row.optLong("at")))
            val backend = row.optString("backend", "?")
            val command = row.optString("command", "")
            val exit = row.optString("exitCode", "?")
            val verified = if (row.optBoolean("verified", false)) "verified" else "unverified"
            "$at · $backend · exit=$exit · $verified · $command"
        }
    }

    /** Number of stored shell command records. */
    fun count(c: Context): Int {
        return runCatching { JSONArray(prefs(c).getString(KEY, "[]") ?: "[]") }?.length() ?: 0
    }

    /** Clear all stored shell command records. */
    fun clear(c: Context) {
        prefs(c).edit().remove(KEY).apply()
    }
}
