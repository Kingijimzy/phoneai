package com.phoneai.app

/**
 * Validates tool results against the user objective (reset Rule 5).
 * Rejects "ok" that does not match the requested outcome so the brain can recover.
 */
internal object OrionToolOutcomeVerifier {

    data class Verdict(
        val accept: Boolean,
        val reason: String,
        val suggestAlternate: String? = null
    )

    fun verify(userGoal: String, tool: String, result: String): Verdict {
        val goal = userGoal.trim()
        val res = result.trim()
        if (res.isBlank()) {
            return Verdict(false, "empty tool result", "retry_with_different_args")
        }
        if (res.startsWith("error", ignoreCase = true) || res.startsWith("blocked", ignoreCase = true)) {
            return Verdict(false, res.take(240), alternateFor(tool))
        }

        val g = goal.lowercase()
        val r = res.lowercase()

        // Open-app / music goals must not be satisfied by a web search fallback.
        if (Regex("\\b(open|launch)\\b").containsMatchIn(g) &&
            Regex("\\b(music|spotify|youtube music)\\b").containsMatchIn(g) &&
            !Regex("\\b(play|search)\\b").containsMatchIn(g)
        ) {
            if (r.contains("youtube results") || r.contains("http") || r.contains("www.youtube.com")) {
                return Verdict(
                    false,
                    "result opened web YouTube instead of a native music app",
                    "open_app:YouTube Music or Spotify"
                )
            }
            if (r.contains("opened") && (r.contains("music") || r.contains("spotify") || r.contains("youtube music"))) {
                return Verdict(true, "native music app open acknowledged")
            }
        }

        // Generic success markers
        if (r.startsWith("ok") || r.contains("opened") || r.contains("playing") ||
            r.contains("saved") || r.contains("clicked") || r.contains("done")
        ) {
            return Verdict(true, "tool reported success")
        }

        // Non-error but unclear — accept with low confidence note for planner
        return Verdict(true, "non-error result; planner should confirm against the user goal")
    }

    private fun alternateFor(tool: String): String? = when (tool) {
        "play_media" -> "open_app with installed music package"
        "open_url" -> "open_app for a native package instead of browser"
        "python_exec" -> "workspace_read then simplified python_exec"
        else -> null
    }
}
