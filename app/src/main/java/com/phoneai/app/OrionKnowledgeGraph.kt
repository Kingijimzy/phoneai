package com.phoneai.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-device provenance graph adapted from Ultron Cerebro.
 * It complements lexical/hybrid retrieval and never uploads private memories.
 */
internal object OrionKnowledgeGraph {
    private class GraphDb(context: Context) : SQLiteOpenHelper(context, "orion_knowledge_graph.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE nodes (id TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT NOT NULL, normalized TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE edges (id INTEGER PRIMARY KEY AUTOINCREMENT, source_id TEXT NOT NULL, target_id TEXT NOT NULL, relation TEXT NOT NULL, memory_id INTEGER NOT NULL, confidence REAL NOT NULL, provenance TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL DEFAULT 0, UNIQUE(source_id,target_id,relation,memory_id))")
            db.execSQL("CREATE INDEX idx_graph_edge_target ON edges(target_id)")
            db.execSQL("CREATE INDEX idx_graph_edge_source ON edges(source_id)")
            db.execSQL("CREATE INDEX idx_graph_edge_memory ON edges(memory_id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {\n            if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_graph_edge_source ON edges(source_id)")\n        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .trim().replace(Regex("\\s+"), " ").take(100)

    private fun putNode(db: SQLiteDatabase, id: String, kind: String, label: String) {
        val values = ContentValues().apply {
            put("id", id); put("kind", kind); put("label", label); put("normalized", id)
        }
        db.insertWithOnConflict("nodes", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun putEdge(db: SQLiteDatabase, source: String, target: String, relation: String, memory: Memory): Boolean {
        val edge = ContentValues().apply {
            put("source_id", source); put("target_id", target); put("relation", relation)
            put("memory_id", memory.id); put("confidence", memory.confidence)
            put("provenance", memory.sourceRole); put("created_at", System.currentTimeMillis())
        }
        return db.insertWithOnConflict("edges", null, edge, SQLiteDatabase.CONFLICT_REPLACE) != -1L
    }

    /** Index only approved, non-sensitive memories. Every edge retains memory provenance. */
    fun indexMemory(context: Context, memory: Memory): Int {
        if (!MemoryGovernancePolicy.canUseInPrompt(memory.reviewStatus, memory.sensitivity)) return 0
        val terms = normalize(memory.text).split(" ")
            .filter { it.length >= 4 && it !in setOf("user", "that", "with", "from", "this", "have", "will", "your", "about") }
            .distinct().take(12)
        val facts = GraphPolicy.extract(memory.text).take(4)
        if (terms.isEmpty() && facts.isEmpty()) return 0

        val db = GraphDb(context.applicationContext).writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            val memoryNode = "memory:${memory.id}"
            putNode(db, memoryNode, "memory", memory.text.take(100))

            terms.forEach { term ->
                val termId = "term:$term"
                putNode(db, termId, "term", term)
                if (putEdge(db, memoryNode, termId, "mentions", memory)) inserted++
            }

            // Cerebro-style directed entity edges supplement term links. The existing schema
            // already stores relation, confidence and provenance, so no destructive DB migration
            // or parallel graph store is needed.
            facts.forEach { (subject, relation, value) ->
                val a = normalize(subject)
                val b = normalize(value)
                val rel = normalize(relation).replace(" ", "_").take(40)
                if (a.length < 2 || b.length < 2 || rel.isBlank() || a == b) return@forEach
                val source = "entity:$a"
                val target = "entity:$b"
                putNode(db, source, "entity", a)
                putNode(db, target, "entity", b)
                if (putEdge(db, source, target, rel, memory)) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    /**
     * Resolve lexical terms and entity labels, then traverse at most one entity hop.
     * The caller re-applies current memory approval, sensitivity and expiry filters.
     */
    fun relatedMemoryIds(context: Context, query: String, limit: Int = 12): Set<Int> {
        val terms = normalize(query).split(" ").filter { it.length >= 3 }.distinct().take(8)
        if (terms.isEmpty()) return emptySet()
        val db = GraphDb(context.applicationContext).readableDatabase
        val frontier = linkedSetOf<String>()
        terms.forEach { term ->
            db.rawQuery(
                "SELECT id FROM nodes WHERE (kind='term' AND label=?) OR (kind='entity' AND label LIKE ?)",
                arrayOf(term, "%$term%")
            ).use { cursor -> while (cursor.moveToNext()) frontier.add(cursor.getString(0)) }
        }
        if (frontier.isEmpty()) return emptySet()

        val result = linkedSetOf<Int>()
        val visited = linkedSetOf<String>()
        var current = frontier
        repeat(2) {
            val batch = current.filter { visited.add(it) }
            if (batch.isEmpty() || result.size >= limit.coerceIn(1, 30)) return@repeat
            val placeholders = batch.joinToString(",") { "?" }
            val args = (batch + batch + (limit.coerceIn(1, 30) * 4).toString()).toTypedArray()
            val next = linkedSetOf<String>()
            db.rawQuery(
                "SELECT source_id,target_id,memory_id FROM edges WHERE source_id IN ($placeholders) OR target_id IN ($placeholders) ORDER BY confidence DESC LIMIT ?",
                args
            ).use { cursor ->
                while (cursor.moveToNext() && result.size < limit.coerceIn(1, 30)) {
                    val source = cursor.getString(0)
                    val target = cursor.getString(1)
                    result.add(cursor.getInt(2))
                    val neighbor = if (source in batch) target else source
                    // Do not expand through a generic owner node or back through memory nodes.
                    if (neighbor.startsWith("entity:") && neighbor != "entity:user" && neighbor !in visited) next.add(neighbor)
                }
            }
            current = next
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
