package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.IOException

/** Android side of the desk backup: pick a file, write or read it, and merge it back. Shared by the Desk and the chat. */
internal object DeskBackupIO {
    const val REQUEST_EXPORT = 9502
    const val REQUEST_RESTORE = 9503

    fun pickExport(a: Activity) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "orion-desk-backup.json") }
        a.startActivityForResult(i, REQUEST_EXPORT)
    }

    fun pickRestore(a: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        a.startActivityForResult(i, REQUEST_RESTORE)
    }

    /** Handles the picker result. Returns true when the request code was ours. */
    fun onResult(a: Activity, requestCode: Int, ok: Boolean, uri: Uri?, after: () -> Unit): Boolean {
        if (requestCode != REQUEST_EXPORT && requestCode != REQUEST_RESTORE) return false
        if (!ok || uri == null) return true
        if (requestCode == REQUEST_EXPORT) export(a, uri) else restore(a, uri, after)
        return true
    }

    fun export(a: Activity, uri: Uri) {
        Thread {
            val msg = try {
                val prefs = DeskBackup.FILES.associateWith { a.getSharedPreferences(it, Context.MODE_PRIVATE).all as Map<String, Any?> }
                val text = DeskBackup.build(prefs, TradeHistoryStore.exportJson(a), System.currentTimeMillis(), AgentLessons.list(a, "coach"))
                (a.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open the file.")).use { it.write(text.toByteArray()) }
                "Backup saved."
            } catch (e: Exception) { "Backup failed: " + (e.message ?: e.javaClass.simpleName) }
            a.runOnUiThread { Toast.makeText(a, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    fun restore(a: Activity, uri: Uri, after: () -> Unit) {
        val parsed = try {
            val bytes = (a.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file.")).use { it.readBytes() }
            DeskBackup.parse(String(bytes))
        } catch (e: Exception) { Toast.makeText(a, e.message ?: "Could not read that file.", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(a).setTitle("Restore this backup?")
            .setMessage("Contains ${DeskBackup.summary(parsed)}. Matching desk settings are replaced; journal trades and memory notes are merged. Keys and safety limits are not touched.")
            .setPositiveButton("Restore") { _, _ ->
                for ((file, map) in parsed.prefs) {
                    val e = a.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
                    for ((k, v) in map) when (v) {
                        is String -> e.putString(k, v)
                        is Boolean -> e.putBoolean(k, v)
                        is Int -> e.putInt(k, v)
                        is Long -> e.putLong(k, v)
                        is Float -> e.putFloat(k, v)
                    }
                    e.apply()
                }
                val added = TradeHistoryStore.importJson(a, parsed.journal)
                val notes = AgentLessons.importNotes(a, "coach", parsed.memory)
                Toast.makeText(a, "Restored. $added new journal trades, $notes new memory notes.", Toast.LENGTH_LONG).show()
                after()
            }.setNegativeButton("Cancel", null).show()
    }
}
