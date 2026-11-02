package com.phoneai.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import android.util.Base64

/** Stages bounded, single-file workspace replacements; applying always requires explicit owner approval. */
internal object OrionWorkspacePatchStore {
    private const val MAX_BYTES = 80_000
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun proposalFile(context: Context, id: String): File {
        require(id.matches(Regex("^[a-f0-9]{64}$")))
        val dir = File(context.applicationContext.filesDir, "orion_patch_proposals").apply { mkdirs() }
        return File(dir, "$id.json")
    }

    data class Proposal(val id: String, val fileName: String, val sourceSha256: String, val replacementSha256: String)

    fun stage(context: Context, treeUri: Uri, fileName: String, source: ByteArray, replacement: ByteArray): Proposal {
        require(fileName.matches(Regex("^[A-Za-z0-9_.-]{1,120}$")) && fileName != "." && fileName != "..") { "unsafe_workspace_filename" }
        require(source.size <= MAX_BYTES && replacement.size <= MAX_BYTES) { "workspace_file_too_large" }
        require(source.none { it == 0.toByte() } && replacement.none { it == 0.toByte() }) { "binary_workspace_file_rejected" }
        val sourceHash = digest(source)
        val replacementHash = digest(replacement)
        val id = digest((treeUri.toString() + "|" + fileName + "|" + sourceHash + "|" + replacementHash).toByteArray())
        val row = JSONObject().put("id", id).put("treeUri", treeUri.toString()).put("fileName", fileName)
            .put("sourceSha256", sourceHash).put("replacementSha256", replacementHash)
            .put("sourceBase64", Base64.encodeToString(source, Base64.NO_WRAP))
            .put("replacementBase64", Base64.encodeToString(replacement, Base64.NO_WRAP))
            .put("status", "pending_owner_approval").put("createdAt", System.currentTimeMillis())
        val file = proposalFile(context, id)
        val tmp = File(file.parentFile, "$id.tmp")
        tmp.writeText(row.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) { tmp.delete(); throw IllegalStateException("patch_proposal_persist_failed") }
        return Proposal(id, fileName, sourceHash, replacementHash)
    }

    /** Call only after the app has collected an explicit owner confirmation for this exact proposal. */
    fun applyApproved(context: Context, proposalId: String, expectedSourceHash: String, ownerApproval: String): Boolean {
        if (ownerApproval.isBlank()) return false
        val file = proposalFile(context, proposalId)
        val row = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return false
        if (row.optString("status") != "pending_owner_approval" ||
            row.optString("sourceSha256") != expectedSourceHash) return false
        val uri = runCatching { Uri.parse(row.getString("treeUri")) }.getOrNull() ?: return false
        val name = row.optString("fileName")
        if (!name.matches(Regex("^[A-Za-z0-9_.-]{1,120}$"))) return false
        val source = runCatching { Base64.decode(row.getString("sourceBase64"), Base64.NO_WRAP) }.getOrNull() ?: return false
        val replacement = runCatching { Base64.decode(row.getString("replacementBase64"), Base64.NO_WRAP) }.getOrNull() ?: return false
        if (digest(source) != expectedSourceHash || digest(replacement) != row.optString("replacementSha256")) return false
        return try {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)
            var target: Uri? = null
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(1) == name &&
                    cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) {
                    target = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)); break
                }
            }
            val document = target ?: return false
            val current = context.contentResolver.openInputStream(document)?.use { it.readBytes() } ?: return false
            if (digest(current) != expectedSourceHash || !current.contentEquals(source)) return false
            val output = context.contentResolver.openOutputStream(document, "wt") ?: return false
            try { output.use { it.write(replacement); it.flush() } }
            catch (e: Exception) {
                runCatching { context.contentResolver.openOutputStream(document, "wt")?.use { it.write(source); it.flush() } }
                throw e
            }
            val written = context.contentResolver.openInputStream(document)?.use { it.readBytes() } ?: return false
            if (digest(written) != row.optString("replacementSha256")) {
                runCatching { context.contentResolver.openOutputStream(document, "wt")?.use { it.write(source); it.flush() } }
                return false
            }
            row.put("status", "applied").put("ownerApproval", ownerApproval.take(240))
                .put("appliedAt", System.currentTimeMillis())
            val tmp = File(file.parentFile, "$proposalId.tmp")
            tmp.writeText(row.toString(), Charsets.UTF_8)
            tmp.renameTo(file)
        } catch (_: Exception) { false }
    }
}
