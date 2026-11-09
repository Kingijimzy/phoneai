package com.phoneai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Long-term memory as (subject, predicate, object) triplets in SQLite, so Orion can query relationships instead of scanning text. */
internal object OrionFactStore {
    private class Db(c: Context) : SQLiteOpenHelper(c, "orion_facts.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE facts (id INTEGER PRIMARY KEY AUTOINCREMENT, s TEXT NOT NULL, p TEXT NOT NULL, o TEXT NOT NULL, at INTEGER NOT NULL, UNIQUE(s,p,o))")
            db.execSQL("CREATE INDEX idx_facts_s ON facts(s)")
            db.execSQL("CREATE INDEX idx_facts_o ON facts(o)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
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

    /** Short block for the prompt: the freshest facts about the owner. */
    fun promptBlock(c: Context, owner: String): String = runCatching {
        val rows = query(c, s = owner, limit = 12)
        if (rows.startsWith("no matching")) "" else "KNOWN FACTS (subject — predicate — object; use fact_query for more, fact_add to remember new ones):\n$rows"
    }.getOrDefault("")
}
