package com.phoneai.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException

data class PhoneFileInfo(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?
)

data class PhoneFileResult<T>(val value: T? = null, val error: String? = null) {
    val isSuccess: Boolean get() = value != null
}

/**
 * Safe helper functions for user-selected document URIs (Storage Access Framework).
 * No broad storage permission or raw filesystem path access is required.
 * The UI must launch ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT and pass the returned URI.
 */
object PhoneFileHandler {
    private const val MAX_TEXT_BYTES = 1_000_000L
    private const val MAX_COPY_BYTES = 25_000_000L

    fun inspect(context: Context, uri: Uri): PhoneFileResult<PhoneFileInfo> {
        val resolver = context.contentResolver
        return try {
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                        (displayName ?: "Selected document") to size
                    }
                } ?: ("Selected document" to null)
            PhoneFileResult(PhoneFileInfo(uri, name.first.take(255), resolver.getType(uri), name.second))
        } catch (_: Exception) {
            PhoneFileResult(error = "Could not inspect the selected document.")
        }
    }

    /** Reads only UTF-8 text and refuses oversized documents. */
    fun readText(context: Context, uri: Uri): PhoneFileResult<String> {
        val resolver = context.contentResolver
        val info = inspect(context, uri).value
        if (info?.sizeBytes != null && info.sizeBytes > MAX_TEXT_BYTES) {
            return PhoneFileResult(error = "Text file exceeds the 1 MB read limit.")
        }
        return try {
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_TEXT_BYTES) return PhoneFileResult(error = "Text file exceeds the 1 MB read limit.")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: return PhoneFileResult(error = "The document provider could not open this file.")
            PhoneFileResult(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            PhoneFileResult(error = "Could not read the selected document.")
        }
    }

    /** Writes text only to a URI explicitly chosen/created by the user. */
    fun writeText(context: Context, uri: Uri, text: String): PhoneFileResult<Long> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) return PhoneFileResult(error = "Text exceeds the 1 MB write limit.")
        return try {
            val count = context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
                bytes.size.toLong()
            } ?: return PhoneFileResult(error = "The document provider could not write this file.")
            PhoneFileResult(count)
        } catch (_: Exception) {
            PhoneFileResult(error = "Could not write the selected document.")
        }
    }

    /** Copies between user-selected URIs; both source and destination must come from the UI. */
    fun copy(context: Context, source: Uri, destination: Uri): PhoneFileResult<Long> {
        if (source == destination) return PhoneFileResult(error = "Source and destination are the same document.")
        val resolver: ContentResolver = context.contentResolver
        val sourceInfo = inspect(context, source).value
        if (sourceInfo?.sizeBytes != null && sourceInfo.sizeBytes > MAX_COPY_BYTES) {
            return PhoneFileResult(error = "Source exceeds the 25 MB copy limit.")
        }
        return try {
            val copied = resolver.openInputStream(source)?.use { rawInput ->
                BufferedInputStream(rawInput).use { input ->
                    resolver.openOutputStream(destination, "w")?.use { rawOutput ->
                        BufferedOutputStream(rawOutput).use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_COPY_BYTES) throw IOException("Copy exceeds the 25 MB limit.")
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                            total
                        }
                    } ?: throw IOException("Destination cannot be written.")
                }
            } ?: throw IOException("Source cannot be read.")
            PhoneFileResult(copied)
        } catch (e: IOException) {
            PhoneFileResult(error = e.message?.take(160) ?: "Copy failed.")
        } catch (_: Exception) {
            PhoneFileResult(error = "Could not copy the selected document.")
        }
    }

    /** Deletion is explicit and should only be called after a user confirmation. */
    fun deleteSelected(context: Context, uri: Uri): PhoneFileResult<Boolean> {
        return try {
            val deleted = context.contentResolver.delete(uri, null, null) > 0
            PhoneFileResult(deleted, if (deleted) null else "The document provider did not delete the file.")
        } catch (_: Exception) {
            PhoneFileResult(error = "Could not delete the selected document.")
        }
    }
}
