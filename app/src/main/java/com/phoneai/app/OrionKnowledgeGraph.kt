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
    data class Snapshot(
        val indexedMemories: Int,
        val entities: Int,
        val relationships: Int,
        val terms: Int,
        val recentFacts: List<String>,
        val recentEntities: List<String>
    )

    private const val INDEX_VERSION = 3
    private const val PREFS = "orion_knowledge_graph_index"
    private val rebuildLock = Any()

    private class GraphDb(context: Context) : SQLiteOpenHelper(context, "orion_knowledge_graph.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE nodes (id TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT NOT NULL, normalized TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE edges (id INTEGER PRIMARY KEY AUTOINCREMENT, source_id TEXT NOT NULL, target_id TEXT NOT NULL, relation TEXT NOT NULL, memory_id INTEGER NOT NULL, confidence REAL NOT NULL, provenance TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL DEFAULT 0, UNIQUE(source_id,target_id,relation,memory_id))")
            db.execSQL("CREATE INDEX idx_graph_edge_target ON edges(target_id)")
            db.execSQL("CREATE INDEX idx_graph_edge_source ON edges(source_id)")
            db.execSQL("CREATE INDEX idx_graph_edge_memory ON edges(memory_id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_graph_edge_source ON edges(source_id)")
        }
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
        if (!MemoryGovernancePolicy.canUseInPrompt(memory.reviewStatus, memory.sensitivity) ||
            !OrionMemoryLifecycle.isActive(context, memory.id)) return 0
        val terms = normalize(memory.text).split(" ")
            .filter { it.length >= 4 && it !in setOf("user", "that", "with", "from", "this", "have", "will", "your", "about") }
            .distinct().take(12)
        val facts = GraphPolicy.extract(memory.text).take(4)
        val entities = OrionEntityIndex.extract(memory.text).distinctBy { it.key }.take(12)
        if (terms.isEmpty() && facts.isEmpty() && entities.isEmpty()) return 0

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

            // Entity mentions make people, places, projects and dates queryable even when
            // the source sentence has no simple subject-verb-object grammar.
            val entityIds = entities.map { entity ->
                val label = normalize(entity.name)
                val id = "entity:${entity.kind.name.lowercase()}:$label"
                putNode(db, id, "entity", label)
                if (putEdge(db, memoryNode, id, "mentions_${entity.kind.name.lowercase()}", memory)) inserted++
                id
            }.toMutableList()

            // Give the canonical "user" subject a searchable owner-name alias.
            if (facts.any { it.first == "user" }) {
                val ownerName = Prefs.userName(context).trim()
                if (ownerName.isNotBlank()) {
                    val ownerLabel = normalize(ownerName)
                    val ownerId = "entity:person:$ownerLabel"
                    putNode(db, ownerId, "entity", ownerLabel)
                    if (putEdge(db, memoryNode, ownerId, "owner_alias", memory)) inserted++
                    entityIds += ownerId
                }
            }

            // Co-occurrence links connect entities mentioned in the same approved memory.
            for (i in entityIds.indices) {
                for (j in i + 1 until entityIds.size) {
                    if (putEdge(db, entityIds[i], entityIds[j], "co_occurs", memory)) inserted++
                }
            }

            facts.forEach { (subject, relation, value) ->
                val a = normalize(subject)
                val b = normalize(value)
                val rel = normalize(relation).replace(" ", "_").take(40)
                if (a.length < 2 || b.length < 2 || rel.isBlank() || a == b) return@forEach
                val source = "entity:fact:$a"
                val target = "entity:fact:$b"
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
     * Upgrade/rebuild the derived index from current approved memories. This also repairs
     * older installs whose memories existed before SQLite graph indexing was introduced.
     */
    fun ensureIndexed(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt("version", 0) >= INDEX_VERSION) return
        rebuild(app)
    }

    /** Rebuild from source-of-truth memory records; safe to run repeatedly. */
    fun rebuild(context: Context): Int = synchronized(rebuildLock) {
        val app = context.applicationContext
        val eligible = MemoryStore.all(app).filter {
            MemoryGovernancePolicy.canUseInPrompt(it.reviewStatus, it.sensitivity) &&
                OrionMemoryLifecycle.isActive(app, it.id)
        }
        val db = GraphDb(app).writableDatabase
        db.beginTransaction()
        try {
            db.delete("edges", null, null)
            db.delete("nodes", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        var indexed = 0
        eligible.forEach { if (indexMemory(app, it) > 0) indexed++ }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("version", INDEX_VERSION).putLong("indexed_at", System.currentTimeMillis()).apply()
        indexed
    }

    /** Resolve lexical terms and entity labels, then traverse at most two graph hops. */
    fun relatedMemoryIds(context: Context, query: String, limit: Int = 12): Set<Int> {
        ensureIndexed(context)
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
                    // The canonical owner subject is shared by every first-person fact.
                    // Traversing through it would turn "I trade forex" into a bridge to
                    // every unrelated owner fact (e.g. "I like football").
                    if (neighbor != "entity:fact:user" &&
                        (neighbor.startsWith("entity:") || neighbor.startsWith("term:")) &&
                        neighbor !in visited) next.add(neighbor)
                }
            }
            current = next
        }
        return result
    }

    fun snapshot(context: Context): Snapshot {
        ensureIndexed(context)
        val db = GraphDb(context.applicationContext).readableDatabase
        fun count(sql: String): Int = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val indexed = count("SELECT COUNT(DISTINCT memory_id) FROM edges")
        val entities = count("SELECT COUNT(*) FROM nodes WHERE kind='entity'")
        val terms = count("SELECT COUNT(*) FROM nodes WHERE kind='term'")
        val relationships = count("SELECT COUNT(*) FROM edges WHERE relation NOT IN ('mentions','co_occurs') AND relation NOT LIKE 'mentions_%'")
        val facts = mutableListOf<String>()
        db.rawQuery(
            "SELECT s.label,e.relation,t.label FROM edges e JOIN nodes s ON s.id=e.source_id JOIN nodes t ON t.id=e.target_id WHERE s.kind='entity' AND t.kind='entity' AND e.relation NOT LIKE 'mentions_%' AND e.relation!='co_occurs' ORDER BY e.created_at DESC LIMIT 8",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) facts += "${cursor.getString(0)} —${cursor.getString(1).replace('_', ' ')}→ ${cursor.getString(2)}"
        }
        val entityLabels = mutableListOf<String>()
        db.rawQuery("SELECT kind,label FROM nodes WHERE kind='entity' ORDER BY label LIMIT 10", null).use { cursor ->
            while (cursor.moveToNext()) entityLabels += "${cursor.getString(0).uppercase()}: ${cursor.getString(1)}"
        }
        return Snapshot(indexed, entities, relationships, terms, facts.distinct(), entityLabels)
    }

    /** Approved source memories reached through the graph, re-filtered at read time. */
    fun relatedMemories(context: Context, query: String, limit: Int = 8): List<Memory> {
        val ids = relatedMemoryIds(context, query, limit * 3)
        if (ids.isEmpty()) return emptyList()
        return MemoryStore.all(context).filter {
            it.id in ids && MemoryGovernancePolicy.canUseInPrompt(it.reviewStatus, it.sensitivity) &&
                OrionMemoryLifecycle.isActive(context, it.id)
        }.sortedByDescending { it.updated }.take(limit.coerceIn(1, 20))
    }

    fun forgetMemory(context: Context, memoryId: Int) {
        val db = GraphDb(context.applicationContext).writableDatabase
        db.delete("edges", "memory_id=?", arrayOf(memoryId.toString()))
        db.delete("nodes", "id=?", arrayOf("memory:$memoryId"))
        db.execSQL("DELETE FROM nodes WHERE id NOT IN (SELECT source_id FROM edges UNION SELECT target_id FROM edges)")
    }

    fun clear(context: Context) {
        val db = GraphDb(context.applicationContext).writableDatabase
        db.delete("edges", null, null)
        db.delete("nodes", null, null)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("version", 0).apply()
    }
}
