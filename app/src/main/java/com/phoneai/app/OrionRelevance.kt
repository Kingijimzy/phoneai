package com.phoneai.app

/**
 * The one shared "does this share meaning with that" primitive, used wherever Orion decides whether a
 * piece of context, a past lesson, or a tool actually bears on the current request.
 *
 * Before this, the same job was done by several separate, independently-drifted implementations:
 * [MemoryRanking]'s tokenizer (no stemming at all), a private unstemmed regex inside `OrionExperience`
 * (`OrionExecutive.kt`), `OrionToolRouter`'s own crude stemmer ("running" -> "runn", a doubled
 * consonant left in place), and `ExperiencePolicy`'s own, materially better stemmer (undoes the
 * doubled consonant, handles irregular plurals like "companies" -> "company"). Four call sites, four
 * slightly different ideas of what "the same word" means, with no reason for the difference other
 * than each one being written in isolation at a different point in this codebase's life.
 *
 * This is the one canonical stemmer, built on top of [MemoryRanking]'s existing tokenizer rather than
 * replacing it (it already has the broad stop-word list and is used in a dozen other places that have
 * nothing to do with stemming). Everything that needs "same word, different form" should call this
 * instead of growing a fifth implementation.
 */
internal object OrionRelevance {
    /**
     * Lifted from [ExperiencePolicy]'s stemmer, the most correct of the ones found in this codebase: it
     * undoes a doubled final consonant before "-ing" ("running" -> "run", not "runn"), folds "-ies" to
     * "-y" ("companies" -> "company"), and only drops a trailing "s" past four characters so short real
     * words ("gas", "bus") are not mangled.
     */
    fun stem(w: String): String {
        val word = w.lowercase()
        return when {
            word.length > 5 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length > 5 && word.endsWith("ing") -> {
                val root = word.dropLast(3)
                if (root.length > 2 && root.last() == root[root.lastIndex - 1] &&
                    root.last() !in "lsz") root.dropLast(1) else root
            }
            word.length > 5 && word.endsWith("ed") -> word.dropLast(2)
            word.length > 5 && (word.endsWith("ches") || word.endsWith("shes") ||
                word.endsWith("xes") || word.endsWith("zes")) -> word.dropLast(2)
            // Most English plurals add only "s": messages -> message, not messag.
            word.length > 4 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
            else -> word
        }
    }

    /** [MemoryRanking]'s tokenizer (lowercasing, punctuation-splitting, stop-word removal), stemmed. */
    fun tokens(text: String): Set<String> = MemoryRanking.tokens(text).map(::stem).toSet()

    /** Do these two texts share any real meaning, word-form differences aside? */
    fun overlap(a: String, b: String): Boolean {
        val x = tokens(a)
        if (x.isEmpty()) return false
        return x.intersect(tokens(b)).isNotEmpty()
    }
}
