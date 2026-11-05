package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable proof that a specific extension source passed runtime sandbox tests.
 * Receipts are hash-bound: changing source invalidates the old proof. Only passing
 * reports are persisted; a crash/restart does not silently turn an untested draft live.
 */
internal class ExtensionTestReceiptStore(context: Context) {
    private val lock = Any()
    private val file = AtomicFile(File(context.applicationContext.filesDir, "orion_extension_test_receipts.json"))

    fun record(id: String, report: ExtensionTestReport): Boolean = synchronized(lock) {
        if (id.isBlank() || !report.passed || report.testedSourceSha256.isBlank()) return@synchronized false
        val current = runCatching { readAllLocked() }.getOrNull() ?: return@synchronized false
        val rows = current.filterNot { it.optString("id") == id && it.optString("hash") == report.testedSourceSha256 }.toMutableList()
        rows += JSONObject()
            .put("id", id)
            .put("hash", report.testedSourceSha256)
            .put("summary", report.summary.take(500))
            .put("output", report.output.take(SelfExtendingExtensionPolicy.MAX_OUTPUT_CHARS))
            .put("recordedAt", System.currentTimeMillis())
        persistLocked(rows)
    }

    fun get(id: String, currentSourceHash: String): ExtensionTestReport? = synchronized(lock) {
        if (id.isBlank() || currentSourceHash.isBlank()) return@synchronized null
        val row = runCatching { readAllLocked() }.getOrNull()?.firstOrNull {
            it.optString("id") == id && it.optString("hash") == currentSourceHash
        } ?: return@synchronized null
        ExtensionTestReport(
            passed = true,
            testedSourceSha256 = row.optString("hash"),
            summary = row.optString("summary"),
            output = row.optString("output")
        )
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val rows = runCatching { readAllLocked() }.getOrNull() ?: return@synchronized false
        val retained = rows.filterNot { it.optString("id") == id }
        persistLocked(retained)
    }

    private fun readAllLocked(): List<JSONObject> {
        val raw = try { file.openRead().bufferedReader().use { it.readText() } }
        catch (_: java.io.FileNotFoundException) { return emptyList() }
        val root = try { JSONObject(raw) } catch (_: Exception) {
            throw IllegalStateException("Extension test receipt store is corrupt; refusing to overwrite it.")
        }
        require(root.optInt("version", -1) == 1) { "Unsupported extension test receipt version." }
        val rows = root.optJSONArray("receipts")
            ?: throw IllegalStateException("Extension test receipt list is missing.")
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    private fun persistLocked(rows: List<JSONObject>): Boolean {
        val array = JSONArray()
        rows.takeLast(100).forEach { array.put(it) }
        val bytes = JSONObject().put("version", 1).put("receipts", array)
            .toString().toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
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
