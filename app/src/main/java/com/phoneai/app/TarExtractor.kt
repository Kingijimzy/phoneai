package com.phoneai.app

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Safe extraction of a POSIX/ustar/GNU/pax tar stream into a directory, for Orion's Linux root filesystem.
 * Every path must stay inside the target; symlinks must point inside it; device nodes are skipped (they need root);
 * total size and entry count are capped. Pure JVM, so it is unit tested without Android.
 */
internal object TarExtractor {
    class Rejected(message: String) : Exception(message)
    data class Stats(val files: Int, val dirs: Int, val links: Int, val skipped: Int, val bytes: Long)

    const val MAX_TOTAL_BYTES = 400_000_000L
    const val MAX_ENTRIES = 40_000
    private const val BLOCK = 512

    fun entryNameAllowed(name: String): Boolean {
        if (name.isEmpty() || name.length > 4096 || name.contains('\u0000')) return false
        if (name.startsWith("/")) return false
        return name.split('/').none { it == ".." }
    }

    /** A relative link is allowed only when, resolved from its own folder, it stays inside the root. */
    fun symlinkTargetAllowed(linkPath: String, target: String): Boolean {
        if (target.isEmpty() || target.length > 4096 || target.startsWith("/") || target.contains('\u0000')) return false
        val parent = linkPath.substringBeforeLast('/', "")
        val combined = if (parent.isEmpty()) target else "$parent/$target"
        return try {
            val resolved = Paths.get(combined).normalize().toString()
            resolved != ".." && !resolved.startsWith("../") && !resolved.startsWith("/")
        } catch (_: Exception) { false }
    }

    fun extract(input: InputStream, root: File): Stats {
        root.mkdirs()
        val base = root.canonicalFile
        val header = ByteArray(BLOCK)
        var files = 0; var dirs = 0; var links = 0; var skipped = 0; var bytes = 0L; var entries = 0
        var pendingName: String? = null
        var pendingLink: String? = null
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val size = octal(header, 124, 12)
            if (size < 0) throw Rejected("bad entry size")
            val type = header[156].toInt().toChar()
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                val data = readData(input, size)
                when (type) {
                    'L' -> pendingName = cString(data, 0, data.size)
                    'K' -> pendingLink = cString(data, 0, data.size)
                    'x' -> paxValue(data, "path")?.let { pendingName = it }.also { paxValue(data, "linkpath")?.let { p -> pendingLink = p } }
                }
                continue
            }
            val name = pendingName ?: nameOf(header)
            val linkName = pendingLink ?: cString(header, 157, 100)
            pendingName = null; pendingLink = null
            if (++entries > MAX_ENTRIES) throw Rejected("archive has too many entries")
            val cleaned = name.removePrefix("./").trimEnd('/')
            if (cleaned.isEmpty()) { skipPayload(input, size); continue }
            if (!entryNameAllowed(cleaned)) throw Rejected("unsafe path in archive")
            val target = File(base, cleaned)
            if (!target.canonicalFile.path.startsWith(base.path + File.separator)) throw Rejected("path escapes the runtime folder")
            when (type) {
                '0', '\u0000', '7' -> {
                    bytes += size
                    if (bytes > MAX_TOTAL_BYTES) throw Rejected("archive expands beyond the size limit")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> copyExactly(input, out, size) }
                    skipPadding(input, size)
                    if (octal(header, 100, 8) and 73L != 0L) target.setExecutable(true, false)
                    files++
                }
                '5' -> { target.mkdirs(); skipPayload(input, size); dirs++ }
                '2' -> {
                    if (!symlinkTargetAllowed(cleaned, linkName)) throw Rejected("symlink points outside the runtime folder")
                    target.parentFile?.mkdirs()
                    if (target.exists() || isLink(target)) target.delete()
                    Files.createSymbolicLink(target.toPath(), Paths.get(linkName))
                    skipPayload(input, size); links++
                }
                '1' -> {
                    val src = linkName.removePrefix("./").trimEnd('/')
                    if (!entryNameAllowed(src)) throw Rejected("unsafe hard link in archive")
                    val source = File(base, src)
                    if (!source.canonicalFile.path.startsWith(base.path + File.separator) || !source.isFile) throw Rejected("hard link target missing")
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    skipPayload(input, size); links++
                }
                else -> { skipPayload(input, size); skipped++ }
            }
        }
        return Stats(files, dirs, links, skipped, bytes)
    }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    private fun nameOf(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val isUstar = h.copyOfRange(257, 263).toString(Charsets.US_ASCII).startsWith("ustar")
        val prefix = if (isUstar) cString(h, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && end < b.size && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = cString(b, off, len).trim().trim('\u0000', ' ')
        if (s.isEmpty()) return 0L
        return s.toLongOrNull(8) ?: throw Rejected("bad number in archive header")
    }

    /** pax records: "<len> key=value\n". Returns the value for one key, if present. */
    internal fun paxValue(data: ByteArray, key: String): String? {
        var i = 0
        while (i < data.size) {
            var j = i
            while (j < data.size && data[j] != ' '.code.toByte()) j++
            val len = String(data, i, j - i, Charsets.US_ASCII).toIntOrNull() ?: return null
            if (len <= 0 || i + len > data.size) return null
            val record = String(data, j + 1, i + len - j - 1, Charsets.UTF_8).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0 && record.substring(0, eq) == key) return record.substring(eq + 1)
            i += len
        }
        return null
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) { if (off == 0) return false; throw Rejected("archive ends in the middle of a header") }
            off += n
        }
        return true
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size > 1_000_000) throw Rejected("archive metadata is too large")
        val data = ByteArray(size.toInt())
        var off = 0
        while (off < data.size) {
            val n = input.read(data, off, data.size - off)
            if (n < 0) throw Rejected("archive ends early")
            off += n
        }
        skipPadding(input, size)
        return data
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(65536)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw Rejected("archive ends early")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipPayload(input: InputStream, size: Long) {
        copyExactly(input, java.io.OutputStream.nullOutputStream(), size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        if (pad > 0) {
            var left = pad
            val buf = ByteArray(pad)
            while (left > 0) {
                val n = input.read(buf, 0, left)
                if (n < 0) throw Rejected("archive ends early")
                left -= n
            }
        }
    }
}
