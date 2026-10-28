package com.phoneai.app

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

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

    /**
     * Validate links in the guest filesystem. Absolute tar links refer to the guest root, not the
     * Android host root, so extraction rewrites them to relative links before creating them.
     */
    fun symlinkTargetAllowed(linkPath: String, target: String): Boolean {
        if (target.isEmpty() || target.length > 4096 || target.contains('\u0000')) return false
        val absoluteGuestTarget = target.startsWith("/")
        val guestTarget = if (absoluteGuestTarget) target.removePrefix("/") else target
        if (guestTarget.isEmpty() || guestTarget.startsWith("/") ||
            guestTarget.split('/').any { it.isEmpty() || it == "." && absoluteGuestTarget } ||
            (absoluteGuestTarget && !entryNameAllowed(guestTarget))) return false
        val parent = linkPath.substringBeforeLast('/', "")
        val combined = if (parent.isEmpty()) guestTarget else "$parent/$guestTarget"
        return try {
            val resolved = Paths.get(combined).normalize().toString()
            resolved.isNotEmpty() && resolved != ".." && !resolved.startsWith("../") && !resolved.startsWith("/")
        } catch (_: Exception) { false }
    }

    /** Convert guest-root absolute links into safe relative host links within the extracted root. */
    private fun storedSymlinkTarget(linkPath: String, target: String): String {
        if (!target.startsWith("/")) return target
        val parent = Paths.get(linkPath).parent ?: Paths.get("")
        val guestTarget = Paths.get(target.removePrefix("/")).normalize()
        require(!guestTarget.isAbsolute && guestTarget.toString().isNotEmpty() &&
            guestTarget.toString() != ".." && !guestTarget.toString().startsWith("../")) {
            "absolute symlink escapes the guest root"
        }
        return parent.relativize(guestTarget).toString()
    }

    fun extract(input: InputStream, root: File): Stats {
        root.mkdirs()
        val base = root.canonicalFile
        val header = ByteArray(BLOCK)
        var files = 0; var dirs = 0; var links = 0; var skipped = 0; var bytes = 0L; var entries = 0
        var pendingName: String? = null
        var pendingLink: String? = null
        // Android app storage can create directories as 0700 regardless of tar metadata.
        // Apply directory modes after extraction so restrictive modes cannot block child extraction.
        val directoryModes = linkedMapOf<File, Int>()
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
                    ensureParents(target.parentFile, base, directoryModes)
                    target.outputStream().use { out -> copyExactly(input, out, size) }
                    skipPadding(input, size)
                    setMode(target, octal(header, 100, 8).toInt(), cleaned)
                    files++
                }
                '5' -> {
                    ensureParents(target.parentFile, base, directoryModes)
                    check(target.mkdirs() || target.isDirectory) { "Could not create directory $cleaned" }
                    directoryModes[target] = octal(header, 100, 8).toInt()
                    skipPayload(input, size); dirs++
                }
                '2' -> {
                    if (!symlinkTargetAllowed(cleaned, linkName)) throw Rejected("symlink points outside the runtime folder")
                    ensureParents(target.parentFile, base, directoryModes)
                    if (target.exists() || isLink(target)) target.delete()
                    // Never create a host-absolute symlink: map guest /foo to a relative path inside rootfs.
                    Files.createSymbolicLink(target.toPath(), Paths.get(storedSymlinkTarget(cleaned, linkName)))
                    skipPayload(input, size); links++
                }
                '1' -> {
                    val src = linkName.removePrefix("./").trimEnd('/')
                    if (!entryNameAllowed(src)) throw Rejected("unsafe hard link in archive")
                    val source = File(base, src)
                    if (!source.canonicalFile.path.startsWith(base.path + File.separator) || !source.isFile) throw Rejected("hard link target missing")
                    ensureParents(target.parentFile, base, directoryModes)
                    source.copyTo(target, overwrite = true)
                    setMode(target, readMode(source), cleaned)
                    skipPayload(input, size); links++
                }
                else -> { skipPayload(input, size); skipped++ }
            }
        }
        // Apply directory modes deepest-first, after all children have been written.
        directoryModes.entries.sortedByDescending { it.key.toPath().nameCount }.forEach { (dir, mode) ->
            setMode(dir, mode, dir.relativeTo(base).path)
        }
        return Stats(files, dirs, links, skipped, bytes)
    }

    private fun ensureParents(parent: File?, base: File, modes: MutableMap<File, Int>) {
        if (parent == null || parent == base || modes.containsKey(parent)) return
        val chain = ArrayDeque<File>()
        var current: File? = parent
        while (current != null && current != base && !modes.containsKey(current)) {
            chain.addFirst(current)
            current = current.parentFile
        }
        for (dir in chain) {
            check(dir.mkdirs() || dir.isDirectory) { "Could not create parent directory ${dir.path}" }
            modes.putIfAbsent(dir, 493) // implicit directories default to 0755
        }
    }

    private fun readMode(file: File): Int {
        val p = Files.getPosixFilePermissions(file.toPath())
        var m = 0
        if (PosixFilePermission.OWNER_READ in p) m = m or 0b100000000
        if (PosixFilePermission.OWNER_WRITE in p) m = m or 0b010000000
        if (PosixFilePermission.OWNER_EXECUTE in p) m = m or 0b001000000
        if (PosixFilePermission.GROUP_READ in p) m = m or 0b000100000
        if (PosixFilePermission.GROUP_WRITE in p) m = m or 0b000010000
        if (PosixFilePermission.GROUP_EXECUTE in p) m = m or 0b000001000
        if (PosixFilePermission.OTHERS_READ in p) m = m or 0b000000100
        if (PosixFilePermission.OTHERS_WRITE in p) m = m or 0b000000010
        if (PosixFilePermission.OTHERS_EXECUTE in p) m = m or 0b000000001
        return m
    }

    private fun setMode(file: File, mode: Int, label: String) {
        val p = mutableSetOf<PosixFilePermission>()
        if ((mode and 0b100000000) != 0) p += PosixFilePermission.OWNER_READ
        if ((mode and 0b010000000) != 0) p += PosixFilePermission.OWNER_WRITE
        if ((mode and 0b001000000) != 0) p += PosixFilePermission.OWNER_EXECUTE
        if ((mode and 0b000100000) != 0) p += PosixFilePermission.GROUP_READ
        if ((mode and 0b000010000) != 0) p += PosixFilePermission.GROUP_WRITE
        if ((mode and 0b000001000) != 0) p += PosixFilePermission.GROUP_EXECUTE
        if ((mode and 0b000000100) != 0) p += PosixFilePermission.OTHERS_READ
        if ((mode and 0b000000010) != 0) p += PosixFilePermission.OTHERS_WRITE
        if ((mode and 0b000000001) != 0) p += PosixFilePermission.OTHERS_EXECUTE
        try {
            Files.setPosixFilePermissions(file.toPath(), p)
        } catch (failure: Exception) {
            val ok = file.setReadable((mode and 0b100100100) != 0, false) &&
                file.setWritable((mode and 0b010010010) != 0, false) &&
                file.setExecutable((mode and 0b001001001) != 0, false)
            check(ok) { "Could not restore mode ${Integer.toOctalString(mode)} for $label: ${failure.message}" }
        }
        check(file.canRead()) { "Extracted path is unreadable: $label" }
        if (file.isDirectory) check(file.canExecute()) { "Extracted directory is not searchable: $label" }
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
