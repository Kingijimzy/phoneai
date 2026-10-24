package com.phoneai.app

/**
 * Normalizes and segments replies for incremental speech playback.
 * The punctuation ending each sentence stays attached to the audio chunk.
 */
internal object SpeechChunker {
    fun split(text: String, limit: Int = 420): List<String> {
        require(limit > 0) { "Speech chunk size must be greater than zero." }
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()

        val sentences = normalized
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val out = ArrayList<String>()

        for (sentence in sentences) {
            var remaining = sentence
            while (remaining.length > limit) {
                val window = remaining.substring(0, limit)
                val comma = window.lastIndexOf(", ")
                val semicolon = window.lastIndexOf("; ")
                val space = window.lastIndexOf(' ')
                val punctuation = maxOf(comma, semicolon)
                val splitAt: Int
                val nextAt: Int
                if (punctuation > limit / 2) {
                    // Keep the punctuation at the end of this chunk and skip only
                    // its following space; joining chunks with a pause stays readable.
                    splitAt = punctuation + 1
                    nextAt = punctuation + 2
                } else if (space > limit / 2) {
                    splitAt = space
                    nextAt = space + 1
                } else {
                    splitAt = limit
                    nextAt = limit
                }
                out.add(remaining.substring(0, splitAt).trim())
                remaining = remaining.substring(nextAt).trim()
            }
            if (remaining.isNotEmpty()) out.add(remaining)
        }
        return out
    }
}
