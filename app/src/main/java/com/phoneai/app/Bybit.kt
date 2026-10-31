package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BybitException(message: String) : Exception(message)

/** Bybit V5 API: market data (no key needed) and account / trading (needs your key). */
object Bybit {
    private const val MAIN = "https://api.bybit.com"
    private const val TEST = "https://api-testnet.bybit.com"
    private const val RECV = "5000"

    private val names = mapOf(
        "bitcoin" to "BTC", "ethereum" to "ETH", "ether" to "ETH", "solana" to "SOL", "ripple" to "XRP",
        "dogecoin" to "DOGE", "cardano" to "ADA", "litecoin" to "LTC", "chainlink" to "LINK",
        "avalanche" to "AVAX", "polkadot" to "DOT", "tron" to "TRX", "binance" to "BNB"
    )
    private val coins = setOf(
        "BTC", "ETH", "SOL", "XRP", "BNB", "DOGE", "ADA", "AVAX", "LINK", "LTC", "DOT", "MATIC", "TON", "TRX",
        "SUI", "ARB", "OP", "PEPE", "SHIB", "NEAR", "APT", "ATOM", "INJ", "FIL", "AAVE", "UNI", "TAO", "WIF"
    )

    /** "btc", "bitcoin" or "ethusdt" -> BTCUSDT. Returns null if it does not look like a coin. */
    fun symbolOf(word: String): String? {
        var w = word.trim().lowercase(Locale.US)
        w = names[w]?.lowercase(Locale.US) ?: w
        val up = w.uppercase(Locale.US)
        if (up.endsWith("USDT") && up.length in 6..14) return up
        if (up in coins) return up + "USDT"
        return null
    }

    fun hasKeys(c: Context): Boolean = Prefs.bybitKey(c).isNotEmpty() && Prefs.bybitSecret(c).isNotEmpty()

    fun modeText(c: Context): String = if (Prefs.bybitTestnet(c)) "TESTNET (practice money)" else "REAL MONEY"

    private fun tradeBase(c: Context) = if (Prefs.bybitTestnet(c)) TEST else MAIN

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    private fun sign(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    private fun call(
        c: Context, base: String, method: String, path: String, query: String, body: String?, signed: Boolean
    ): JSONObject {
        val url = URL(base + path + (if (query.isNotEmpty()) "?$query" else ""))
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Accept", "application/json")
            if (signed) {
                if (!hasKeys(c)) throw BybitException("No Bybit key saved. Add it in Orion settings, Trading card.")
                val ts = System.currentTimeMillis().toString()
                val key = Prefs.bybitKey(c)
                val payload = ts + key + RECV + (if (method == "GET") query else (body ?: ""))
                conn.setRequestProperty("X-BAPI-API-KEY", key)
                conn.setRequestProperty("X-BAPI-TIMESTAMP", ts)
                conn.setRequestProperty("X-BAPI-RECV-WINDOW", RECV)
                conn.setRequestProperty("X-BAPI-SIGN", sign(Prefs.bybitSecret(c), payload))
            }
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write((body ?: "").toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream == null) "" else BufferedReader(InputStreamReader(stream)).use { it.readText() }
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw BybitException("Bybit answered with an error ($code).")
            }
            val rc = o.optInt("retCode", -1)
            if (rc != 0) throw BybitException(o.optString("retMsg", "Bybit error $rc").take(160))
            return o
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- market data (mainnet, no key)

    fun price(c: Context, symbol: String, forTrade: Boolean = false): Double {
        val base = if (forTrade) tradeBase(c) else MAIN
        val o = call(c, base, "GET", "/v5/market/tickers", "category=linear&symbol=$symbol", null, false)
        val l = o.getJSONObject("result").getJSONArray("list")
        if (l.length() == 0) throw BybitException("I can't find $symbol on Bybit.")
        return l.getJSONObject(0).getString("lastPrice").toDouble()
    }

    fun change24h(c: Context, symbol: String): Double {
        val o = call(c, MAIN, "GET", "/v5/market/tickers", "category=linear&symbol=$symbol", null, false)
        val l = o.getJSONObject("result").getJSONArray("list")
        if (l.length() == 0) return 0.0
        return l.getJSONObject(0).optString("price24hPcnt", "0").toDoubleOrNull()?.times(100.0) ?: 0.0
    }

    /** Candles, oldest first. interval: 15, 60, 240, D. The last one may still be forming. */
    fun candles(c: Context, symbol: String, interval: String, limit: Int = 200): List<Candle> {
        val o = call(c, MAIN, "GET", "/v5/market/kline", "category=linear&symbol=$symbol&interval=$interval&limit=$limit", null, false)
        val l = o.getJSONObject("result").getJSONArray("list")
        val out = ArrayList<Candle>(l.length())
        for (i in l.length() - 1 downTo 0) {
            val r = l.getJSONArray(i)
            out.add(
                Candle(
                    r.getString(0).toLong(), r.getString(1).toDouble(), r.getString(2).toDouble(),
                    r.getString(3).toDouble(), r.getString(4).toDouble(), r.getString(5).toDouble()
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- account (needs key)

    private fun money(x: Double) = String.format(Locale.US, "%.2f", x)

    fun equity(c: Context): Double {
        val o = call(c, tradeBase(c), "GET", "/v5/account/wallet-balance", "accountType=UNIFIED", null, true)
        val l = o.getJSONObject("result").getJSONArray("list")
        if (l.length() == 0) return 0.0
        return l.getJSONObject(0).optString("totalEquity", "0").toDoubleOrNull() ?: 0.0
    }

    fun balance(c: Context): String {
        val o = call(c, tradeBase(c), "GET", "/v5/account/wallet-balance", "accountType=UNIFIED", null, true)
        val l = o.getJSONObject("result").getJSONArray("list")
        if (l.length() == 0) return "Your account looks empty. Mode: " + modeText(c) + "."
        val a = l.getJSONObject(0)
        val eq = a.optString("totalEquity", "0").toDoubleOrNull() ?: 0.0
        val av = a.optString("totalAvailableBalance", "0").toDoubleOrNull() ?: 0.0
        val pnl = a.optString("totalPerpUPL", "0").toDoubleOrNull() ?: 0.0
        return "Total equity ${money(eq)} dollars, available ${money(av)}, open profit or loss ${money(pnl)}. Mode: " + modeText(c) + "."
    }

    fun positionList(c: Context): JSONArray {
        val o = call(c, tradeBase(c), "GET", "/v5/position/list", "category=linear&settleCoin=USDT", null, true)
        val l = o.getJSONObject("result").getJSONArray("list")
        val out = JSONArray()
        for (i in 0 until l.length()) {
            val p = l.getJSONObject(i)
            if ((p.optString("size", "0").toDoubleOrNull() ?: 0.0) > 0.0) out.put(p)
        }
        return out
    }

    /** Closed trades (profit and loss records), newest first. Needs a key with read access. */
    fun closedPnl(c: Context, limit: Int = 100): JSONArray =
        call(c, tradeBase(c), "GET", "/v5/position/closed-pnl", "category=linear&limit=${limit.coerceIn(1, 200)}", null, true)
            .getJSONObject("result").getJSONArray("list")

    /** Funding rate, open interest (now and about 24h ago), 24h price change and the long share of accounts. Public data, no key. */
    fun derivativesInput(c: Context, symbol: String): DerivativesRead.Input? {
        val t = call(c, MAIN, "GET", "/v5/market/tickers", "category=linear&symbol=$symbol", null, false)
            .getJSONObject("result").getJSONArray("list").let { if (it.length() == 0) return null else it.getJSONObject(0) }
        val funding = t.optString("fundingRate", "").toDoubleOrNull() ?: return null
        val oiNow = t.optString("openInterest", "").toDoubleOrNull() ?: 0.0
        val chg = (t.optString("price24hPcnt", "0").toDoubleOrNull() ?: 0.0) * 100.0
        val oiOld = try {
            val l = call(c, MAIN, "GET", "/v5/market/open-interest", "category=linear&symbol=$symbol&intervalTime=1h&limit=25", null, false)
                .getJSONObject("result").getJSONArray("list")
            if (l.length() >= 25) l.getJSONObject(24).optString("openInterest", "").toDoubleOrNull() else null
        } catch (_: Exception) { null }
        val longShare = try {
            call(c, MAIN, "GET", "/v5/market/account-ratio", "category=linear&symbol=$symbol&period=1h&limit=1", null, false)
                .getJSONObject("result").getJSONArray("list").let { if (it.length() == 0) null else it.getJSONObject(0).optString("buyRatio", "").toDoubleOrNull() }
        } catch (_: Exception) { null }
        return DerivativesRead.Input(symbol, funding, oiNow, oiOld, chg, longShare)
    }

    fun positions(c: Context): String {
        val l = positionList(c)
        if (l.length() == 0) return "You have no open positions. Mode: " + modeText(c) + "."
        val sb = StringBuilder()
        for (i in 0 until l.length()) {
            val p = l.getJSONObject(i)
            val side = if (p.optString("side") == "Buy") "long" else "short"
            val pnl = p.optString("unrealisedPnl", "0").toDoubleOrNull() ?: 0.0
            sb.append(p.optString("symbol")).append(" ").append(side).append(" size ").append(p.optString("size"))
                .append(" at ").append(p.optString("avgPrice")).append(", profit or loss ").append(money(pnl))
            val sl = p.optString("stopLoss", "")
            if (sl.isNotEmpty() && sl != "0") sb.append(", stop ").append(sl)
            sb.append(". ")
        }
        return sb.toString().trim()
    }

    private fun decimals(step: String): Int {
        val d = BigDecimal(step).stripTrailingZeros().scale()
        return if (d < 0) 0 else d
    }

    private fun fmt(x: Double, dec: Int): String = BigDecimal(x).setScale(dec, RoundingMode.HALF_UP).toPlainString()

    /** Quantity for a position worth `usd` dollars, rounded down to the exchange step. */
    fun quantityFor(c: Context, symbol: String, usd: Double, price: Double): Pair<String, Int> {
        val o = call(c, tradeBase(c), "GET", "/v5/market/instruments-info", "category=linear&symbol=$symbol", null, false)
        val l = o.getJSONObject("result").getJSONArray("list")
        if (l.length() == 0) throw BybitException("I can't find $symbol to trade.")
        val inst = l.getJSONObject(0)
        val lot = inst.getJSONObject("lotSizeFilter")
        val step = BigDecimal(lot.getString("qtyStep"))
        val min = BigDecimal(lot.getString("minOrderQty"))
        val tick = inst.getJSONObject("priceFilter").getString("tickSize")
        val raw = BigDecimal(usd / price)
        val q = raw.divide(step, 0, RoundingMode.DOWN).multiply(step)
        if (q.compareTo(min) < 0) throw BybitException("That is too small for $symbol. The minimum is about " + money(min.toDouble() * price) + " dollars.")
        return Pair(q.stripTrailingZeros().toPlainString(), decimals(tick))
    }

    /** Places a market order with a stop loss and optional take profit. side: long or short. */
    fun order(c: Context, symbol: String, side: String, usd: Double, stop: Double, target: Double?): String {
        val px = price(c, symbol, true)
        val long = side == "long"
        if (long && stop >= px) throw BybitException("For a long, the stop must be below the price (" + Ta.price(px) + ").")
        if (!long && stop <= px) throw BybitException("For a short, the stop must be above the price (" + Ta.price(px) + ").")
        if (target != null) {
            if (long && target <= px) throw BybitException("For a long, the target must be above the price.")
            if (!long && target >= px) throw BybitException("For a short, the target must be below the price.")
        }
        val qp = quantityFor(c, symbol, usd, px)
        val dec = qp.second
        val b = JSONObject()
            .put("category", "linear")
            .put("symbol", symbol)
            .put("side", if (long) "Buy" else "Sell")
            .put("orderType", "Market")
            .put("qty", qp.first)
            .put("positionIdx", 0)
            .put("stopLoss", fmt(stop, dec))
            .put("slTriggerBy", "LastPrice")
        if (target != null) b.put("takeProfit", fmt(target, dec)).put("tpTriggerBy", "LastPrice")
        b.put("tpslMode", "Full")
        call(c, tradeBase(c), "POST", "/v5/order/create", "", b.toString(), true)
        return "Order placed: " + side.uppercase() + " " + qp.first + " " + symbol + " at about " + Ta.price(px) + ", stop " + fmt(stop, dec) +
            (if (target != null) ", target " + fmt(target, dec) else "") + ". Mode: " + modeText(c) + "."
    }

    fun cancelAll(c: Context): String {
        call(c, tradeBase(c), "POST", "/v5/order/cancel-all", "", JSONObject().put("category", "linear").put("settleCoin", "USDT").toString(), true)
        return "All open orders cancelled."
    }

    fun closeAll(c: Context): String {
        val l = positionList(c)
        if (l.length() == 0) return "There was nothing to close."
        var n = 0
        for (i in 0 until l.length()) {
            val p = l.getJSONObject(i)
            val closeSide = if (p.optString("side") == "Buy") "Sell" else "Buy"
            val b = JSONObject()
                .put("category", "linear")
                .put("symbol", p.optString("symbol"))
                .put("side", closeSide)
                .put("orderType", "Market")
                .put("qty", p.optString("size"))
                .put("reduceOnly", true)
                .put("positionIdx", 0)
            call(c, tradeBase(c), "POST", "/v5/order/create", "", b.toString(), true)
            n++
        }
        return "Closed $n position" + (if (n == 1) "" else "s") + "."
    }
}
