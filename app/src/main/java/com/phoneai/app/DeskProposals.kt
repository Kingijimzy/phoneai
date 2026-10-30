package com.phoneai.app

/**
 * Structured actions the Trading Coach can propose for the trading desk. They are only proposals: nothing is saved or
 * traded until the user taps Accept on the card.
 *
 *   STRATEGY: London breakouts | EURUSD, GBPUSD | 1h, 4h | breakout, pullback | 2 | enter after the Asian range breaks
 *   PAPER: long EURUSD 500 stop=1.0700 target=1.0900 | reason
 */
internal object DeskProposals {
    class Paper(val long: Boolean, val symbol: String, val usd: Double, val stop: Double, val target: Double?, val note: String) {
        fun summary(): String = "${if (long) "LONG" else "SHORT"} $symbol ${"%.0f".format(usd)} USD stop ${fmt(stop)}" + (target?.let { " target ${fmt(it)}" } ?: "")
        private fun fmt(v: Double) = if (v >= 100) "%.2f".format(v) else "%.5g".format(v)
    }

    class Parsed(val visible: String, val strategies: List<StrategySpec>, val papers: List<Paper>)

    private val strategyLine = Regex("(?im)^\\s*STRATEGY\\s*:\\s*(.+)$")
    private val paperLine = Regex("(?im)^\\s*PAPER\\s*:\\s*(long|short|buy|sell)\\s+([A-Za-z0-9/_=.-]{3,14})\\s+\\$?(\\d+(?:\\.\\d+)?)\\s+stop\\s*=?\\s*\\$?(\\d+(?:\\.\\d+)?)(?:\\s+target\\s*=?\\s*\\$?(\\d+(?:\\.\\d+)?))?\\s*(?:\\|\\s*(.*))?$")

    private fun list(s: String): List<String> = s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("any", true) && !it.equals("all", true) }

    fun parse(reply: String, resolveSymbol: (String) -> String?): Parsed {
        val strategies = ArrayList<StrategySpec>()
        for (m in strategyLine.findAll(reply)) {
            val f = m.groupValues[1].split('|').map { it.trim() }
            val name = f.getOrNull(0)?.take(40).orEmpty()
            if (name.isEmpty()) continue
            val instruments = list(f.getOrNull(1).orEmpty()).mapNotNull { resolveSymbol(it) }.distinct()
            val rr = f.getOrNull(4)?.toDoubleOrNull()?.coerceIn(0.5, 10.0) ?: 1.5
            strategies += StrategySpec(name, instruments, list(f.getOrNull(2).orEmpty()).map { it.lowercase() }, list(f.getOrNull(3).orEmpty()).map { it.lowercase() }, rr, 1.0, true, f.drop(5).joinToString(" | ").take(400))
            if (strategies.size >= 2) break
        }
        val papers = ArrayList<Paper>()
        for (m in paperLine.findAll(reply)) {
            val sym = resolveSymbol(m.groupValues[2]) ?: continue
            val usd = m.groupValues[3].toDoubleOrNull() ?: continue
            val stop = m.groupValues[4].toDoubleOrNull() ?: continue
            if (usd <= 0 || stop <= 0) continue
            papers += Paper(m.groupValues[1].lowercase() in setOf("long", "buy"), sym, usd, stop, m.groupValues[5].toDoubleOrNull(), m.groupValues[6].trim().take(200))
            if (papers.size >= 2) break
        }
        val clean = reply.replace(strategyLine, "").replace(paperLine, "").replace(Regex("\\n{3,}"), "\n\n").trim()
        return Parsed(clean, strategies, papers)
    }
}
