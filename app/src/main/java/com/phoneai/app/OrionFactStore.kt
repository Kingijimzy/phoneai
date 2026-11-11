package com.phoneai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Long-term memory as (subject, predicate, object) triplets in SQLite, so Orion can query relationships instead of scanning text. */
internal object OrionFactStore {
    private class Db(c: Context) : SQLiteOpenHelper(c, "orion_facts.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE facts (id INTEGER PRIMARY KEY AUTOINCREMENT, s TEXT NOT NULL, p TEXT NOT NULL, o TEXT NOT NULL, at INTEGER NOT NULL, UNIQUE(s,p,o))")
            db.execSQL("CREATE INDEX idx_facts_s ON facts(s)")
            db.execSQL("CREATE INDEX idx_facts_o ON facts(o)")
            createTom(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { if (o < 2) createTom(db) }
        private fun createTom(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS tom_profile (dimension TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL, confidence REAL NOT NULL, evidence TEXT NOT NULL, source TEXT NOT NULL, updated INTEGER NOT NULL, observations INTEGER NOT NULL DEFAULT 1)")
        }
    }

    private fun norm(v: String) = v.trim().replace(Regex("\\s+"), " ").take(200)

    fun add(c: Context, s: String, p: String, o: String): String {
        val (a, b, d) = Triple(norm(s), norm(p), norm(o))
        if (a.isBlank() || b.isBlank() || d.isBlank()) return "error: fact_add needs subject, predicate and object"
        Db(c).writableDatabase.use {
            it.execSQL("INSERT OR REPLACE INTO facts(s,p,o,at) VALUES(?,?,?,?)", arrayOf(a, b, d, System.currentTimeMillis()))
        }
        return "remembered: $a — $b — $d"
    }

    /** Any blank field is a wildcard; `match` also does a substring search over all three. */
    fun query(c: Context, s: String = "", p: String = "", o: String = "", match: String = "", limit: Int = 30): String {
        val where = mutableListOf<String>(); val args = mutableListOf<String>()
        if (s.isNotBlank()) { where += "lower(s)=lower(?)"; args += norm(s) }
        if (p.isNotBlank()) { where += "lower(p)=lower(?)"; args += norm(p) }
        if (o.isNotBlank()) { where += "lower(o)=lower(?)"; args += norm(o) }
        if (match.isNotBlank()) { where += "(s LIKE ? OR p LIKE ? OR o LIKE ?)"; repeat(3) { args += "%${norm(match)}%" } }
        val sql = "SELECT s,p,o FROM facts" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY at DESC LIMIT ${limit.coerceIn(1, 100)}"
        val rows = mutableListOf<String>()
        Db(c).readableDatabase.use { db -> db.rawQuery(sql, args.toTypedArray()).use { while (it.moveToNext()) rows += "${it.getString(0)} — ${it.getString(1)} — ${it.getString(2)}" } }
        return if (rows.isEmpty()) "no matching facts" else rows.joinToString("\n")
    }

    fun forget(c: Context, s: String, p: String, o: String): String {
        val n = Db(c).writableDatabase.use { it.delete("facts", "lower(s)=lower(?) AND lower(p)=lower(?) AND lower(o)=lower(?)", arrayOf(norm(s), norm(p), norm(o))) }
        return if (n > 0) "forgot $n fact(s)" else "no such fact"
    }

    data class Tom(val dimension: String, val value: String, val confidence: Double, val evidence: String, val source: String, val observations: Int)

    /**
     * Theory-of-mind preference profile: revisable, evidence-backed hypotheses about how Kingi likes to work
     * (any dimension Orion judges useful, e.g. detail level, initiative, tone). Agreement raises confidence,
     * a contradicting observation must beat the current belief to replace it, and values Kingi set himself are never overwritten.
     */
    fun tomObserve(c: Context, dimension: String, value: String, confidence: Double, evidence: String, owner: Boolean = false): Boolean {
        val d = norm(dimension).lowercase().replace(' ', '_').take(40); val v = norm(value).take(160)
        if (d.isBlank() || v.isBlank() || (!owner && evidence.isBlank())) return false
        Db(c).writableDatabase.use { db ->
            val old = db.rawQuery("SELECT value,confidence,source,observations FROM tom_profile WHERE dimension=?", arrayOf(d)).use {
                if (it.moveToFirst()) Tom(d, it.getString(0), it.getDouble(1), "", it.getString(2), it.getInt(3)) else null
            }
            val next = mergeTom(old?.value, old?.confidence ?: 0.0, old?.source ?: "", old?.observations ?: 0, v, confidence.coerceIn(0.0, 1.0), owner) ?: return false
            db.execSQL("INSERT OR REPLACE INTO tom_profile(dimension,value,confidence,evidence,source,updated,observations) VALUES(?,?,?,?,?,?,?)",
                arrayOf(d, next.first, next.second, norm(evidence).take(200), if (owner) "owner" else "inferred", System.currentTimeMillis(), next.third))
        }
        return true
    }

    /** Pure merge rule: returns (value, confidence, observations) to store, or null to keep what is there. */
    internal fun mergeTom(oldValue: String?, oldConf: Double, oldSource: String, oldObs: Int, value: String, conf: Double, owner: Boolean): Triple<String, Double, Int>? {
        if (owner) return Triple(value, 1.0, oldObs + 1)
        if (oldValue == null) return if (conf >= 0.5) Triple(value, conf.coerceAtMost(0.8), 1) else null
        if (oldSource == "owner") return null
        return if (oldValue.equals(value, ignoreCase = true)) Triple(oldValue, (oldConf + (1 - oldConf) * conf * 0.5).coerceAtMost(0.97), oldObs + 1)
        else if (conf > oldConf + 0.1) Triple(value, conf.coerceAtMost(0.8), 1)
        else null
    }

    fun tomProfile(c: Context): List<Tom> = runCatching {
        Db(c).readableDatabase.use { db ->
            db.rawQuery("SELECT dimension,value,confidence,evidence,source,observations FROM tom_profile ORDER BY confidence DESC, updated DESC LIMIT 14", null).use {
                buildList { while (it.moveToNext()) add(Tom(it.getString(0), it.getString(1), it.getDouble(2), it.getString(3), it.getString(4), it.getInt(5))) }
            }
        }
    }.getOrDefault(emptyList())

    fun tomBlock(c: Context): String {
        val p = tomProfile(c).filter { it.confidence >= 0.5 }
        if (p.isEmpty()) return ""
        return "KINGI'S PREFERENCE PROFILE (theory of mind: hypotheses from evidence, revisable; his current words always win):\n" +
            p.joinToString("\n") { "- ${it.dimension}: ${it.value} (${(it.confidence * 100).toInt()}%, ${it.source}, seen ${it.observations}x)" }
    }

    /** Short block for the prompt: the freshest facts about the owner. */
    fun promptBlock(c: Context, owner: String): String = runCatching {
        val rows = query(c, s = owner, limit = 12)
        if (rows.startsWith("no matching")) "" else "KNOWN FACTS (subject — predicate — object; use fact_query for more, fact_add to remember new ones):\n$rows"
    }.getOrDefault("")
}
