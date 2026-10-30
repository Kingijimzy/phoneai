package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Owns the local Gaming & Hobbies capability: session-note history and focus reminders.
 * This executor does not tune FPS, Game Turbo, DND, root settings, or other apps.
 */
internal object OrionGamingCapabilityExecutor {
    private val supportedTools = setOf(
        "gaming_session_summary", "gaming_session_note_add", "gaming_focus_preset"
    )

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(tool: String, args: JSONObject, context: Context): String? {
        if (!supports(tool)) return null
        if (!AgentPolicy.enabled(context, "gaming_hobby")) {
            return "error: Gaming & Hobbies capability pack is disabled. Enable it in Orion Control Center first."
        }

        return when (tool) {
            "gaming_session_summary" -> {
                val summary = GameCoachStore.summaryText(context)
                if (!args.optBoolean("recent", false)) summary
                else {
                    val recent = GameCoachStore.all(context).take(12).joinToString("\n") {
                        "${it.game} · ${it.durationMinutes} min · ${it.result} · ${it.note}"
                    }
                    if (recent.isBlank()) summary else "$summary\n\nRecent notes:\n$recent"
                }
            }
            "gaming_session_note_add" -> {
                val saved = GameCoachStore.add(
                    context,
                    args.optString("game"),
                    args.optString("note"),
                    args.optInt("duration_minutes", 0),
                    args.optString("result")
                )
                if (saved) {
                    AgentPolicy.log(context, "gaming_hobby", "save-session-note", true, "local session note saved")
                    "Session note saved locally."
                } else "error: could not save note; provide a game and a non-empty note."
            }
            "gaming_focus_preset" -> GamingFocus.start(context, args.optInt("minutes", 0))
            else -> null
        }
    }
}
