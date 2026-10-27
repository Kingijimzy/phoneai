package com.phoneai.app

/** Commands that map straight to one Android intent: no AI call, so they run in well under a second. */
internal object FastIntents {
    sealed class Intent {
        class PlayMedia(val query: String, val app: String?) : Intent()
        class ChromeSearch(val query: String) : Intent()
        class PlayStoreSearch(val query: String) : Intent()
    }

    private val polite = Regex("^(?:hey orion[, ]*|orion[, ]*|please |pls |can you |could you |would you |i want you to |i want to |go ahead and )+")
    private val musicWords = Regex("\\b(music|song|songs|playlist|mix|album|beats?|hits|remix|lofi|radio|video|videos|afrobeats?|gospel|worship|podcast)\\b| by ")
    private val gameWords = Regex("\\b(game|games|fc mobile|chess|ludo|pubg|cod|fortnite|minecraft)\\b")

    fun parse(raw: String): Intent? {
        var t = raw.lowercase().trim().replace(Regex("[?!.,]+$"), "")
        t = t.replace(polite, "").trim()
        if (t.isEmpty()) return null

        Regex("^(?:open|launch|go to|start)\\s+(youtube music|youtube|spotify)\\s+(?:and|then)\\s+play\\s+(?:me\\s+)?(?:some\\s+)?(.+)$").find(t)?.let {
            val q = it.groupValues[2].trim()
            if (q.isNotEmpty() && !gameWords.containsMatchIn(q)) return Intent.PlayMedia(q, it.groupValues[1])
        }
        Regex("^play\\s+(?:me\\s+)?(?:some\\s+)?(.+?)(?:\\s+(?:on|in|using|with)\\s+(youtube music|youtube|spotify))?$").find(t)?.let {
            val q = it.groupValues[1].trim()
            val app = it.groupValues[2].ifBlank { null }
            if (q.isNotEmpty() && !gameWords.containsMatchIn(q) && (app != null || musicWords.containsMatchIn(" $q "))) return Intent.PlayMedia(q, app)
        }

        Regex("^(?:open|go to|launch)\\s+(?:google chrome|chrome)(?:\\s+(?:and|then))?\\s+(?:search|google|look up|find)(?:\\s+for)?\\s+(.+)$").find(t)?.let {
            return Intent.ChromeSearch(it.groupValues[1].trim())
        }
        Regex("^(?:search|google|look up|find)\\s+(?:for\\s+)?(.+?)\\s+(?:on|in|using|with)\\s+(?:google chrome|chrome|google)$").find(t)?.let {
            return Intent.ChromeSearch(it.groupValues[1].trim())
        }
        Regex("^(?:open|launch|go to|start)\\s+(?:the\\s+)?(?:google play(?: store)?|play store)(?:\\s+(?:and|then)\\s+)?(?:search(?:\\s+for)?|find)\\s+(.+)$").find(t)?.let {
            return Intent.PlayStoreSearch(it.groupValues[1].trim())
        }
        Regex("^(?:search|find)\\s+(?:for\\s+)?(.+?)\\s+(?:in|on|using)\\s+(?:the\\s+)?(?:google play(?: store)?|play store)$").find(t)?.let {
            return Intent.PlayStoreSearch(it.groupValues[1].trim())
        }

        Regex("^google\\s+(.+)$").find(t)?.let { return Intent.ChromeSearch(it.groupValues[1].trim()) }
        return null
    }
}
