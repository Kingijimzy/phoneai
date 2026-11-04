package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class ImportedTrade(
    val fingerprint: String,
    val symbol: String,
    val side: String,
    val quantity: Double?,
    val entryPrice: Double?,
    val exitPrice: Double?,
    val pnl: Double?,
    val openedAt: String,
    val closedAt: String,
    val source: String,
    val importedAt: Long
)

data class TradeImportResult(
    val imported: Int,
    val duplicates: Int,
    val skipped: Int,
    val total: Int,
    val error: String? = null
)

/** Local-only CSV trade history. Only selected, normalized columns are stored; the source file is never uploaded. */
object TradeHistoryStore {
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "orion_trade_history.json")
    private const val MAX_RECORDS = 10000

    private fun parseNumber(raw: String?): Double? {
        if (raw.isNullOrBlank() || raw.trim() in setOf("-", "--", "—", "N/A", "n/a")) return null
        val text = raw.trim()
        val negative = text.startsWith("(") && text.endsWith(")")
        val cleaned = text.replace("−", "-").replace(",", "")
            .replace(Regex("[^0-9eE+\\-.]"), "")
        val value = cleaned.toDoubleOrNull() ?: return null
        return if (negative) -kotlin.math.abs(value) else value
    }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase(Locale.US).filter { it.isLetterOrDigit() }

    private fun findHeader(headers: List<String>, aliases: Set<String>): Int {
        val normalizedAliases = aliases.map { normalizeHeader(it) }.toSet()
        return headers.indexOfFirst { normalizeHeader(it) in normalizedAliases }
    }

    /** RFC-4180-style parser for quoted commas, escaped quotes and quoted newlines. */
    internal fun parseCsv(text: String): List<List<String>> {
        val sample = text.lineSequence().take(20).joinToString("\n")
        val delimiter = if (sample.count { it == ';' } > sample.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (text.startsWith("\uFEFF")) 1 else 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == delimiter && !quoted -> {
                    row.add(field.toString().trim())
                    field.setLength(0)
                }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString().trim())
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(ch)
            }
            i++
        }
        row.add(field.toString().trim())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    private fun fingerprint(parts: List<String>): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun decode(c: Context): MutableList<ImportedTrade> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try { fromJson(JSONArray(f.readText())) } catch (_: Exception) { mutableListOf() }
    }

    private fun fromJson(arr: JSONArray): MutableList<ImportedTrade> {
        return try {
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ImportedTrade(
                    o.optString("fingerprint"), o.optString("symbol"), o.optString("side"),
                    if (o.isNull("quantity")) null else o.optDouble("quantity"),
                    if (o.isNull("entryPrice")) null else o.optDouble("entryPrice"),
                    if (o.isNull("exitPrice")) null else o.optDouble("exitPrice"),
                    if (o.isNull("pnl")) null else o.optDouble("pnl"),
                    o.optString("openedAt"), o.optString("closedAt"), o.optString("source"), o.optLong("importedAt")
                )
            }
        } catch (_: Exception) { mutableListOf() }
    }

    private fun encode(t: ImportedTrade): JSONObject = JSONObject()
        .put("fingerprint", t.fingerprint).put("symbol", t.symbol).put("side", t.side)
        .put("quantity", t.quantity ?: JSONObject.NULL)
        .put("entryPrice", t.entryPrice ?: JSONObject.NULL)
        .put("exitPrice", t.exitPrice ?: JSONObject.NULL)
        .put("pnl", t.pnl ?: JSONObject.NULL)
        .put("openedAt", t.openedAt).put("closedAt", t.closedAt)
        .put("source", t.source).put("importedAt", t.importedAt)

    private fun save(c: Context, records: List<ImportedTrade>): Boolean {
        val target = file(c)
        val temp = File(c.filesDir, "orion_trade_history.json.tmp")
        return try {
            val arr = JSONArray()
            records.takeLast(MAX_RECORDS).forEach { arr.put(encode(it)) }
            temp.writeText(arr.toString())
            if (temp.renameTo(target)) true else {
                target.writeText(arr.toString())
                temp.delete()
                true
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    /** The whole journal as JSON, for backups. */
    internal fun exportJson(c: Context): JSONArray = synchronized(lock) { JSONArray().also { a -> decode(c).forEach { a.put(encode(it)) } } }

    /** Merges a backed-up journal, skipping trades already present. Returns how many were new. */
    internal fun importJson(c: Context, arr: JSONArray): Int = addRecords(c, fromJson(arr).filter { it.fingerprint.isNotBlank() })

    fun all(c: Context): List<ImportedTrade> = synchronized(lock) { decode(c).sortedByDescending { it.importedAt } }

    /** Adds already-normalised records (for example from a broker sync), skipping ones seen before. Returns how many were new. */
    fun addRecords(c: Context, incoming: List<ImportedTrade>): Int = synchronized(lock) {
        val records = decode(c)
        val seen = records.map { it.fingerprint }.toHashSet()
        var added = 0
        for (r in incoming) if (seen.add(r.fingerprint)) { records.add(r); added++ }
        if (added > 0 && !save(c, records)) 0 else added
    }

    fun importCsv(c: Context, csv: String, sourceName: String): TradeImportResult = synchronized(lock) {
        if (csv.isBlank()) return@synchronized TradeImportResult(0, 0, 0, decode(c).size, "The selected file is empty.")
        if (csv.length > 10 * 1024 * 1024) return@synchronized TradeImportResult(0, 0, 0, decode(c).size, "CSV exceeds the 10 MB safety limit.")
        val rows = parseCsv(csv)
        if (rows.size < 2) return@synchronized TradeImportResult(0, 0, rows.size, decode(c).size, "No data rows were found.")
        var headerRow = -1
        var symbolIdx = -1
        var sideIdx = -1
        var qtyIdx = -1
        var entryIdx = -1
        var exitIdx = -1
        var pnlIdx = -1
        var openedIdx = -1
        var closedIdx = -1
        val symbolAliases = setOf("symbol", "instrument", "item", "market", "contract", "pair")
        val sideAliases = setOf("side", "direction", "type", "positionside", "action")
        val qtyAliases = setOf("qty", "quantity", "size", "volume", "lots", "orderqty", "executedqty")
        val entryAliases = setOf("entryprice", "openprice", "priceopen", "avgentryprice", "entry", "averageentryprice")
        val exitAliases = setOf("exitprice", "closeprice", "priceclose", "avgexitprice", "exit", "averageexitprice")
        val pnlAliases = setOf("closedpnl", "closedpl", "realizedpnl", "realizedpl", "profit", "netprofit", "pnl", "pl", "profitloss")
        val openAliases = setOf("opentime", "timeopen", "entrytime", "openat", "createdtime", "opendate")
        val closeAliases = setOf("closetime", "timeclose", "exittime", "closedat", "updatedtime", "closedate", "time", "date")
        for (i in 0 until minOf(rows.size, 20)) {
            val candidate = rows[i]
            val s = findHeader(candidate, symbolAliases)
            val p = findHeader(candidate, pnlAliases)
            val e = findHeader(candidate, entryAliases)
            val x = findHeader(candidate, exitAliases)
            if (s >= 0 && (p >= 0 || e >= 0 || x >= 0)) {
                headerRow = i
                symbolIdx = s
                sideIdx = findHeader(candidate, sideAliases)
                qtyIdx = findHeader(candidate, qtyAliases)
                entryIdx = e
                exitIdx = x
                pnlIdx = p
                openedIdx = findHeader(candidate, openAliases)
                closedIdx = findHeader(candidate, closeAliases)
                break
            }
        }
        if (headerRow < 0) {
            return@synchronized TradeImportResult(0, 0, rows.size, decode(c).size,
                "Could not identify CSV headers. Need a symbol/instrument column and at least one P&L or entry/exit price column.")
        }
        val records = decode(c)
        val seen = records.mapTo(HashSet()) { it.fingerprint }
        var imported = 0
        var duplicates = 0
        var skipped = 0
        val source = sourceName.substringAfterLast('/').replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "imported CSV" }
        for (r in rows.drop(headerRow + 1)) {
            if (records.size >= MAX_RECORDS) { skipped++; continue }
            if (symbolIdx !in r.indices) { skipped++; continue }
            val symbol = r[symbolIdx].uppercase(Locale.US).replace(Regex("[^A-Z0-9._/-]"), "")
            if (symbol.length !in 3..30) { skipped++; continue }
            fun cell(index: Int) = if (index in r.indices) r[index].trim() else ""
            val sideRaw = cell(sideIdx).lowercase(Locale.US)
            val side = when {
                sideRaw.contains("buy") || sideRaw.contains("long") -> "long"
                sideRaw.contains("sell") || sideRaw.contains("short") -> "short"
                sideRaw.isBlank() -> "unknown"
                else -> sideRaw.take(24)
            }
            val qty = parseNumber(cell(qtyIdx))
            val entry = parseNumber(cell(entryIdx))
            val exit = parseNumber(cell(exitIdx))
            val pnl = parseNumber(cell(pnlIdx))
            if (pnl == null && entry == null && exit == null) { skipped++; continue }
            val opened = cell(openedIdx).take(48)
            val closed = cell(closedIdx).take(48)
            val key = fingerprint(listOf(symbol, side, qty?.toString().orEmpty(), entry?.toString().orEmpty(),
                exit?.toString().orEmpty(), pnl?.toString().orEmpty(), opened, closed))
            if (!seen.add(key)) { duplicates++; continue }
            records.add(ImportedTrade(key, symbol, side, qty, entry, exit, pnl, opened, closed, source, System.currentTimeMillis()))
            imported++
        }
        if (!save(c, records)) {
            return@synchronized TradeImportResult(0, duplicates, skipped, decode(c).size, "Storage failed. No import was confirmed.")
        }
        TradeImportResult(imported, duplicates, skipped, records.size)
    }

    fun summaryText(c: Context): String {
        val trades = all(c)
        if (trades.isEmpty()) {
            return "No imported trade history yet. Import a CSV export from MT5, Bybit or another platform. Only normalized trade fields are saved locally; the CSV is not uploaded and no orders are placed."
        }
        val withPnl = trades.filter { it.pnl != null }
        val wins = withPnl.count { it.pnl!! > 0.0 }
        val losses = withPnl.count { it.pnl!! < 0.0 }
        val flat = withPnl.size - wins - losses
        val rate = if (wins + losses > 0) wins * 100.0 / (wins + losses) else 0.0
        val bySource = withPnl.groupBy { it.source }.entries
            .sortedByDescending { it.value.sumOf { trade -> trade.pnl ?: 0.0 } }
            .take(5)
        val sourceText = if (bySource.isEmpty()) "No P&L columns were recognized." else bySource.joinToString("; ") { (source, rows) ->
            val total = rows.sumOf { it.pnl ?: 0.0 }
            "$source: ${String.format(Locale.US, "%+.2f", total)} across ${rows.size} trades"
        }
        return "Imported records: ${trades.size}. With P&L: ${withPnl.size}; wins: $wins; losses: $losses; flat: $flat; win rate excluding flat trades: ${String.format(Locale.US, "%.1f", rate)}%. P&L by source (currency may differ between files): $sourceText. This is historical data, not a forecast or trading instruction."
    }

    fun recentText(c: Context, limit: Int = 12): String = all(c).take(limit.coerceIn(1, 30)).joinToString("\n") { t ->
        "${t.symbol} · ${t.side} · P&L ${t.pnl?.let { String.format(Locale.US, "%+.2f", it) } ?: "not provided"} · ${t.closedAt.ifBlank { t.openedAt.ifBlank { "time unknown" } }} · source ${t.source}"
    }
}
