package com.phoneai.app

/**
 * Validates tool results against the user objective (reset Rule 5).
 * Rejects success-looking text that does not match the requested outcome.
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
        val r = res.lowercase()
        val g = goal.lowercase()

        if (r.startsWith("error") || r.startsWith("blocked") || r.contains("permission denied")) {
            return Verdict(false, res.take(240), alternateFor(tool))
        }

        // Open music app → never accept web YouTube search pages
        if (isOpenMusicGoal(g)) {
            if (looksLikeWebYoutube(r)) {
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

        // Explicit open_app tool should name an app, not a search page
        if (tool == "open_app" && looksLikeWebYoutube(r) && !g.contains("youtube.com")) {
            return Verdict(false, "open_app resolved to web URL", "use package launch for installed app")
        }

        // Shell failures often embed non-zero exit without error: prefix
        if (tool == "shell" && (r.contains("exit code") && Regex("exit code[^0-9]*[1-9]").containsMatchIn(r))) {
            return Verdict(false, "shell non-zero exit", "retry simplified command")
        }

        if (r.startsWith("ok") || r.contains("opened") || r.contains("playing") ||
            r.contains("saved") || r.contains("clicked") || r.contains("done") ||
            r.contains("success")
        ) {
            return Verdict(true, "tool reported success")
        }

        return Verdict(true, "non-error result; planner should confirm against the user goal")
    }

    private fun isOpenMusicGoal(g: String): Boolean =
        Regex("\\b(open|launch)\\b").containsMatchIn(g) &&
            Regex("\\b(music|spotify|youtube music)\\b").containsMatchIn(g) &&
            !Regex("\\b(play|search)\\b").containsMatchIn(g)

    private fun looksLikeWebYoutube(r: String): Boolean =
        r.contains("youtube results") ||
            r.contains("www.youtube.com") ||
            (r.contains("http") && r.contains("youtube") && !r.contains("youtube music"))

    private fun alternateFor(tool: String): String? = when (tool) {
        "play_media" -> "open_app with installed music package"
        "open_url" -> "open_app for a native package instead of browser"
        "open_app" -> "resolve installed package name then launch"
        "python_exec" -> "workspace_read then simplified python_exec"
        "shell" -> "retry with safer shorter command"
        else -> null
    }
}
