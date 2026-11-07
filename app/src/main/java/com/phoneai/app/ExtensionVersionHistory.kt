package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Immutable source snapshots for promoted extensions. Rollback always reuses a tested hash. */
internal class ExtensionVersionHistory(context: Context) {
    data class Version(
        val id: String,
        val displayName: String,
        val runtime: String,
        val source: String,
        val sourceSha256: String,
        val description: String,
        val createdAtMillis: Long,
        val version: Int,
        val activatedAtMillis: Long
    ) {
        fun extension(enabled: Boolean = true) = GeneratedExtension(
            id, displayName, runtime, source, sourceSha256, description, createdAtMillis,
            enabled = enabled, verified = enabled
        )
    }

    private val lock = Any()
    private val file = AtomicFile(File(context.applicationContext.filesDir, "orion_extension_versions.json"))

    fun all(id: String): List<Version> = synchronized(lock) {
        readLocked().filter { it.id == id }.sortedBy { it.version }
    }

    fun record(extension: GeneratedExtension): Boolean = synchronized(lock) {
        if (!extension.enabled || !extension.verified ||
            extension.sourceSha256 != SelfExtendingExtensionPolicy.sha256(extension.source)) return@synchronized false
        val rows = try { readLocked() } catch (_: Exception) { return@synchronized false }
        if (rows.any { it.id == extension.id && it.sourceSha256 == extension.sourceSha256 }) return@synchronized true
        val next = (rows.filter { it.id == extension.id }.maxOfOrNull { it.version } ?: 0) + 1
        persistLocked(rows + Version(
            extension.id, extension.displayName, extension.runtime, extension.source,
            extension.sourceSha256, extension.description, extension.createdAtMillis,
            next, System.currentTimeMillis()
        ))
    }

    /** Returns the newest previously activated version whose source hash differs from active. */
    fun previous(id: String, activeHash: String): Version? = synchronized(lock) {
        try { readLocked().filter { it.id == id && it.sourceSha256 != activeHash }.maxByOrNull { it.version } }
        catch (_: Exception) { null }
    }

    private fun readLocked(): List<Version> {
        val raw = try { file.openRead().bufferedReader().use { it.readText() } }
        catch (_: java.io.FileNotFoundException) { return emptyList() }
        val root = JSONObject(raw)
        require(root.optInt("version", -1) == 1) { "unsupported extension history version" }
        val rows = root.optJSONArray("versions") ?: throw IllegalStateException("extension versions missing")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                Version(
                    row.getString("id"), row.getString("displayName"), row.getString("runtime"),
                    row.getString("source"), row.getString("sourceSha256"), row.optString("description"),
                    row.getLong("createdAtMillis"), row.getInt("version"), row.getLong("activatedAtMillis")
                )
            }.getOrNull()
        }
    }

    private fun persistLocked(rows: List<Version>): Boolean {
        val array = JSONArray()
        rows.takeLast(300).forEach { v ->
            array.put(JSONObject().put("id", v.id).put("displayName", v.displayName)
                .put("runtime", v.runtime).put("source", v.source).put("sourceSha256", v.sourceSha256)
                .put("description", v.description).put("createdAtMillis", v.createdAtMillis)
                .put("version", v.version).put("activatedAtMillis", v.activatedAtMillis))
        }
        val bytes = JSONObject().put("version", 1).put("versions", array).toString().toByteArray(Charsets.UTF_8)
        var stream: FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) runCatching { file.failWrite(stream) }
            false
        }
    }
}
