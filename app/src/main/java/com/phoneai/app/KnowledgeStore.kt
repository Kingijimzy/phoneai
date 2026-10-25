package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class KnowledgeDoc(val id: Int, val title: String, val added: Long, val chunks: List<String>)

/** On-device knowledge base: pasted or imported text split into chunks, searched locally (hybrid BM25 + TF-IDF). */
object KnowledgeStore {
    private const val MAX_DOCS = 40
    private const val MAX_CHARS = 300_000
    private val lock = Any()

    private fun file(c: Context) = File(c.filesDir, "orion_knowledge.json")

    /** Paragraph-aware chunks of at most [limit] characters. */
    internal fun chunk(text: String, limit: Int = 500): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotBlank()) out.add(current.toString().trim()); current.setLength(0) }
        for (paragraph in text.replace("\r", "").split(Regex("\n\\s*\n"))) {
            val p = paragraph.replace(Regex("\\s+"), " ").trim()
            if (p.isEmpty()) continue
            if (p.length > limit) {
                flush()
                var start = 0
                while (start < p.length) {
                    var end = minOf(p.length, start + limit)
                    if (end < p.length) { val cut = p.lastIndexOf(' ', end); if (cut > start + limit / 2) end = cut }
                    out.add(p.substring(start, end).trim())
                    start = end
                }
            } else {
                if (current.length + p.length + 1 > limit) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(p)
            }
        }
        flush()
        return out
    }

    private fun load(c: Context): MutableList<KnowledgeDoc> {
        val out = ArrayList<KnowledgeDoc>()
        try {
            val f = file(c)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val chunks = o.optJSONArray("chunks") ?: JSONArray()
                    out.add(KnowledgeDoc(o.getInt("id"), o.optString("title"), o.optLong("added"), (0 until chunks.length()).map { chunks.getString(it) }))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun save(c: Context, docs: List<KnowledgeDoc>): Boolean = try {
        val arr = JSONArray()
        docs.forEach { d -> arr.put(JSONObject().put("id", d.id).put("title", d.title).put("added", d.added).put("chunks", JSONArray(d.chunks))) }
        val temp = File(c.filesDir, "orion_knowledge.json.tmp")
        temp.writeText(arr.toString())
        if (!temp.renameTo(file(c))) { file(c).writeText(arr.toString()); temp.delete() }
        true
    } catch (_: Exception) { false }

    fun list(c: Context): List<KnowledgeDoc> = synchronized(lock) { load(c).sortedByDescending { it.added } }

    /** Returns the number of chunks stored, or a negative value on failure. */
    fun add(c: Context, title: String, text: String): Int = synchronized(lock) {
        val body = text.take(MAX_CHARS)
        val chunks = chunk(body)
        if (chunks.isEmpty()) return@synchronized -1
        val docs = load(c)
        if (docs.size >= MAX_DOCS) return@synchronized -2
        val id = (docs.maxOfOrNull { it.id } ?: 0) + 1
        docs.add(KnowledgeDoc(id, title.trim().take(80).ifBlank { "Untitled" }, System.currentTimeMillis(), chunks))
        if (save(c, docs)) chunks.size else -3
    }

    fun delete(c: Context, id: Int): Boolean = synchronized(lock) {
        val docs = load(c)
        if (!docs.removeAll { it.id == id }) return@synchronized false
        save(c, docs)
    }

    /** Best chunks for a query, each tagged with its document title. Content is data, never instructions. */
    fun search(c: Context, query: String, limit: Int = 5): List<String> {
        val chunks = list(c).flatMap { d -> d.chunks.map { d.title to it } }
        return MemoryRanking.hybridRank(chunks, { it.second }, query, limit).map { "[" + it.first + "] " + it.second }
    }
}
