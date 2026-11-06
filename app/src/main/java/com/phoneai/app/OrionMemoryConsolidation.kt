package com.phoneai.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Subject-slot consolidation. A new fact about the same subject as an old one (city, job, employer, a relative's
 * name) replaces the old fact instead of piling up beside it, and keeps a one-line history of what it replaced.
 * Near-duplicate wording in one category is still merged by MemoryRanking.findSuperseded.
 */
internal object OrionMemoryConsolidation {
    data class Slot(val key: String, val value: String)

    private const val VALUE = "([A-Za-z][A-Za-z0-9 .'&-]{1,40}?)(?=\\s*(?:[.,;!(]|\\s(?:and|but|because|since|with)\\b|$))"
    private val relatives = "sister|brother|mother|mom|mum|father|dad|wife|husband|son|daughter|girlfriend|boyfriend|friend|boss|cousin|uncle|aunt"
    private val rules: List<Pair<Regex, (MatchResult) -> Slot?>> = listOf(
        Regex("\\b(?:i live in|i'm living in|i am living in|i moved to|i'm based in|i am based in|my city is|my hometown is|my home town is)\\s+$VALUE", RegexOption.IGNORE_CASE) to
            { m -> Slot("city", m.groupValues[1]) },
        Regex("\\b(?:i work as(?: an?)?|my job is(?: an?)?|my job title is)\\s+$VALUE", RegexOption.IGNORE_CASE) to
            { m -> Slot("job", m.groupValues[1]) },
        Regex("\\b(?:i work at|i work for|my employer is|my company is)\\s+$VALUE", RegexOption.IGNORE_CASE) to
            { m -> Slot("employer", m.groupValues[1]) },
        Regex("\\bmy ($relatives)(?:'s name)? (?:is )?(?:called |named |is )?$VALUE", RegexOption.IGNORE_CASE) to
            { m -> Slot("relative:" + m.groupValues[1].lowercase(), m.groupValues[2]) }
    )

    fun slotOf(text: String): Slot? {
        val clean = text.trim()
        for ((regex, make) in rules) {
            val m = regex.find(clean) ?: continue
            val slot = make(m) ?: continue
            val value = slot.value.trim().trimEnd('.', ',', ';', '!').lowercase()
            if (value.length >= 2) return Slot(slot.key, value)
        }
        return null
    }

    /**
     * The existing memory this new text contradicts: same slot, different value. A pending (unreviewed) new text
     * never overwrites an approved memory; only owner-approved text may replace approved memory.
     */
    fun conflicting(existing: List<Memory>, newText: String, newStatus: String): Memory? {
        val slot = slotOf(newText) ?: return null
        return existing
            .filter { !it.pinned && it.reviewStatus != "rejected" && it.reviewStatus != "superseded" }
            .filter { newStatus == "approved" || it.reviewStatus != "approved" }
            .filter { old -> slotOf(old.text).let { it != null && it.key == slot.key && it.value != slot.value } }
            .maxByOrNull { it.updated }
    }

    /** New text plus a one-line history of the value it replaced. */
    fun withHistory(oldText: String, newText: String, nowMs: Long): String {
        val oldValue = slotOf(oldText)?.value ?: oldText.trim().take(60)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        val base = newText.substringBefore(" (was: ").trim()
        return (base.take(300) + " (was: " + oldValue.take(50) + ", until " + date + ")").take(380)
    }
}
