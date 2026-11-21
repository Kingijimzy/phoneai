package com.phoneai.app

import android.content.Context

/**
 * Orion reports what it did on its own. After any unprompted run (initiative, mission, scheduled goal) the
 * final account (what it did, learned, built, decided and why) goes to the owner's private Telegram chat,
 * so he sees autonomous work without opening the app. Never throws.
 */
internal object OrionReports {
    private val idle = listOf("nothing is needed", "nothing needed", "nothing to do", "no action needed", "nothing worth")

    fun shouldReport(text: String): Boolean {
        val t = text.trim()
        if (t.length < 24) return false
        val lower = t.lowercase()
        return !(t.length < 140 && idle.any { it in lower })
    }

    fun format(kind: String, text: String, now: Long = System.currentTimeMillis()): String {
        val time = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.US).format(java.util.Date(now))
        return "Orion · $kind · $time\n\n" + text.trim().take(3500)
    }

    fun report(c: Context, kind: String, text: String) {
        runCatching {
            if (!shouldReport(text)) return
            val message = format(kind, text)
            // Keep the report visible in the app even when Telegram is not connected.
            runCatching { SoulEngineState.recordLesson(c, "owner_report", message, "reported") }
            OrionBehaviourLedger.record(c, "owner_report", kind, text, "reported", "Persisted to the local behaviour ledger")
            if (TelegramBridge.isConfigured(c)) TelegramBridge.send(c, message)
        }
    }
}
