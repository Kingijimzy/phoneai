package com.phoneai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

/**
 * Small on-device provenance graph. It complements lexical/hybrid memory retrieval; it is not
 * a remote vector database and does not upload private memories.
 */
internal object OrionKnowledgeGraph {
    private class GraphDb(context: Context) : SQLiteOpenHelper(context, "orion_knowledge_graph.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE nodes (id TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT NOT NULL, normalized TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE edges (id INTEGER PRIMARY KEY AUTOINCREMENT, source_id TEXT NOT NULL, target_id TEXT NOT NULL, relation TEXT NOT NULL, memory_id INTEGER NOT NULL, confidence REAL NOT NULL, provenance TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL DEFAULT 0, UNIQUE(source_id,target_id,relation,memory_id))")
            db.execSQL("CREATE INDEX idx_graph_edge_target ON edges(target_id)")
            db.execSQL("CREATE INDEX idx_graph_edge_memory ON edges(memory_id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .trim().replace(Regex("\\s+"), " ").take(100)

    /** Index only approved, non-sensitive memories. Entity extraction is deterministic and bounded. */
    fun indexMemory(context: Context, memory: Memory): Int {
        if (!MemoryGovernancePolicy.canUseInPrompt(memory.reviewStatus, memory.sensitivity)) return 0
        val terms = normalize(memory.text).split(" ")
            .filter { it.length >= 4 && it !in setOf("user", "that", "with", "from", "this", "have", "will", "your", "about") }
            .distinct().take(12)
        if (terms.isEmpty()) return 0
        val db = GraphDb(context.applicationContext).writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            val memoryNode = "memory:${memory.id}"
            val nodeValues = ContentValues().apply {
                put("id", memoryNode); put("kind", "memory"); put("label", memory.text.take(100)); put("normalized", memoryNode)
            }
            db.insertWithOnConflict("nodes", null, nodeValues, SQLiteDatabase.CONFLICT_REPLACE)
            terms.forEach { term ->
                val entityId = "term:$term"
                val entityValues = ContentValues().apply {
                    put("id", entityId); put("kind", "term"); put("label", term); put("normalized", entityId)
                }
                db.insertWithOnConflict("nodes", null, entityValues, SQLiteDatabase.CONFLICT_IGNORE)
                val edge = ContentValues().apply {
                    put("source_id", memoryNode); put("target_id", entityId); put("relation", "mentions")
                    put("memory_id", memory.id); put("confidence", memory.confidence)
                    put("provenance", memory.sourceRole); put("created_at", System.currentTimeMillis())
                }
                if (db.insertWithOnConflict("edges", null, edge, SQLiteDatabase.CONFLICT_REPLACE) != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    /** Bounded graph expansion for a query; caller still applies memory governance and ranking. */
    fun relatedMemoryIds(context: Context, query: String, limit: Int = 12): Set<Int> {
        val terms = normalize(query).split(" ").filter { it.length >= 4 }.distinct().take(8)
        if (terms.isEmpty()) return emptySet()
        val db = GraphDb(context.applicationContext).readableDatabase
        val placeholders = terms.joinToString(",") { "?" }
        val args = terms.toTypedArray()
        val result = linkedSetOf<Int>()
        db.rawQuery(
            "SELECT DISTINCT e.memory_id FROM edges e JOIN nodes n ON n.id=e.target_id WHERE n.label IN ($placeholders) ORDER BY e.confidence DESC LIMIT ?",
            args + limit.coerceIn(1, 30).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getInt(0))
        }
        return result
    }

    fun forgetMemory(context: Context, memoryId: Int) {
        val db = GraphDb(context.applicationContext).writableDatabase
        db.delete("edges", "memory_id=?", arrayOf(memoryId.toString()))
        db.delete("nodes", "id=?", arrayOf("memory:$memoryId"))
    }

    fun clear(context: Context) {
        val db = GraphDb(context.applicationContext).writableDatabase
        db.delete("edges", null, null)
        db.delete("nodes", null, null)
    }
}
