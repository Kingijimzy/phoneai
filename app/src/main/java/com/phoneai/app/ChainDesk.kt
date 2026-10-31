package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Read-only memecoin and wallet watching. Only public addresses and public market data: Orion never asks for or keeps
 * a private key or seed phrase, never signs anything and never sends a transaction.
 */
internal object ChainDesk {
    private const val DEX = "https://api.dexscreener.com"
    private fun sp(c: Context) = c.getSharedPreferences("chain_desk", Context.MODE_PRIVATE)

    private class Evm(val label: String, val rpc: String, val wrapped: String, val dexChain: String)
    private val evmChains = listOf(
        Evm("Ethereum", "https://eth.llamarpc.com", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "ethereum"),
        Evm("Base", "https://mainnet.base.org", "0x4200000000000000000000000000000000000006", "base"),
        Evm("BNB Chain", "https://bsc-dataseed.binance.org", "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c", "bsc")
    )
    private const val SOLANA_RPC = "https://api.mainnet-beta.solana.com"
    private const val WSOL = "So11111111111111111111111111111111111111112"
    private const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"

    private fun http(url: String, body: String? = null): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Orion/1.0"); conn.setRequestProperty("Accept", "application/json")
            if (body != null) { conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
            if (conn.responseCode !in 200..299) null else conn.inputStream.bufferedReader().use { it.readText().take(1_500_000) }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }

    // ---- memecoins
    fun searchPairs(query: String): List<DexPair> =
        http("$DEX/latest/dex/search?q=" + URLEncoder.encode(query.trim().take(80), "UTF-8"))?.let { DexParse.pairs(it) }.orEmpty()
            .filter { it.liquidityUsd > 0 }.sortedByDescending { it.liquidityUsd }

    private fun tokenPairs(chain: String, addresses: List<String>): Map<String, DexPair> =
        addresses.chunked(30).fold(emptyMap<String, DexPair>()) { acc, chunk ->
            acc + (http("$DEX/tokens/v1/$chain/" + chunk.joinToString(","))?.let { DexParse.bestPerToken(DexParse.pairs(it)) }.orEmpty())
        }

    class Meme(val chain: String, val token: String, val symbol: String)

    fun memes(c: Context): List<Meme> = try {
        val a = JSONArray(sp(c).getString("memes", "[]")); (0 until a.length()).map { val o = a.getJSONObject(it); Meme(o.getString("c"), o.getString("t"), o.getString("s")) }
    } catch (_: Exception) { emptyList() }

    private fun saveMemes(c: Context, l: List<Meme>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("c", it.chain).put("t", it.token).put("s", it.symbol)) }
        sp(c).edit().putString("memes", a.toString()).apply()
    }

    fun checkMeme(query: String): String {
        if (WalletAddress.looksLikeSecret(query)) return "That looks like a private key or seed phrase. Never share those with anyone, including me. Paste a token contract address or a name instead."
        val p = searchPairs(query).firstOrNull() ?: return "I could not find a trading pair for '$query'. Paste the token's contract address for an exact match."
        return ChainRisk.summary(p, System.currentTimeMillis()) + " Names are copied by scammers, so confirm the contract address (${p.tokenAddress.take(6)}...${p.tokenAddress.takeLast(4)}) before trusting it."
    }

    fun addMeme(c: Context, query: String): String {
        if (WalletAddress.looksLikeSecret(query)) return checkMeme(query)
        val p = searchPairs(query).firstOrNull() ?: return "I could not find a trading pair for '$query'. Paste the token's contract address for an exact match."
        val cur = memes(c)
        if (cur.any { it.token.equals(p.tokenAddress, true) }) return "${p.symbol} is already on your memecoin watchlist."
        if (cur.size >= 15) return "The memecoin watchlist is full (15). Remove one first."
        saveMemes(c, cur + Meme(p.chain, p.tokenAddress, p.symbol))
        return "Watching ${p.symbol} (${p.chain}). " + ChainRisk.summary(p, System.currentTimeMillis())
    }

    fun removeMeme(c: Context, query: String): String {
        val cur = memes(c); val q = query.trim()
        val keep = cur.filterNot { it.symbol.equals(q, true) || it.token.equals(q, true) }
        if (keep.size == cur.size) return "No watched memecoin matches '$q'."
        saveMemes(c, keep); return "Removed $q from the memecoin watchlist."
    }

    // ---- wallets
    class Wallet(val address: String, val label: String)

    fun wallets(c: Context): List<Wallet> = try {
        val a = JSONArray(sp(c).getString("wallets", "[]")); (0 until a.length()).map { val o = a.getJSONObject(it); Wallet(o.getString("a"), o.optString("l")) }
    } catch (_: Exception) { emptyList() }

    private fun saveWallets(c: Context, l: List<Wallet>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("a", it.address).put("l", it.label)) }
        sp(c).edit().putString("wallets", a.toString()).apply()
    }

    fun addWallet(c: Context, address: String, label: String): String {
        if (WalletAddress.looksLikeSecret(address)) return "That looks like a private key or seed phrase. Never share those with anyone, including me. I only need a public wallet address."
        val a = address.trim()
        val kind = WalletAddress.detect(a) ?: return "That is not a valid Solana or EVM (0x...) public address."
        val cur = wallets(c)
        if (cur.any { it.address.equals(a, true) }) return "That wallet is already on the watchlist."
        if (cur.size >= 5) return "The wallet watchlist is full (5). Remove one first."
        saveWallets(c, cur + Wallet(a, label.trim().take(24).ifBlank { a.take(4) + "..." + a.takeLast(4) }))
        return "Watching ${if (kind == WalletAddress.Kind.SOLANA) "Solana" else "EVM"} wallet ${a.take(6)}...${a.takeLast(4)}. Read-only: I can see balances and changes, never move anything."
    }

    fun removeWallet(c: Context, query: String): String {
        val cur = wallets(c); val q = query.trim()
        val keep = cur.filterNot { it.address.equals(q, true) || it.label.equals(q, true) }
        if (keep.size == cur.size) return "No watched wallet matches '$q'."
        saveWallets(c, keep); sp(c).edit().remove("snap_" + q.lowercase()).remove("start_" + q.lowercase()).apply()
        return "Stopped watching $q."
    }

    private fun rpc(url: String, method: String, params: String): String? =
        http(url, """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")

    private val priceCache = HashMap<String, Pair<Long, Double>>()
    private fun nativePrice(dexChain: String, wrapped: String): Double {
        val now = System.currentTimeMillis()
        synchronized(priceCache) { priceCache[dexChain]?.takeIf { now - it.first < 600_000L }?.let { return it.second } }
        val p = tokenPairs(dexChain, listOf(wrapped)).values.firstOrNull()?.priceUsd ?: 0.0
        if (p > 0) synchronized(priceCache) { priceCache[dexChain] = now to p }
        return p
    }

    /** Current holdings with USD values where a market price exists. Null when the chain could not be read. */
    fun snapshot(address: String): WalletDiff.Snapshot? {
        val holdings = ArrayList<WalletDiff.Holding>()
        when (WalletAddress.detect(address)) {
            WalletAddress.Kind.SOLANA -> {
                val sol = rpc(SOLANA_RPC, "getBalance", """["$address"]""")?.let { DexParse.solBalance(it) } ?: return null
                holdings += WalletDiff.Holding("sol", "SOL", sol, sol * nativePrice("solana", WSOL))
                val toks = rpc(SOLANA_RPC, "getTokenAccountsByOwner", """["$address",{"programId":"$TOKEN_PROGRAM"},{"encoding":"jsonParsed"}]""")?.let { DexParse.solanaTokens(it) }.orEmpty()
                val priced = tokenPairs("solana", toks.keys.take(30))
                toks.entries.take(30).forEach { (mint, amt) ->
                    val pair = priced[mint.lowercase(Locale.US)]
                    holdings += WalletDiff.Holding(mint, pair?.symbol ?: (mint.take(4) + "..."), amt, if (pair != null) amt * pair.priceUsd else 0.0)
                }
            }
            WalletAddress.Kind.EVM -> {
                var any = false
                for (e in evmChains) {
                    val bal = rpc(e.rpc, "eth_getBalance", """["$address","latest"]""")?.let { DexParse.evmBalance(it) } ?: continue
                    any = true
                    if (bal > 0) holdings += WalletDiff.Holding(e.dexChain + ":native", e.label + " coin", bal, bal * nativePrice(e.dexChain, e.wrapped))
                }
                if (!any) return null
            }
            null -> return null
        }
        return WalletDiff.Snapshot(holdings.sumOf { it.usd }, holdings.filter { it.amount > 0 })
    }

    private fun saveSnap(c: Context, address: String, s: WalletDiff.Snapshot) {
        val h = JSONArray(); s.holdings.forEach { h.put(JSONObject().put("k", it.key).put("s", it.symbol).put("a", it.amount).put("u", it.usd)) }
        val k = address.lowercase(Locale.US)
        sp(c).edit().putString("snap_$k", JSONObject().put("t", s.totalUsd).put("h", h).toString()).apply()
        if (!sp(c).contains("start_$k")) sp(c).edit().putFloat("start_$k", s.totalUsd.toFloat()).apply()
    }

    private fun loadSnap(c: Context, address: String): WalletDiff.Snapshot? {
        return try {
            val raw = sp(c).getString("snap_" + address.lowercase(Locale.US), "").orEmpty()
            if (raw.isEmpty()) return null
            val o = JSONObject(raw)
            val h = o.getJSONArray("h")
            WalletDiff.Snapshot(o.getDouble("t"), (0 until h.length()).map { val x = h.getJSONObject(it); WalletDiff.Holding(x.getString("k"), x.getString("s"), x.getDouble("a"), x.getDouble("u")) })
        } catch (_: Exception) { null }
    }

    fun overview(c: Context): String {
        val sb = StringBuilder()
        val ms = memes(c)
        if (ms.isEmpty()) sb.append("Memecoin watchlist is empty.") else {
            val byChain = ms.groupBy { it.chain }
            val now = System.currentTimeMillis()
            byChain.forEach { (chain, l) ->
                val live = tokenPairs(chain, l.map { it.token })
                l.forEach { m -> sb.append("\n").append(live[m.token.lowercase(Locale.US)]?.let { ChainRisk.summary(it, now) } ?: "${m.symbol}: no data right now.") }
            }
        }
        val ws = wallets(c)
        sb.append("\n\n")
        if (ws.isEmpty()) sb.append("No wallets watched.") else ws.forEach { w ->
            val s = snapshot(w.address)
            sb.append(if (s == null) "${w.label}: could not read the chain right now.\n" else WalletDiff.text(w.label, s, sp(c).getFloat("start_" + w.address.lowercase(Locale.US), -1f).takeIf { it >= 0 }?.toDouble()) + "\n")
        }
        return sb.toString().trim()
    }

    /** Watcher pass: memecoin pumps, dumps and liquidity pulls; wallet value moves and new tokens. */
    fun watchCycle(c: Context, notify: (Int, String, String) -> Unit) {
        val now = System.currentTimeMillis()
        var n = 0
        val snaps = try { JSONObject(sp(c).getString("meme_snap", "{}")) } catch (_: Exception) { JSONObject() }
        val cool = try { JSONObject(sp(c).getString("meme_cool", "{}")) } catch (_: Exception) { JSONObject() }
        memes(c).groupBy { it.chain }.forEach { (chain, l) ->
            val live = tokenPairs(chain, l.map { it.token })
            l.forEach { m ->
                val cur = live[m.token.lowercase(Locale.US)] ?: return@forEach
                val prevArr = snaps.optJSONArray(m.token)
                val prev = if (prevArr != null) prevArr.optDouble(0) to prevArr.optDouble(1) else null
                ChainRisk.alerts(prev, cur).forEach { a ->
                    val key = m.token + "|" + a.kind
                    if (n < 3 && now - cool.optLong(key, 0L) > 4 * 3_600_000L) { cool.put(key, now); notify(4700 + n, "Memecoin ${cur.symbol}", a.text); n++ }
                }
                snaps.put(m.token, JSONArray().put(cur.priceUsd).put(cur.liquidityUsd))
            }
        }
        sp(c).edit().putString("meme_snap", snaps.toString()).putString("meme_cool", cool.toString()).apply()
        wallets(c).take(5).forEachIndexed { i, w ->
            val s = snapshot(w.address) ?: return@forEachIndexed
            WalletDiff.compare(loadSnap(c, w.address), s).take(2).forEach { t -> notify(4750 + i, "Wallet ${w.label}", t) }
            saveSnap(c, w.address, s)
        }
    }
}
