package com.phoneai.app

/**
 * Pure transcript edit/retry policy. The latest matching user turn is the anchor;
 * that turn and every later assistant/tool-context turn are removed before resubmission.
 */
internal object TranscriptEditPolicy {
    fun rewind(transcript: List<Pair<String, String>>, prompt: String): List<Pair<String, String>>? {
        val index = transcript.indexOfLast { it.first == "me" && it.second == prompt }
        if (index < 0) return null
        return transcript.take(index)
            .filter { (role, text) -> (role == "me" || role == "ai") && text.isNotBlank() }
            .map { it.first to it.second }
    }
}
