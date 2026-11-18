package com.phoneai.app

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Folder sync between the phone and the Linux workspace. A pair keeps two folders level: new files appear on both
 * sides and the newer copy of a changed file wins. Deletions are not propagated, so a sync can never remove work.
 * Pairs persist and are re-run in the background while the Orion service lives.
 */
internal object OrionFolderSync {
    data class SyncPair(val phone: String, val workspaceRel: String)
    data class Report(val toWorkspace: Int, val toPhone: Int, val skipped: Int) {
        val changed get() = toWorkspace + toPhone
        fun line(): String = if (changed == 0) "Already in sync" else "Synced: $toWorkspace to workspace, $toPhone to phone" + if (skipped > 0) " ($skipped skipped)" else ""
    }

    const val MAX_FILES = 3000
    const val MAX_FILE_BYTES = 25L * 1024 * 1024
    private const val PREFS = "orion_folder_sync"
    private val skipDirs = setOf(".git", "node_modules", "__pycache__", ".venvs", ".gradle", "build")
    private var scheduler: ScheduledExecutorService? = null

    /** Walks both folders and makes them level. Pure file logic: safe to unit test with temp folders. */
    fun syncFolders(phone: File, work: File): Report {
        if (!phone.isDirectory || !work.isDirectory) return Report(0, 0, 0)
        val a = index(phone); val b = index(work)
        var toWork = 0; var toPhone = 0; var skipped = 0
        (a.keys + b.keys).take(MAX_FILES).forEach { rel ->
            val pf = a[rel]; val wf = b[rel]
            val (src, dst, dir) = when {
                pf != null && wf == null -> Triple(pf, File(work, rel), true)
                pf == null && wf != null -> Triple(wf, File(phone, rel), false)
                pf != null && wf != null -> when {
                    pf.length() == wf.length() && kotlin.math.abs(pf.lastModified() - wf.lastModified()) < 2000L -> return@forEach
                    pf.lastModified() >= wf.lastModified() -> Triple(pf, wf, true)
                    else -> Triple(wf, pf, false)
                }
                else -> return@forEach
            }
            if (src.length() > MAX_FILE_BYTES) { skipped++; return@forEach }
            val ok = runCatching { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true); dst.setLastModified(src.lastModified()) }.isSuccess
            if (!ok) skipped++ else if (dir) toWork++ else toPhone++
        }
        return Report(toWork, toPhone, skipped)
    }

    private fun index(root: File): Map<String, File> {
        val base = root.canonicalFile
        val out = LinkedHashMap<String, File>()
        base.walkTopDown().onEnter { it.name !in skipDirs || it == base }.filter { it.isFile }.forEach { f ->
            if (out.size < MAX_FILES) out[f.relativeTo(base).path] = f
        }
        return out
    }

    private fun load(c: Context): MutableList<SyncPair> {
        val arr = runCatching { JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pairs", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> SyncPair(o.optString("p"), o.optString("w")) } }.filter { it.phone.isNotBlank() }.toMutableList()
    }

    private fun save(c: Context, pairs: List<SyncPair>) {
        val arr = JSONArray(); pairs.forEach { arr.put(JSONObject().put("p", it.phone).put("w", it.workspaceRel)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pairs", arr.toString()).apply()
    }

    fun pairs(c: Context): List<SyncPair> = load(c)

    fun relToWorkspace(c: Context, dir: File): String? = runCatching { dir.canonicalFile.relativeTo(ShellRunner.workspace(c).canonicalFile).path }.getOrNull()?.takeIf { !it.startsWith("..") }

    fun isSynced(c: Context, phone: File, work: File): Boolean {
        val rel = relToWorkspace(c, work) ?: return false
        return load(c).any { it.phone == phone.canonicalPath && it.workspaceRel == rel }
    }

    /** Turns sync on or off for this folder pair; returns a one-line status. */
    fun toggle(c: Context, phone: File, work: File): String {
        val rel = relToWorkspace(c, work) ?: return "That folder is outside the workspace."
        val pair = SyncPair(phone.canonicalPath, rel)
        val all = load(c)
        return if (all.remove(pair)) { save(c, all); "Sync off for ${phone.name.ifBlank { "/" }}" }
        else { all.add(pair); save(c, all); syncAll(c).line() }
    }

    fun syncAll(c: Context): Report {
        var w = 0; var p = 0; var s = 0
        load(c).forEach { pair ->
            val r = syncFolders(File(pair.phone), File(ShellRunner.workspace(c), pair.workspaceRel))
            w += r.toWorkspace; p += r.toPhone; s += r.skipped
        }
        return Report(w, p, s)
    }

    /** Idempotent: one daemon timer re-runs every saved pair once a minute. */
    @Synchronized fun startBackground(c: Context) {
        if (scheduler != null) return
        val app = c.applicationContext
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "orion-folder-sync").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { syncAll(app) } }, 30, 60, TimeUnit.SECONDS)
        }
    }
}
