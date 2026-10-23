package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Trading journal. Every setup Orion spots is written down, then checked against later candles
 * to see whether the target or the stop was hit first. That is how Orion learns which setups work for you.
 */
object Journal {
    private val lock = Any()

    private fun file(c: Context) = File(c.filesDir, "orion_journal.json")

    private fun load(c: Context): JSONArray = try {
        val f = file(c)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    private fun save(c: Context, a: JSONArray) {
        try {
            file(c).writeText(a.toString())
        } catch (e: Exception) {
        }
    }

    /** Returns true if this is a new setup (not a repeat of one still open). */
    fun add(c: Context, s: Setup): Boolean = synchronized(lock) {
        val a = load(c)
        val now = System.currentTimeMillis()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("status") == "open" && o.optString("symbol") == s.symbol &&
                o.optString("type") == s.type && o.optString("side") == s.side &&
                now - o.optLong("time") < 8L * 3600000L
            ) return@synchronized false
        }
        a.put(
            JSONObject().put("time", now).put("symbol", s.symbol).put("tf", s.tf).put("type", s.type)
                .put("side", s.side).put("entry", s.entry).put("stop", s.stop).put("target", s.target)
                .put("status", "open")
        )
        while (a.length() > 300) a.remove(0)
        save(c, a)
        true
    }

    /** Checks open setups for this symbol against fresh candles (oldest first). */
    fun evaluate(c: Context, symbol: String, cs: List<Candle>) {
      synchronized(lock) {
        val a = load(c)
        var changed = false
        val now = System.currentTimeMillis()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("status") != "open" || o.optString("symbol") != symbol) continue
            val t0 = o.optLong("time")
            val stop = o.optDouble("stop")
            val target = o.optDouble("target")
            val long = o.optString("side") == "long"
            var result = ""
            for (k in cs) {
                if (k.t < t0) continue
                val hitStop = if (long) k.l <= stop else k.h >= stop
                val hitTarget = if (long) k.h >= target else k.l <= target
                if (hitStop) {
                    result = "loss"
                    break
                }
                if (hitTarget) {
                    result = "win"
                    break
                }
            }
            if (result.isEmpty() && now - t0 > 72L * 3600000L) result = "expired"
            if (result.isNotEmpty()) {
                o.put("status", result)
                changed = true
            }
        }
        if (changed) save(c, a)
      }
    }

    /** type -> [wins, losses] */
    fun stats(c: Context): Map<String, IntArray> = synchronized(lock) {
        val a = load(c)
        val m = LinkedHashMap<String, IntArray>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val st = o.optString("status")
            if (st != "win" && st != "loss") continue
            val arr = m.getOrPut(o.optString("type")) { IntArray(2) }
            if (st == "win") arr[0]++ else arr[1]++
        }
        m
    }

    /** Win rate of a setup type, or null if there are fewer than 8 finished setups. */
    fun winRate(c: Context, type: String): Double? {
        val s = stats(c)[type] ?: return null
        val n = s[0] + s[1]
        return if (n < 8) null else s[0].toDouble() / n
    }

    fun statsText(c: Context): String {
        val s = stats(c)
        val a = synchronized(lock) { load(c) }
        var open = 0
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("status") == "open") open++
        if (s.isEmpty()) return "No finished setups yet. I have $open open and tracking. Turn on market watch and I will keep score."
        val sb = StringBuilder()
        for ((k, v) in s) {
            val n = v[0] + v[1]
            sb.append(k).append(": ").append(v[0]).append(" wins of ").append(n)
                .append(String.format(Locale.US, " (%.0f%%). ", v[0] * 100.0 / n))
        }
        sb.append("$open still open.")
        return sb.toString()
    }
}
