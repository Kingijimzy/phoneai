package com.phoneai.app

import android.content.Context

/**
 * Workspace Matrix: adaptive working modes. A mode only changes which capabilities and presentation
 * hints the one Orion emphasises. It never creates a separate memory or identity, and it never
 * grants permissions or bypasses the shared gates.
 */
enum class OrionWorkMode(val label: String, val keywords: List<String>, val focus: String, val rules: String) {
    GENERAL("General", emptyList(), "balanced assistance", ""),
    CODING("Coding", listOf("code", "coding", "kotlin", "python", "gradle", "build", "bug", "repo", "compile", "test suite"),
        "project workspace, terminal, coding agent, test runner, diagnostics",
        "Inspect the workspace first, run tests after changes, report build and test results as done, failed or not verified."),
    TRADING("Trading", listOf("trade", "trading", "chart", "bybit", "crypto", "market", "position", "stop loss"),
        "charts, market research, calendar and the owner's analysis tools",
        "Analysis only: never place, modify or cancel a trade without explicit per-trade owner approval; respect the risk gate and kill switch."),
    RESEARCH("Research", listOf("research", "compare", "sources", "investigate", "look up", "find out"),
        "web search, fetching sources, notes and citations", "Date and cite every claim; separate verified facts from uncertainty."),
    GAMING("Gaming", listOf("game", "gaming", "fc mobile", "match", "fps"),
        "low-interruption notifications, performance tips within Android's controls", "Keep replies short and avoid proactive interruptions while a game is active."),
    CREATIVE("Creative", listOf("design", "draw", "story", "write a poem", "logo", "creative"),
        "drafting, documents and visual tools", "Offer options, then refine on feedback."),
    LEARNING("Learning", listOf("learn", "explain", "teach me", "study", "quiz", "course"),
        "explanations, practice questions, notes", "Explain step by step, check understanding."),
    COMMUNICATION("Communication", listOf("email", "reply to", "message", "call", "whatsapp", "telegram", "sms"),
        "messages, calendar, contacts, drafts", "Draft first; sending needs the existing approvals."),
    CHILL("Chill", listOf("relax", "chill", "movie", "cinema", "music", "unwind"),
        "media, music and quiet presentation", "Be light and brief; no proactive messages unless urgent.");

    companion object {
        fun parse(raw: String?): OrionWorkMode = values().firstOrNull { it.name.equals(raw?.trim(), true) } ?: GENERAL
    }
}

object OrionWorkspaceMatrix {
    private const val PREFS = "orion_workspace_matrix"
    private const val KEY = "mode"

    /** Best-guess mode from a request; GENERAL when nothing stands out. Pure and deterministic. */
    fun detect(request: String): OrionWorkMode {
        val q = request.lowercase()
        var best = OrionWorkMode.GENERAL
        var bestScore = 0
        for (m in OrionWorkMode.values()) {
            val score = m.keywords.count { q.contains(it) }
            if (score > bestScore) { best = m; bestScore = score }
        }
        return best
    }

    fun current(c: Context): OrionWorkMode =
        OrionWorkMode.parse(runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) }.getOrNull())

    fun set(c: Context, mode: OrionWorkMode) {
        runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply() }
    }

    /** Prompt guidance for the active mode. Empty for GENERAL so ordinary turns stay light. */
    fun promptBlock(mode: OrionWorkMode): String =
        if (mode == OrionWorkMode.GENERAL) "" else
            "WORKSPACE MODE: ${mode.label}. Emphasise: ${mode.focus}. ${mode.rules} " +
                "This changes emphasis only; memory, identity and safety rules are unchanged."
}
