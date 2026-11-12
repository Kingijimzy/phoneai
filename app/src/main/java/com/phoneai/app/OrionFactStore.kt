package com.phoneai.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Compatibility adapter for legacy triplet tools and the owner preference profile.
 * New facts live in MemoryStore and OrionKnowledgeGraph; the old facts table is read once
 * and migrated as untrusted proposals because its original provenance is unavailable.
 */
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
    private const val LEGACY_MIGRATION_PREFS = "orion_fact_store_migration"

    /** Old facts lack provenance; migrate them as proposals, never silently as trusted facts. */
    private fun migrateLegacyFacts(c: Context) {
        val app = c.applicationContext
        val prefs = app.getSharedPreferences(LEGACY_MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("complete", false)) return
        val rows = mutableListOf<Triple<String, String, String>>()
        try {
            Db(app).readableDatabase.use { db ->
                db.rawQuery("SELECT s,p,o FROM facts ORDER BY at DESC", null).use {
                    while (it.moveToNext()) rows += Triple(it.getString(0), it.getString(1), it.getString(2))
                }
            }
        } catch (_: Exception) { return }
        val migrated = rows.all { (s, p, o) ->
            MemoryStore.addObservedProposal(
                app, "fact", "${norm(s)} — ${norm(p)} — ${norm(o)}",
                0.55, "migrated legacy triplet; original provenance unavailable"
            ) != null
        }
        if (migrated) prefs.edit().putBoolean("complete", true).apply()
    }

    /** Called by the shared turn-context builder so old facts are migrated before recall. */
    fun migrateLegacyFactsIfNeeded(c: Context) = migrateLegacyFacts(c)

    /** Model/tool-originated writes enter the canonical memory store as reviewable proposals. */
    fun add(c: Context, s: String, p: String, o: String): String {
        val (a, b, d) = Triple(norm(s), norm(p), norm(o))
        if (a.isBlank() || b.isBlank() || d.isBlank()) return "error: fact_add needs subject, predicate and object"
        val memory = MemoryStore.addObservedProposal(
            c, "fact", "$a — $b — $d", 0.55, "fact_add tool proposal; owner review required"
        ) ?: return "error: fact proposal could not be saved"
        return if (memory.reviewStatus == "approved") "already remembered: $a — $b — $d"
        else "proposed for memory review: $a — $b — $d"
    }

    /** Direct owner statements enter the same graph as owner-approved memories. */
    fun addOwnerFact(c: Context, s: String, p: String, o: String): String {
        val (a, b, d) = Triple(norm(s), norm(p), norm(o))
        if (a.isBlank() || b.isBlank() || d.isBlank()) return "error: owner fact needs subject, predicate and object"
        val memory = MemoryStore.addOwnerConfirmed(c, "fact", "$a — $b — $d")
            ?: return "error: owner fact could not be saved"
        return "remembered: $a — $b — $d (memory #${memory.id})"
    }

    /** Query only approved facts from the canonical MemoryStore/SQLite graph. */
    fun query(c: Context, s: String = "", p: String = "", o: String = "", match: String = "", limit: Int = 30): String {
        migrateLegacyFacts(c)
        val owner = Prefs.userName(c).trim()
        val facts = KnowledgeGraph.all(c).filter { fact ->
            val subjectMatches = s.isBlank() || fact.a.equals(norm(s), true) ||
                (fact.a == "user" && (s.equals("user", true) || s.equals(owner, true)))
            val predicateMatches = p.isBlank() || fact.rel.replace('_', ' ').equals(norm(p).replace('_', ' '), true)
            val objectMatches = o.isBlank() || fact.b.equals(norm(o), true)
            val matchMatches = match.isBlank() || listOf(fact.a, fact.rel, fact.b).any { it.contains(norm(match), true) }
            subjectMatches && predicateMatches && objectMatches && matchMatches
        }.take(limit.coerceIn(1, 100))
        return if (facts.isEmpty()) "no owner-approved facts match; check the Memory screen for pending proposals"
        else facts.joinToString("\n") { "${it.a} — ${it.rel} — ${it.b}" }
    }

    /** Forgetting a fact removes it from trusted recall while preserving its rejected record. */
    fun forget(c: Context, s: String, p: String, o: String): String {
        migrateLegacyFacts(c)
        val owner = Prefs.userName(c).trim()
        val matches = MemoryStore.all(c).filter { memory ->
            MemoryGovernancePolicy.canUseInPrompt(memory.reviewStatus, memory.sensitivity) &&
                GraphPolicy.extract(memory.text).any { (a, rel, b) ->
                    val subjectMatches = s.isBlank() || a.equals(norm(s), true) ||
                        (a == "user" && (s.equals("user", true) || s.equals(owner, true)))
                    val predicateMatches = p.isBlank() || rel.replace('_', ' ').equals(norm(p).replace('_', ' '), true)
                    val objectMatches = o.isBlank() || b.equals(norm(o), true)
                    subjectMatches && predicateMatches && objectMatches
                }
        }
        val changed = matches.count { MemoryStore.setReviewStatus(c, it.id, "rejected") }
        return if (changed > 0) "forgot $changed fact(s) from trusted recall; rejected records remain available in Memory history"
        else "no matching approved fact"
    }

    data class Tom(val dimension: String, val value: String, val confidence: Double, val evidence: String, val source: String, val observations: Int)

    /**
     * Theory-of-mind preference profile: revisable, evidence-backed hypotheses about how Kingi likes to work.
     * Owner-set values are authoritative; inferred values remain revisable.
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
        return "KINGI'S PREFERENCE PROFILE (theory of mind: hypotheses from evidence, revisable; his current words always win):\\n" +
            p.joinToString("\n") { "- ${it.dimension}: ${it.value} (${(it.confidence * 100).toInt()}%, ${it.source}, seen ${it.observations}x)" }
    }

    /** Compatibility adapter: prompt facts now come from the canonical approved memory graph. */
    fun promptBlock(c: Context, owner: String): String = runCatching {
        migrateLegacyFacts(c)
        val rows = KnowledgeGraph.promptBlock(c, owner)
        if (rows.isBlank()) "" else "KNOWN FACTS (approved owner memory; subject — predicate — object):\\n$rows"
    }.getOrDefault("")
}
