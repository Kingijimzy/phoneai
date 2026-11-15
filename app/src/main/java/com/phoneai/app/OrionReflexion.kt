package com.phoneai.app

/**
 * Reflexion runtime: actor -> evaluator -> reflection -> retry. When every tool in a round failed and the
 * model is about to stop, Orion makes it write a short diagnosis and try a different route instead of
 * reporting failure. Bounded per command so it can never spin.
 */
internal object OrionReflexion {
    const val MAX_REFLECTIONS_PER_COMMAND = 3

    private val failureMarkers = listOf(
        "error:", "exception", "traceback", "failed", "not found", "permission denied", "timed out",
        "refused", "unauthorized", "forbidden", "http 4", "http 5", "exit=1", "exit=2", "exit=126", "exit=127"
    )

    fun looksFailed(result: String): Boolean {
        val t = result.take(600).lowercase()
        if (t.isBlank()) return false
        if (t.startsWith("exit 0") || t.contains("success")) return false
        return failureMarkers.any { it in t }
    }

    /** True when the round produced results and every one of them failed. */
    fun roundFailed(results: List<String>): Boolean = results.isNotEmpty() && results.all(::looksFailed)

    fun shouldReflect(roundFailed: Boolean, reflectionsUsed: Int, modelWantsToStop: Boolean): Boolean =
        roundFailed && modelWantsToStop && reflectionsUsed < MAX_REFLECTIONS_PER_COMMAND

    fun prompt(attempt: Int, results: List<String>): String =
        "[ORION REFLEXION $attempt/$MAX_REFLECTIONS_PER_COMMAND] The last tool round failed:\n" +
            results.take(4).joinToString("\n") { "- " + it.take(300).replace("\n", " ") } +
            "\nDo not give up and do not report failure yet. In one or two sentences diagnose the root cause " +
            "(wrong arguments, missing package, wrong route, permission, bad assumption), then try a DIFFERENT route " +
            "with your tools (install what is missing in the Linux shell, use another tool, fix the input, or write a small " +
            "extension). Only if a different route is genuinely impossible, tell the owner exactly what is blocking and what you tried."
}
