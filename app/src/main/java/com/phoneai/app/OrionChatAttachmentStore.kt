package com.phoneai.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Durable, conversation-scoped attachment storage.
 *
 * Payloads are copied into app-private storage; SQLite stores only safe display metadata,
 * relative file names, size and SHA-256. No external content URI is needed after import.
 */
internal object OrionChatAttachmentStore {
    const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
    private const val DATABASE = "orion_chat_attachments.db"

    data class Attachment(
        val id: String,
        val sessionId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
        val relativePath: String,
        val createdAt: Long
    )

    private class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, DATABASE, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE attachments (
                id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""")
            db.execSQL("CREATE INDEX attachments_session_time ON attachments(session_id, created_at)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun root(context: Context, sessionId: String): File {
        val safeSession = sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(100)
        require(safeSession.isNotBlank()) { "A saved conversation is required before attaching a file." }
        return File(context.filesDir, "chat-attachments/$safeSession")
    }

    fun saveFromUri(
        context: Context,
        sessionId: String,
        uri: Uri,
        resolver: ContentResolver = context.contentResolver
    ): Attachment {
        require(uri.scheme == "content" || uri.scheme == "file") { "Unsupported attachment source." }
        val id = UUID.randomUUID().toString()
        // Some file:// sources and document providers return no OpenableColumns row.
        // Derive a display-name fallback from the URI before applying the same sanitization.
        var displayName = if (uri.scheme == "file") {
            uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: "attachment"
        } else "attachment"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }
        if (declaredSize > MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException("Attachment exceeds the 25 MB limit.")
        }
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(100).ifBlank { "attachment" }
        val extension = safeName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
        val inferredMime = extension?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val mime = (resolver.getType(uri) ?: inferredMime ?: "application/octet-stream").substringBefore(';').take(120)
        val directory = root(context, sessionId).apply { mkdirs() }
        val destination = File(directory, id + "_" + safeName)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            val input = resolver.openInputStream(uri) ?: throw IllegalArgumentException("Android could not open this attachment.")
            input.use { source ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > MAX_ATTACHMENT_BYTES) throw IllegalArgumentException("Attachment exceeds the 25 MB limit.")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (count == 0L) throw IllegalArgumentException("The selected attachment is empty.")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val relative = "chat-attachments/${directory.name}/${destination.name}"
            val item = Attachment(id, sessionId.take(160), safeName, mime, count, sha, relative, System.currentTimeMillis())
            val values = ContentValues().apply {
                put("id", item.id); put("session_id", item.sessionId); put("name", item.name)
                put("mime_type", item.mimeType); put("size_bytes", item.sizeBytes)
                put("sha256", item.sha256); put("relative_path", item.relativePath); put("created_at", item.createdAt)
            }
            val helper = Db(context)
            try {
                check(helper.writableDatabase.insert("attachments", null, values) != -1L) {
                    "Could not save attachment metadata."
                }
            } finally { helper.close() }
            return item
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun forSession(context: Context, sessionId: String): List<Attachment> {
        if (sessionId.isBlank()) return emptyList()
        val helper = Db(context)
        return try {
            val result = mutableListOf<Attachment>()
            helper.readableDatabase.rawQuery(
                "SELECT id,session_id,name,mime_type,size_bytes,sha256,relative_path,created_at FROM attachments WHERE session_id=? ORDER BY created_at ASC",
                arrayOf(sessionId.take(160))
            ).use { cursor ->
                while (cursor.moveToNext()) result += Attachment(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                    cursor.getLong(4), cursor.getString(5), cursor.getString(6), cursor.getLong(7)
                )
            }
            result.filter { file(context, it).isFile }
        } finally { helper.close() }
    }

    fun file(context: Context, attachment: Attachment): File {
        val base = File(context.filesDir, "chat-attachments").canonicalFile
        val target = File(context.filesDir, attachment.relativePath).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "Invalid attachment path." }
        return target
    }

    fun deleteSession(context: Context, sessionId: String): Int {
        val helper = Db(context)
        val removed = try { helper.writableDatabase.delete("attachments", "session_id=?", arrayOf(sessionId.take(160))) }
        finally { helper.close() }
        root(context, sessionId).deleteRecursively()
        return removed
    }
}
