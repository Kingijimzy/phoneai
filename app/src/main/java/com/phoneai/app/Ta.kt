package com.phoneai.app

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

class Setup(
    val symbol: String,
    val tf: String,
    val type: String,
    val side: String,
    val entry: Double,
    val stop: Double,
    val target: Double,
    var score: Int,
    val note: String
) {
    fun rr(): Double = if (entry == stop) 0.0 else abs(target - entry) / abs(entry - stop)
}

/** Chart maths: moving averages, RSI, ATR and simple setup detection. Runs on the phone, no AI. */
object Ta {

    fun ema(v: List<Double>, p: Int): List<Double> {
        val out = ArrayList<Double>(v.size)
        if (v.isEmpty()) return out
        val k = 2.0 / (p + 1)
        var e = v[0]
        for (x in v) {
            e = x * k + e * (1 - k)
            out.add(e)
        }
        return out
    }

    fun rsi(c: List<Double>, p: Int = 14): List<Double> {
        val out = ArrayList<Double>(c.size)
        if (c.size <= p) {
            for (i in c.indices) out.add(50.0)
            return out
        }
        var gain = 0.0
        var loss = 0.0
        for (i in 1..p) {
            val d = c[i] - c[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        gain /= p
        loss /= p
        for (i in 0 until p) out.add(50.0)
        out.add(if (loss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + gain / loss))
        for (i in p + 1 until c.size) {
            val d = c[i] - c[i - 1]
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            gain = (gain * (p - 1) + g) / p
            loss = (loss * (p - 1) + l) / p
            out.add(if (loss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + gain / loss))
        }
        return out
    }

    fun atr(cs: List<Candle>, p: Int = 14): List<Double> {
        val out = ArrayList<Double>(cs.size)
        if (cs.isEmpty()) return out
        var a = cs[0].h - cs[0].l
        out.add(a)
        for (i in 1 until cs.size) {
            val prev = cs[i - 1].c
            val tr = max(cs[i].h - cs[i].l, max(abs(cs[i].h - prev), abs(cs[i].l - prev)))
            a = if (i < p) (a * i + tr) / (i + 1) else (a * (p - 1) + tr) / p
            out.add(a)
        }
        return out
    }

    fun price(x: Double): String = when {
        x >= 1000 -> String.format(Locale.US, "%.1f", x)
        x >= 100 -> String.format(Locale.US, "%.2f", x)
        x >= 1 -> String.format(Locale.US, "%.3f", x)
        else -> String.format(Locale.US, "%.5f", x)
    }

    /** 1 = uptrend, -1 = downtrend, 0 = sideways, from closed candles. */
    fun trend(cs: List<Candle>): Int {
        if (cs.size < 55) return 0
        val closes = cs.map { it.c }
        val e20 = ema(closes, 20)
        val e50 = ema(closes, 50)
        val i = cs.size - 1
        return when {
            e20[i] > e50[i] && closes[i] > e50[i] -> 1
            e20[i] < e50[i] && closes[i] < e50[i] -> -1
            else -> 0
        }
    }

    private fun trendWord(t: Int) = when (t) {
        1 -> "uptrend"
        -1 -> "downtrend"
        else -> "sideways"
    }

    /** One short line of facts about a chart, used for talking and for the AI. */
    fun summary(symbol: String, tf: String, cs: List<Candle>): String {
        if (cs.size < 55) return "$symbol $tf: not enough data."
        val closes = cs.map { it.c }
        val r = rsi(closes).last()
        val a = atr(cs).last()
        val last = cs.last()
        val from = max(0, cs.size - 30)
        var hi = Double.MIN_VALUE
        var lo = Double.MAX_VALUE
        for (i in from until cs.size) {
            hi = max(hi, cs[i].h)
            lo = min(lo, cs[i].l)
        }
        var vs = 0.0
        for (i in cs.size - 21 until cs.size - 1) vs += cs[i].v
        val vr = if (vs > 0) last.v / (vs / 20.0) else 1.0
        val e200 = if (cs.size >= 100) " Price is " + (if (last.c > ema(closes, 200).last()) "above" else "below") + " the 200 average." else ""
        return String.format(
            Locale.US,
            "%s %s: price %s, %s, RSI %.0f, volatility %.1f%%, 30-candle range %s to %s, volume %.1fx average.%s",
            symbol, tf, price(last.c), trendWord(trend(cs)), r, a / last.c * 100.0, price(lo), price(hi), vr, e200
        )
    }

    /** Looks for pullback, breakout and reversal setups on the last CLOSED candle. htf = 1/-1/0 higher-timeframe trend. */
    fun scan(symbol: String, tf: String, cs: List<Candle>, htf: Int): List<Setup> {
        val out = ArrayList<Setup>()
        val n = cs.size
        if (n < 60) return out
        val closes = cs.map { it.c }
        val e20 = ema(closes, 20)
        val e50 = ema(closes, 50)
        val rs = rsi(closes)
        val at = atr(cs)
        val i = n - 1
        val p = closes[i]
        val a = at[i]
        val r = rs[i]
        if (a <= 0.0) return out
        var vs = 0.0
        for (k in i - 20 until i) vs += cs[k].v
        val vr = if (vs > 0) cs[i].v / (vs / 20.0) else 1.0
        val up = e20[i] > e50[i] && p > e50[i]
        val down = e20[i] < e50[i] && p < e50[i]
        var low4 = Double.MAX_VALUE
        var high4 = Double.MIN_VALUE
        for (k in i - 3..i) {
            low4 = min(low4, cs[k].l)
            high4 = max(high4, cs[k].h)
        }
        var hh = Double.MIN_VALUE
        var ll = Double.MAX_VALUE
        for (k in i - 20 until i) {
            hh = max(hh, cs[k].h)
            ll = min(ll, cs[k].l)
        }
        val bull = cs[i].c > cs[i].o
        val bear = cs[i].c < cs[i].o

        fun add(type: String, side: String, stop: Double, rMult: Double, note: String) {
            val risk = abs(p - stop)
            val riskPct = risk / p * 100.0
            if (riskPct < 0.2 || riskPct > 8.0) return
            val target = if (side == "long") p + rMult * risk else p - rMult * risk
            var score = 55
            if (htf == 1 && side == "long") score += 10
            if (htf == -1 && side == "short") score += 10
            if (htf == 1 && side == "short") score -= 10
            if (htf == -1 && side == "long") score -= 10
            if (vr >= 1.5) score += 8
            if (rMult >= 2.0) score += 7
            out.add(Setup(symbol, tf, type, side, p, stop, target, score, note))
        }

        // 1. pullback to the 20 average inside a trend
        if (up && abs(p - e20[i]) <= 0.6 * a && r in 38.0..58.0 && bull) {
            add("pullback", "long", min(low4, p - 1.2 * a) - 0.1 * a, 2.2, "Price pulled back to the 20 average in an uptrend and bounced.")
        }
        if (down && abs(p - e20[i]) <= 0.6 * a && r in 42.0..62.0 && bear) {
            add("pullback", "short", max(high4, p + 1.2 * a) + 0.1 * a, 2.2, "Price pulled back to the 20 average in a downtrend and turned down.")
        }
        // 2. breakout of the 20-candle range with volume
        if (p > hh && vr >= 1.3 && r < 78.0) {
            add("breakout", "long", min(hh - 0.5 * a, p - 1.0 * a), 2.0, "Closed above the 20-candle high with strong volume.")
        }
        if (p < ll && vr >= 1.3 && r > 22.0) {
            add("breakout", "short", max(ll + 0.5 * a, p + 1.0 * a), 2.0, "Closed below the 20-candle low with strong volume.")
        }
        // 3. reversal from an extreme RSI
        if (r <= 28.0 && bull && p > cs[i - 1].c) {
            add("reversal", "long", low4 - 0.2 * a, 1.8, "Oversold (RSI " + r.toInt() + ") and a bounce candle appeared.")
        }
        if (r >= 72.0 && bear && p < cs[i - 1].c) {
            add("reversal", "short", high4 + 0.2 * a, 1.8, "Overbought (RSI " + r.toInt() + ") and a rejection candle appeared.")
        }
        return out
    }

    fun describe(s: Setup): String =
        s.side.uppercase() + " " + s.symbol + " on " + s.tf + " (" + s.type + "). Entry " + price(s.entry) +
            ", stop " + price(s.stop) + ", target " + price(s.target) +
            String.format(Locale.US, ", reward to risk %.1f, score %d. ", s.rr(), s.score) + s.note
}
