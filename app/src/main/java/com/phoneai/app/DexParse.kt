package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Parsers for the public DEX market-data feed and chain RPC replies. Tolerant: odd rows are skipped, never thrown. */
internal object DexParse {
    private fun num(o: JSONObject?, k: String): Double = o?.optDouble(k, 0.0)?.takeIf { !it.isNaN() } ?: 0.0

    /** Accepts {"pairs":[...]} (search) or a bare array (token lookups). */
    fun pairs(json: String): List<DexPair> {
        val arr: JSONArray = try {
            val t = json.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("pairs") ?: return emptyList()
        } catch (_: Exception) { return emptyList() }
        val out = ArrayList<DexPair>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val base = o.optJSONObject("baseToken") ?: continue
            val price = o.optString("priceUsd", "").toDoubleOrNull() ?: continue
            if (price <= 0.0) continue
            val ch = o.optJSONObject("priceChange"); val tx = o.optJSONObject("txns")?.optJSONObject("h1")
            out += DexPair(
                o.optString("chainId"), o.optString("dexId"), o.optString("pairAddress"), base.optString("address"),
                base.optString("symbol").take(20), base.optString("name").take(40), price,
                num(ch, "h1"), num(ch, "h24"), num(o.optJSONObject("liquidity"), "usd"), num(o.optJSONObject("volume"), "h24"),
                tx?.optInt("buys", 0) ?: 0, tx?.optInt("sells", 0) ?: 0,
                num(o, "fdv"), o.optLong("pairCreatedAt", 0L), o.optString("url")
            )
        }
        return out
    }

    /** Highest-liquidity pair for each token (keyed by lower-case token address). */
    fun bestPerToken(list: List<DexPair>): Map<String, DexPair> =
        list.groupBy { it.tokenAddress.lowercase(Locale.US) }.mapValues { e -> e.value.maxByOrNull { it.liquidityUsd }!! }

    /** Solana getTokenAccountsByOwner (jsonParsed): mint to amount, zero balances dropped, same mint summed. */
    fun solanaTokens(json: String): Map<String, Double> {
        val arr = try { JSONObject(json).optJSONObject("result")?.optJSONArray("value") } catch (_: Exception) { null } ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (i in 0 until arr.length()) {
            val info = arr.optJSONObject(i)?.optJSONObject("account")?.optJSONObject("data")?.optJSONObject("parsed")?.optJSONObject("info") ?: continue
            val mint = info.optString("mint"); val amt = info.optJSONObject("tokenAmount")?.optDouble("uiAmount", 0.0) ?: 0.0
            if (mint.isNotEmpty() && amt > 0.0 && !amt.isNaN()) out[mint] = (out[mint] ?: 0.0) + amt
        }
        return out
    }

    /** Solana getBalance: lamports to SOL. */
    fun solBalance(json: String): Double? = try { JSONObject(json).optJSONObject("result")?.optLong("value", -1L)?.takeIf { it >= 0 }?.let { it / 1e9 } } catch (_: Exception) { null }

    /** EVM eth_getBalance: hex wei to the native coin. */
    fun evmBalance(json: String): Double? = try {
        JSONObject(json).optString("result", "").removePrefix("0x").takeIf { it.isNotEmpty() && it.length <= 40 }?.let { java.math.BigInteger(it, 16).toBigDecimal().divide(java.math.BigDecimal("1e18")).toDouble() }
    } catch (_: Exception) { null }
}
