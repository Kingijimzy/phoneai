package com.phoneai.app

/**
 * Declarative trading playbook models and pure helpers.
 * Skills grow only when the user accepts candidates — never silent auto-enable.
 */
object TradingPlaybook {
    enum class AssetClass { CRYPTO, FOREX, OTHER }
    enum class Direction { LONG, SHORT, EITHER }

    data class Rule(
        val id: String,
        val name: String,
        val assetClass: AssetClass,
        val instrument: String,
        val direction: Direction,
        /** Free-form conditions the matcher / model interpret (session, level, pattern). */
        val conditions: List<String> = emptyList(),
        val enabled: Boolean = false,
        val version: Int = 1,
        val sourceNote: String = ""
    )

    data class Candidate(
        val name: String,
        val assetClass: AssetClass,
        val instrument: String,
        val direction: Direction,
        val conditions: List<String>,
        val sourceNote: String,
        val needsUserAccept: Boolean = true
    )

    data class StrategyNote(
        val instrument: String,
        val direction: Direction?,
        val levels: List<String> = emptyList(),
        val invalidation: String = "",
        val session: String = "",
        val rawSummary: String = "",
        val fromImage: Boolean = false
    )

    fun normalizeInstrument(raw: String): String =
        raw.trim().uppercase().replace(" ", "").replace("/", "").take(24)

    fun parseDirection(raw: String?): Direction? {
        val t = raw?.trim()?.lowercase() ?: return null
        return when {
            t in setOf("long", "buy", "bull", "bullish") -> Direction.LONG
            t in setOf("short", "sell", "bear", "bearish") -> Direction.SHORT
            t in setOf("either", "both", "any") -> Direction.EITHER
            else -> null
        }
    }

    fun parseAssetClass(instrument: String, hint: String? = null): AssetClass {
        val h = hint?.lowercase()
        if (h == "forex" || h == "fx") return AssetClass.FOREX
        if (h == "crypto") return AssetClass.CRYPTO
        val i = instrument.uppercase()
        val fx = setOf("EUR", "USD", "GBP", "JPY", "AUD", "NZD", "CAD", "CHF", "XAU", "XAG")
        if (i.length == 6 && i.take(3) in fx && i.drop(3) in fx) return AssetClass.FOREX
        if (i.contains("USDT") || i.contains("USD") && (i.contains("BTC") || i.contains("ETH"))) return AssetClass.CRYPTO
        return AssetClass.OTHER
    }

    /** Build a playbook candidate from a structured strategy note (chart image caption or trade result text). */
    fun candidateFromNote(note: StrategyNote, name: String? = null): Candidate {
        val instrument = normalizeInstrument(note.instrument)
        val direction = note.direction ?: Direction.EITHER
        val conditions = buildList {
            if (note.session.isNotBlank()) add("session:${note.session.trim()}")
            note.levels.filter { it.isNotBlank() }.forEach { add("level:$it") }
            if (note.invalidation.isNotBlank()) add("invalidation:${note.invalidation.trim()}")
            if (note.rawSummary.isNotBlank()) add("note:${note.rawSummary.trim().take(200)}")
            if (note.fromImage) add("source:chart_image")
        }
        return Candidate(
            name = name?.take(80) ?: "Setup $instrument ${direction.name.lowercase()}",
            assetClass = parseAssetClass(instrument),
            instrument = instrument,
            direction = direction,
            conditions = conditions,
            sourceNote = note.rawSummary.take(400),
            needsUserAccept = true
        )
    }

    fun activate(candidate: Candidate, id: String, enabled: Boolean = true): Rule =
        Rule(
            id = id,
            name = candidate.name,
            assetClass = candidate.assetClass,
            instrument = candidate.instrument,
            direction = candidate.direction,
            conditions = candidate.conditions,
            enabled = enabled,
            version = 1,
            sourceNote = candidate.sourceNote
        )
}
