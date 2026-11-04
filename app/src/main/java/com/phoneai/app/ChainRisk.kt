package com.phoneai.app

import java.util.Locale
import kotlin.math.abs

/** One trading pair for a token on a DEX, as reported by a public market-data feed. */
data class DexPair(
    val chain: String, val dex: String, val pairAddress: String, val tokenAddress: String, val symbol: String, val name: String,
    val priceUsd: Double, val change1h: Double, val change24h: Double, val liquidityUsd: Double, val volume24h: Double,
    val buys1h: Int, val sells1h: Int, val fdv: Double, val createdAtMillis: Long, val url: String
)

/**
 * Read-only risk flags for memecoins from market data alone. It cannot see the contract, holder concentration or
 * honeypot traps, and says so. Pure.
 */
internal object ChainRisk {
    enum class Level { OK, CAUTION, HIGH }
    class Assessment(val level: Level, val flags: List<String>)
    class Alert(val kind: String, val text: String)

    private fun usd(v: Double) = when {
        v >= 1e9 -> String.format(Locale.US, "$%.1fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "$%.1fM", v / 1e6)
        v >= 1e3 -> String.format(Locale.US, "$%.0fK", v / 1e3)
        else -> String.format(Locale.US, "$%.0f", v)
    }

    fun assess(p: DexPair, now: Long): Assessment {
        val flags = ArrayList<String>(); var level = Level.OK
        fun flag(l: Level, t: String) { flags += t; if (l.ordinal > level.ordinal) level = l }
        when {
            p.liquidityUsd < 25_000 -> flag(Level.HIGH, "very thin liquidity (${usd(p.liquidityUsd)}): one sell can crash the price and you may not be able to exit")
            p.liquidityUsd < 100_000 -> flag(Level.CAUTION, "thin liquidity (${usd(p.liquidityUsd)}): large orders move the price a lot")
        }
        if (p.createdAtMillis > 0) {
            val hours = (now - p.createdAtMillis) / 3_600_000.0
            if (hours < 2) flag(Level.HIGH, "pair is only ${"%.0f".format(Locale.US, hours * 60)} minutes old")
            else if (hours < 24) flag(Level.CAUTION, "pair is only ${"%.0f".format(Locale.US, hours)}h old")
        }
        if (p.fdv > 0 && p.liquidityUsd > 0 && p.fdv / p.liquidityUsd > 500) flag(Level.CAUTION, "valuation ${usd(p.fdv)} is ${"%.0f".format(Locale.US, p.fdv / p.liquidityUsd)}x its liquidity: easy to manipulate")
        if (p.buys1h + p.sells1h >= 20 && p.sells1h > p.buys1h * 1.5) flag(Level.CAUTION, "sellers outnumber buyers (${p.sells1h} vs ${p.buys1h} in the last hour)")
        if (p.change1h <= -30) flag(Level.HIGH, "dumping: ${"%.0f".format(Locale.US, p.change1h)}% in the last hour")
        else if (p.change24h <= -60) flag(Level.HIGH, "down ${"%.0f".format(Locale.US, -p.change24h)}% in 24h")
        if (p.change1h >= 100) flag(Level.CAUTION, "parabolic: +${"%.0f".format(Locale.US, p.change1h)}% in an hour, late buyers usually get hurt")
        if (p.volume24h > 0 && p.liquidityUsd > 0 && p.volume24h / p.liquidityUsd > 30) flag(Level.CAUTION, "24h volume is ${"%.0f".format(Locale.US, p.volume24h / p.liquidityUsd)}x liquidity: wash trading or a frenzy")
        return Assessment(level, flags)
    }

    fun summary(p: DexPair, now: Long): String {
        val a = assess(p, now)
        val head = "${p.symbol} on ${p.chain} (${p.dex}): ${priceText(p.priceUsd)}, 1h ${"%+.1f".format(Locale.US, p.change1h)}%, 24h ${"%+.1f".format(Locale.US, p.change24h)}%, liquidity ${usd(p.liquidityUsd)}, 24h volume ${usd(p.volume24h)}."
        val risk = when (a.level) { Level.HIGH -> "HIGH RISK"; Level.CAUTION -> "CAUTION"; Level.OK -> "No obvious red flags in the market data" }
        return head + " $risk" + (if (a.flags.isNotEmpty()) ": " + a.flags.joinToString("; ") + "." else ".") +
            " This reads market data only; it cannot see the contract, who holds the supply, or honeypot traps."
    }

    fun priceText(v: Double) = if (v >= 1) String.format(Locale.US, "$%.2f", v) else String.format(Locale.US, "$%.8g", v)

    /** Alerts since the last check. [prev] is (priceUsd, liquidityUsd) from the previous snapshot, if any. */
    fun alerts(prev: Pair<Double, Double>?, cur: DexPair): List<Alert> {
        val out = ArrayList<Alert>()
        if (cur.change1h >= 15) out += Alert("pump", "${cur.symbol} is up ${"%.0f".format(Locale.US, cur.change1h)}% in the last hour (${priceText(cur.priceUsd)}). Chasing a pump is how most memecoin losses happen.")
        if (cur.change1h <= -15) out += Alert("dump", "${cur.symbol} is down ${"%.0f".format(Locale.US, -cur.change1h)}% in the last hour (${priceText(cur.priceUsd)}).")
        if (prev != null && prev.second > 0 && cur.liquidityUsd < prev.second * 0.7)
            out += Alert("liquidity", "${cur.symbol} liquidity fell ${"%.0f".format(Locale.US, (1 - cur.liquidityUsd / prev.second) * 100)}% since the last check (${usd(prev.second)} to ${usd(cur.liquidityUsd)}). That can mean a rug pull in progress.")
        return out
    }
}

internal object WalletAddress {
    enum class Kind { SOLANA, EVM }

    private val evm = Regex("^0x[0-9a-fA-F]{40}$")
    private val sol = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    fun detect(raw: String): Kind? = raw.trim().let { if (evm.matches(it)) Kind.EVM else if (sol.matches(it)) Kind.SOLANA else null }

    /** True when the text looks like a seed phrase or private key. Orion only ever accepts public addresses. */
    fun looksLikeSecret(raw: String): Boolean {
        val t = raw.trim()
        if (Regex("^(0x)?[0-9a-fA-F]{64}$").matches(t)) return true
        if (Regex("^[1-9A-HJ-NP-Za-km-z]{80,90}$").matches(t)) return true
        val words = t.lowercase(Locale.US).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.size in setOf(12, 15, 18, 21, 24) && words.all { it.length in 3..8 && it.all { c -> c in 'a'..'z' } }
    }
}

/** Compares two wallet snapshots and says what changed. */
internal object WalletDiff {
    data class Holding(val key: String, val symbol: String, val amount: Double, val usd: Double)
    class Snapshot(val totalUsd: Double, val holdings: List<Holding>)

    fun compare(prev: Snapshot?, cur: Snapshot, thresholdPct: Double = 10.0): List<String> {
        if (prev == null) return emptyList()
        val out = ArrayList<String>()
        if (prev.totalUsd >= 20) {
            val pct = (cur.totalUsd - prev.totalUsd) / prev.totalUsd * 100.0
            if (abs(pct) >= thresholdPct && abs(cur.totalUsd - prev.totalUsd) >= 10)
                out += "Wallet value moved ${"%+.1f".format(Locale.US, pct)}% since the last check (${"$%.0f".format(Locale.US, prev.totalUsd)} to ${"$%.0f".format(Locale.US, cur.totalUsd)})."
        }
        val before = prev.holdings.associateBy { it.key }
        cur.holdings.filter { it.key !in before }.take(3).forEach {
            out += "New token ${it.symbol} appeared in the wallet (${if (it.usd > 0) "$%.0f".format(Locale.US, it.usd) else "no market price"}). Unsolicited tokens are often scams: do not click or approve anything linked to them."
        }
        val now = cur.holdings.associateBy { it.key }
        prev.holdings.filter { it.usd >= 20 && (now[it.key]?.amount ?: 0.0) < it.amount * 0.5 }.take(3).forEach {
            out += "${it.symbol} balance dropped by more than half (was about ${"$%.0f".format(Locale.US, it.usd)})."
        }
        return out
    }

    fun text(label: String, s: Snapshot, startTotal: Double?): String {
        val top = s.holdings.sortedByDescending { it.usd }.take(6).joinToString(", ") { "${it.symbol} ${if (it.usd > 0) "$%.0f".format(Locale.US, it.usd) else "(no price)"}" }
        val since = startTotal?.takeIf { it >= 20 }?.let { " Since you started watching: ${"%+.1f".format(Locale.US, (s.totalUsd - it) / it * 100.0)}%." }.orEmpty()
        return "$label: about ${"$%.0f".format(Locale.US, s.totalUsd)} across ${s.holdings.size} asset${if (s.holdings.size == 1) "" else "s"}" + (if (top.isNotEmpty()) " ($top)" else "") + ".$since"
    }
}
