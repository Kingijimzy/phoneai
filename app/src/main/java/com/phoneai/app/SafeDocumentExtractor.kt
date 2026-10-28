package com.phoneai.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Bounded, text-only document ingestion for chat.
 *
 * Deliberately does not execute, render, or parse complex document formats. The byte limit is
 * checked both from provider metadata and while reading because content providers can lie.
 */
object SafeDocumentExtractor {
    const val MAX_BYTES: Int = 1 * 1024 * 1024
    const val MAX_CHARS: Int = 30_000
    private const val READ_CHUNK = 8 * 1024

    data class Extracted(val name: String, val mimeType: String, val text: String, val bytesRead: Int)

    private val allowedExtensions = setOf("txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log")

    fun extract(resolver: ContentResolver, uri: Uri): Extracted {
        val mime = resolver.getType(uri)?.substringBefore(';')?.lowercase(Locale.ROOT) ?: "application/octet-stream"
        var name = "document"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)?.take(160) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in allowedExtensions) {
            throw IllegalArgumentException("Unsupported document type: .$ext. Supported formats: TXT, Markdown, CSV/TSV, JSON, XML, HTML, and LOG. PDF and Office files are not supported yet.")
        }
        if (declaredSize > MAX_BYTES) {
            throw IllegalArgumentException("This document is larger than the 1 MB limit. Choose a smaller text file.")
        }
        val input = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Android could not open this document. Re-select it and try again.")
        val output = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(READ_CHUNK)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) {
                    throw IllegalArgumentException("This document exceeds the 1 MB read limit. Choose a smaller text file.")
                }
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        if (bytes.any { it == 0.toByte() }) {
            throw IllegalArgumentException("This file appears to contain binary data, not plain text. Choose a supported text document.")
        }
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("This document is not valid UTF-8 text. Save or export it as UTF-8 and try again.")
        }
        val safeText = if (ext == "html" || ext == "htm") {
            decoded.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>"), " ")
                .replace(Regex("(?s)<[^>]{0,2048}>"), " ")
                .replace("&nbsp;", " ", true).replace("&amp;", "&", true)
                .replace("&lt;", "<", true).replace("&gt;", ">", true)
                .replace(Regex("[ \\t]+"), " ")
        } else decoded
        val trimmed = safeText.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("This document is empty.")
        val bounded = if (trimmed.length > MAX_CHARS) trimmed.take(MAX_CHARS) + "\\n\\n[Document truncated at $MAX_CHARS characters.]" else trimmed
        return Extracted(name, mime, bounded, bytes.size)
    }
}
