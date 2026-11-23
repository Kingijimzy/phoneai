package com.phoneai.app

/**
 * The self-improvement loop's eyes: from Orion's measured record, which tools earn trust, which keep failing,
 * and what to forge or rebuild next. Orion acts on it with extension_forge; nothing here blocks anything.
 */
internal object OrionEvolution {
    data class Verdict(val tool: String, val ok: Int, val failed: Int, val avgMs: Long, val advice: String)

    fun review(stats: Map<String, Triple<Int, Int, Long>>): List<Verdict> =
        stats.mapNotNull { (tool, t) ->
            val (ok, failed, ms) = t
            val n = ok + failed
            if (n == 0) return@mapNotNull null
            val avg = ms / n
            val advice = when {
                failed >= 3 && ok == 0 && OrionForgedTools.isForged(tool) -> "retire or rebuild: your forged tool has never worked"
                failed >= 3 && ok == 0 -> "has never worked: read its last error; forge a replacement if the cause is the tool itself"
                failed >= 3 && failed > ok -> "fails more than it works: read its last error (brain diagnostics) and change the approach"
                ok >= 5 && avg > 15_000 -> "works but slow; forge a lighter tool for the common case"
                ok >= 5 && failed == 0 -> "proven; keep in reach"
                else -> null
            } ?: return@mapNotNull null
            Verdict(tool, ok, failed, avg, advice)
        }.sortedWith(compareByDescending<Verdict> { it.failed }.thenBy { it.tool })

    fun report(stats: Map<String, Triple<Int, Int, Long>>): String {
        val v = review(stats)
        if (v.isEmpty()) return "EVOLUTION REVIEW: not enough measured use yet. Keep working; patterns appear after a few runs."
        return "EVOLUTION REVIEW (act on it with extension_forge, then test it):\n" +
            v.take(12).joinToString("\n") { "- ${it.tool} (${it.ok} ok / ${it.failed} failed, avg ${it.avgMs}ms): ${it.advice}" }
    }

    /** A short standing prompt section: failing tools Orion may fix himself, without asking, right now. */
    fun nudge(stats: Map<String, Triple<Int, Int, Long>>): String {
        val bad = review(stats).filter { it.failed >= 3 && it.failed > it.ok && OrionForgedTools.isForged(it.tool) }.take(2)
        if (bad.isEmpty()) return ""
        return "EVOLUTION: " + bad.joinToString("; ") { "${it.tool} keeps failing (${it.ok} ok / ${it.failed} failed)" } +
            ". Fix it yourself when it comes up: forge a replacement with extension_forge and use it. Do not ask first."
    }
}
