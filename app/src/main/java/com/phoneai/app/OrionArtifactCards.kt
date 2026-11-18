package com.phoneai.app

/**
 * Keeps chat clean: code, files and local previews inside a reply become tappable cards that open the right
 * space (Code Studio editor, file, Orion browser) instead of dumping text into the conversation.
 */
internal object OrionArtifactCards {
    enum class Kind { CODE, FILE, PREVIEW }
    data class Card(val kind: Kind, val title: String, val target: String, val extra: String = "")

    private val fence = Regex("```([A-Za-z0-9+#._-]*)[ \\t]*\\n?([\\s\\S]*?)(```|$)")
    private val filePath = Regex("/workspace/([A-Za-z0-9._-][A-Za-z0-9._/-]{0,100}\\.[A-Za-z0-9]{1,8})")
    private val localUrl = Regex("https?://(?:127\\.0\\.0\\.1|localhost)(?::\\d{2,5})?(?:/[^\\s)\\]\"'>]*)?")

    fun extract(text: String): List<Card> {
        if (text.isBlank()) return emptyList()
        val cards = ArrayList<Card>()
        fence.findAll(text).take(3).forEach { m ->
            val body = m.groupValues[2].trimEnd()
            if (body.isNotBlank()) {
                val lang = m.groupValues[1].lowercase().ifBlank { "text" }
                val lines = body.lines().size
                cards += Card(Kind.CODE, "Code · $lang · $lines line${if (lines == 1) "" else "s"}", body.take(60_000), lang)
            }
        }
        val rest = fence.replace(text, " ")
        filePath.findAll(rest).map { it.groupValues[1] }.filterNot { it.contains("..") }.distinct().take(4).forEach {
            cards += Card(Kind.FILE, it.substringAfterLast('/'), it)
        }
        localUrl.findAll(rest).map { it.value.trimEnd('.', ',', ';') }.distinct().take(2).forEach {
            cards += Card(Kind.PREVIEW, "Preview · " + it.removePrefix("http://").removePrefix("https://"), it)
        }
        return cards
    }

    /** Opens the space a card belongs to. */
    fun open(c: android.content.Context, card: Card) {
        val intent = when (card.kind) {
            Kind.CODE -> android.content.Intent(c, CodeSpaceActivity::class.java).putExtra("snippet", card.target).putExtra("lang", card.extra)
            Kind.FILE -> android.content.Intent(c, CodeSpaceActivity::class.java).putExtra("file", card.target)
            Kind.PREVIEW -> android.content.Intent(c, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, card.target)
        }
        runCatching { c.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
