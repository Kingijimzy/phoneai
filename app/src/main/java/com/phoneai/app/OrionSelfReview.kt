package com.phoneai.app

/**
 * The evaluation and self-correction loop. Before a finished piece of work reaches the owner, the draft is graded against
 * a fixed rubric by a second pass; if it fails, the same pass returns the corrected version. Pure text rules so it is testable.
 */
internal object OrionSelfReview {
    data class Verdict(val ok: Boolean, val text: String, val reason: String)

    private val jobWords = Regex("(?i)\\b(build|code|write|create|implement|fix|research|analy[sz]e|compare|plan|design|review|debug|refactor|summari[sz]e|report|strategy|setup|set up)\\b")

    /** Greetings and one-liners are not reviewed; jobs and long answers are. */
    fun worthReviewing(request: String, answer: String): Boolean {
        val q = request.trim(); val a = answer.trim()
        if (q.length < 25 || a.length < 220) return false
        return jobWords.containsMatchIn(q) || a.length >= 700
    }

    fun prompt(request: String, draft: String): String =
        "You are the reviewer. Grade the DRAFT against the REQUEST. Rubric: (1) does it do exactly what was asked, fully; (2) every claim is backed by something actually done or read, none invented; " +
            "(3) uncertainty and what is unverified are stated plainly; (4) it chose the best option and says why in one line; (5) nothing important is missing, and it is concise.\n" +
            "Reply with exactly one first line: `VERDICT: OK` or `VERDICT: REVISE: <the main flaw in one short sentence>`. If REVISE, follow with the complete corrected answer, ready to give the owner, with no commentary about the review. If OK, write nothing else.\n\n" +
            "REQUEST:\n" + request.take(1500) + "\n\nDRAFT:\n" + draft.take(6000)

    fun parse(reply: String, draft: String): Verdict {
        val r = reply.trim()
        val first = r.lineSequence().firstOrNull().orEmpty().trim()
        val m = Regex("(?i)^verdict\\s*:\\s*(ok|revise)\\b[:\\s-]*(.*)$").find(first) ?: return Verdict(true, draft, "")
        if (m.groupValues[1].equals("ok", true)) return Verdict(true, draft, "")
        val body = r.lineSequence().drop(1).joinToString("\n").trim()
        return if (body.length < 40) Verdict(true, draft, "") else Verdict(false, body, m.groupValues[2].trim())
    }
}
