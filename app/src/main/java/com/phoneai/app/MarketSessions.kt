package com.phoneai.app

/** Forex/crypto trading sessions by UTC hour, with overlaps and news-blackout timing. Pure. */
internal object MarketSessions {
    data class Session(val name: String, val startUtc: Int, val endUtc: Int)

    val all = listOf(
        Session("Sydney", 21, 6), Session("Tokyo", 0, 9), Session("London", 7, 16), Session("New York", 12, 21)
    )

    private fun inside(s: Session, hour: Int) = if (s.startUtc < s.endUtc) hour in s.startUtc until s.endUtc else hour >= s.startUtc || hour < s.endUtc

    fun active(utcHour: Int): List<String> = all.filter { inside(it, utcHour.mod(24)) }.map { it.name }

    /** "London + New York overlap: the most liquid hours" style one-liner. */
    fun describe(utcHour: Int): String {
        val a = active(utcHour)
        return when {
            a.size >= 2 -> a.joinToString(" + ") + " overlap: usually the most liquid hours."
            a.size == 1 -> a.first() + " session."
            else -> "Between sessions: thin liquidity."
        }
    }

    /** Pairs and the sessions where they usually move most. Used to prefer setups in their natural hours. */
    fun preferredSessions(symbol: String): List<String> {
        val s = symbol.uppercase().replace("/", "").replace("_", "")
        return when {
            s.startsWith("JPY") || s.endsWith("JPY") -> listOf("Tokyo", "London", "New York")
            s.endsWith("AUD") || s.startsWith("AUD") || s.startsWith("NZD") || s.endsWith("NZD") -> listOf("Sydney", "Tokyo")
            s.startsWith("EUR") || s.startsWith("GBP") || s.endsWith("CHF") -> listOf("London", "New York")
            s.endsWith("USDT") -> listOf("London", "New York")
            else -> all.map { it.name }
        }
    }

    fun inPreferredSession(symbol: String, utcHour: Int): Boolean =
        active(utcHour).any { it in preferredSessions(symbol) }
}
