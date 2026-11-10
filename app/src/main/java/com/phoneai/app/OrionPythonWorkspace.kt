package com.phoneai.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared app-private coding workspace for Python, Alpine shell and project file tools. Paths cannot escape or follow symlinks. */
internal class OrionPythonWorkspace(context: Context) {
    private val filesDir = context.applicationContext.filesDir
    private val root = File(filesDir, "orion-python/workspace")
    private val trash = File(filesDir, "orion-python/trash")
    private val backup = File(filesDir, "orion-python/backup")
    companion object {
        const val MAX_FILE_BYTES = 512 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        // Preserve space for Android, databases and other apps.
        private const val MIN_FREE_SPACE_BYTES = 1024L * 1024 * 1024
        private const val MAX_ENTRIES = 100_000
        private val IGNORED_LIST_DIRECTORIES = setOf("node_modules", ".gradle", ".git", ".idea", "build", "dist", "target", "__pycache__", ".venv", "venv")
        private val NAME = Regex("^[A-Za-z0-9.][A-Za-z0-9._/-]{0,179}$")
    }
    enum class RootStatus { MISSING, READY, UNSAFE }

    @Synchronized fun rootStatus(): RootStatus {
        if (hasSymlink(root) || !insideFilesDir(root)) return RootStatus.UNSAFE
        if (!root.exists()) return RootStatus.MISSING
        return if (root.isDirectory && root.canRead() && root.canWrite()) RootStatus.READY else RootStatus.UNSAFE
    }

    @Synchronized fun canEnsureRoot(): Boolean {
        if (rootStatus() != RootStatus.MISSING) return false
        var parent = root.parentFile
        while (parent != null && !parent.exists()) parent = parent.parentFile
        return parent != null && parent.isDirectory && parent.canWrite() &&
            insideFilesDir(parent) && !hasSymlink(parent)
    }

    /** App-owned repair primitive: creates only the fixed workspace root, never follows links. */
    @Synchronized fun ensureRootForRepair(): String? {
        when (rootStatus()) {
            RootStatus.READY -> return "already_ready"
            RootStatus.UNSAFE -> return null
            RootStatus.MISSING -> Unit
        }
        val parent = root.parentFile ?: return null
        if (!parent.exists() && !parent.mkdirs()) return null
        if (hasSymlink(parent) || !insideFilesDir(parent) || !parent.isDirectory) return null
        if (!root.mkdir() || rootStatus() != RootStatus.READY) {
            if (root.isDirectory && root.listFiles().isNullOrEmpty()) root.delete()
            return null
        }
        return "created"
    }

    @Synchronized fun removeRootIfEmpty(): Boolean {
        if (!root.exists()) return true
        if (rootStatus() != RootStatus.READY) return false
        return root.listFiles()?.isEmpty() == true && root.delete()
    }

    @Synchronized fun rootExists(): Boolean = root.exists()

    /**
     * Measures the actual guest-visible workspace without following symbolic links.
     * This detects quota overrun from direct CPython writes; it is deliberately not described
     * as a hard in-flight quota because PRoot has no filesystem quota boundary.
     */
    @Synchronized fun usageBytes(): Long? {
        if (rootStatus() != RootStatus.READY) return null
        var total = 0L
        var entries = 0
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: return null
            for (child in children) {
                entries++
                if (entries > MAX_ENTRIES) return MAX_TOTAL_BYTES + 1L
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) return null
                if (child.isDirectory) stack.add(child)
                else if (child.isFile) {
                    total += child.length()
                    if (total > MAX_TOTAL_BYTES) return total
                } else return null
            }
        }
        return total
    }

    @Synchronized fun quotaStatus(): String {
        val used = usageBytes() ?: return "unsafe"
        return if (used <= MAX_TOTAL_BYTES) "ok" else "exceeded"
    }

    @Synchronized fun list(): String {
        ensureRoot()
        return root.walkTopDown()
            .onEnter { it == root || it.name !in IGNORED_LIST_DIRECTORIES }
            .filter { it != root && it.isFile && (!it.isHidden || it.name == ".gitignore") }
            .take(500).map { root.toPath().relativize(it.toPath()).toString() }
            .sorted().joinToString("\n").ifBlank { "(workspace empty)" }.take(12_000)
    }
    @Synchronized fun read(path: String): String {
        val file = resolve(path, true) ?: return "error: invalid or missing workspace file"
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return "error: file is not regular or exceeds 512 MiB"
        return runCatching { FileInputStream(file).use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrElse { "error: workspace read failed (${it.javaClass.simpleName})" }
    }
    @Synchronized fun write(path: String, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) return "error: workspace files are limited to 512 MiB"
        val file = resolve(path, false) ?: return "error: invalid workspace path"
        val parent = file.parentFile ?: return "error: invalid workspace path"
        if (!parent.exists() && !parent.mkdirs()) return "error: could not create workspace directory"
        if (!insideRoot(parent) || hasSymlink(parent)) return "error: workspace path crossed a symlink boundary"
        val current = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val previous = if (file.isFile) file.length() else 0L
        if (current - previous + bytes.size > MAX_TOTAL_BYTES) return "error: workspace storage quota (8 GiB) exceeded"
        if (filesDir.usableSpace - bytes.size < MIN_FREE_SPACE_BYTES) {
            return "error: workspace write paused to preserve at least 1 GiB free on the device"
        }
        // Automatic backup before any destructive overwrite: preserve the exact prior contents
        // so the owner can always restore the pre-write state from the recycle/backup area.
        if (file.isFile) {
            val backedUp = backup(path)
            if (!backedUp) return "error: could not create automatic backup before overwrite"
        }
        val temp = File(parent, ".orion-${file.name}.part")
        return try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            try {
                java.nio.file.Files.move(
                    temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                if (file.exists() && !file.delete()) error("could not replace target")
                if (!temp.renameTo(file)) error("could not commit workspace file")
            }
            "saved ${path.take(180)} (${bytes.size} bytes)"
        } catch (e: Exception) {
            temp.delete()
            "error: workspace write failed (${e.javaClass.simpleName})"
        }
    }
    /** Snapshot helper for the conflict-safe undo journal; null means absent or unreadable. */
    @Synchronized fun readForUndo(path: String): String? {
        val file = resolve(path, false) ?: return null
        if (!file.exists()) return null
        if (!file.isFile || file.length() > MAX_FILE_BYTES || java.nio.file.Files.isSymbolicLink(file.toPath())) return null
        return runCatching { FileInputStream(file).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    /** Move a regular workspace file without replacing an existing destination. */
    @Synchronized fun move(source: String, destination: String): String {
        val from = resolve(source, true) ?: return "error: invalid or missing source workspace file"
        val to = resolve(destination, false) ?: return "error: invalid destination workspace path"
        if (!from.isFile || java.nio.file.Files.isSymbolicLink(from.toPath())) return "error: source is not a regular workspace file"
        if (to.exists()) return "error: destination already exists; refusing to overwrite it"
        val parent = to.parentFile ?: return "error: invalid destination workspace path"
        if (!parent.exists() && !parent.mkdirs()) return "error: could not create destination directory"
        if (!insideRoot(parent) || hasSymlink(parent)) return "error: destination crossed a symlink boundary"
        return try {
            // No REPLACE_EXISTING and no renameTo fallback: a destination race must fail,
            // never overwrite a file that appeared after the preflight check.
            java.nio.file.Files.move(from.toPath(), to.toPath())
            "moved $source -> $destination"
        } catch (e: Exception) {
            "error: workspace move failed (${e.javaClass.simpleName}); destination was not overwritten"
        }
    }

    @Synchronized fun delete(path: String): Boolean {
        val file = resolve(path, true) ?: return false
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return false
        ensureTrashRoot()
        val relative = normalizePath(path) ?: return false
        val target = File(trash, relative)
        val parent = target.parentFile ?: return false
        if (!parent.mkdirs() && !parent.isDirectory) return false
        return file.renameTo(target)
    }

    /** List files currently in the recycle location, as relative paths. */
    @Synchronized fun listTrash(): String {
        ensureTrashRoot()
        return trash.walkTopDown()
            .filter { it != trash && it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .map { trash.toPath().relativize(it.toPath()).toString() }
            .sorted().joinToString("\n").ifBlank { "(trash empty)" }.take(12_000)
    }

    /** Restore a file from the recycle location back into the workspace at its original path. */
    @Synchronized fun restoreFromTrash(path: String): String {
        val relative = normalizePath(path) ?: return "error: invalid workspace path"
        val source = File(trash, relative)
        if (!source.isFile || java.nio.file.Files.isSymbolicLink(source.toPath())) return "error: no trashed file at $path"
        val destination = resolve(path, false) ?: return "error: invalid workspace path"
        if (destination.exists()) return "error: $path already exists in workspace; remove it first, then restore"
        val parent = destination.parentFile ?: return "error: invalid workspace path"
        if (!parent.mkdirs() && !parent.isDirectory) return "error: could not create workspace directory"
        if (!insideRoot(parent) || hasSymlink(parent)) return "error: restore path crossed a symlink boundary"
        return try {
            java.nio.file.Files.move(source.toPath(), destination.toPath())
            "restored $path from recycle"
        } catch (e: Exception) {
            "error: restore from recycle failed (${e.javaClass.simpleName})"
        }
    }

    /** Permanently remove all files from the recycle location. */
    @Synchronized fun emptyTrash(): Int {
        ensureTrashRoot()
        var count = 0
        trash.walkTopDown().filter { it != trash && it.isFile }.forEach {
            if (it.delete()) count++
        }
        return count
    }

    /** Returns the number of files currently in the recycle location. */
    @Synchronized fun trashCount(): Int {
        ensureTrashRoot()
        return trash.walkTopDown().filter { it != trash && it.isFile }.count()
    }

    /** Remove partial ".part" temp files left by interrupted writes, and orphaned trash entries whose
     *  workspace file has been re-created. Safe to call at any time. */
    @Synchronized fun cleanupInterruptedWrites(): Int {
        ensureRoot()
        ensureTrashRoot()
        var count = 0
        root.walkTopDown().filter { it.isFile && it.name.startsWith(".orion-") && it.name.endsWith(".part") }.forEach {
            if (it.delete()) count++
        }
        return count
    }

    private fun ensureTrashRoot() {
        if (!trash.exists()) trash.mkdirs()
    }

    private fun ensureBackupRoot() {
        if (!backup.exists()) backup.mkdirs()
    }

    /** Copy an existing workspace file into the recycle/backup area with a timestamp suffix,
     *  so destructive overwrites can always be reversed. Returns false if the file cannot be read
     *  or the backup cannot be written. */
    @Synchronized fun backup(path: String): Boolean {
        val file = resolve(path, true) ?: return false
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return false
        ensureBackupRoot()
        val relative = normalizePath(path) ?: return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val target = File(backup, "${relative.replace("/", "_")}.$stamp.bak")
        val parent = target.parentFile ?: return false
        if (!parent.mkdirs() && !parent.isDirectory) return false
        return runCatching {
            FileInputStream(file).use { src ->
                FileOutputStream(target).use { dst -> src.copyTo(dst) }
            }
            true
        }.getOrDefault(false)
    }

    /** List files currently in the automatic backup area, as relative paths. */
    @Synchronized fun listBackup(): String {
        ensureBackupRoot()
        return backup.walkTopDown()
            .filter { it != backup && it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .map { backup.toPath().relativize(it.toPath()).toString() }
            .sorted().joinToString("\n").ifBlank { "(backup empty)" }.take(12_000)
    }

    /** Restore a file from the automatic backup area back into the workspace at its original path. */
    @Synchronized fun restoreFromBackup(path: String): String {
        val relative = normalizePath(path) ?: return "error: invalid workspace path"
        // Find the most recent backup for this path
        ensureBackupRoot()
        val prefix = "${relative.replace("/", "_")}."
        val candidates = backup.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(".bak") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val source = candidates.firstOrNull() ?: return "error: no backup found for $path"
        val destination = resolve(path, false) ?: return "error: invalid workspace path"
        if (destination.exists()) return "error: $path already exists in workspace; remove it first, then restore"
        val parent = destination.parentFile ?: return "error: invalid workspace path"
        if (!parent.mkdirs() && !parent.isDirectory) return "error: could not create workspace directory"
        if (!insideRoot(parent) || hasSymlink(parent)) return "error: restore path crossed a symlink boundary"
        return try {
            java.nio.file.Files.copy(source.toPath(), destination.toPath())
            "restored $path from automatic backup"
        } catch (e: Exception) {
            "error: restore from backup failed (${e.javaClass.simpleName})"
        }
    }

    /** Returns the number of files currently in the automatic backup area. */
    @Synchronized fun backupCount(): Int {
        ensureBackupRoot()
        return backup.walkTopDown().filter { it != backup && it.isFile }.count()
    }

    private fun ensureRoot() {
        check(rootStatus() != RootStatus.UNSAFE) { "workspace path is unsafe" }
        if (!root.exists()) check(root.mkdirs()) { "workspace unavailable" }
        check(rootStatus() == RootStatus.READY) { "workspace unavailable" }
    }
    /** Accept both host-relative paths and the guest-visible /workspace mount path. */
    private fun normalizePath(path: String): String? {
        val relative = when {
            path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
            path == "/workspace" -> return null
            path.startsWith("/") -> return null
            else -> path
        }
        val segments = relative.split('/')
        if (!NAME.matches(relative) || segments.any {
                it.isBlank() || it == "." || it == ".." || (it.startsWith(".") && it != ".gitignore")
            }) return null
        return relative
    }

    private fun resolve(path: String, mustExist: Boolean): File? {
        val relative = normalizePath(path) ?: return null
        ensureRoot()
        val target = File(root, relative)
        val parent = target.parentFile ?: return null
        if (!insideRoot(parent) || hasSymlink(parent)) return null
        if (mustExist && (!target.exists() || !insideRoot(target) || java.nio.file.Files.isSymbolicLink(target.toPath()))) return null
        if (!mustExist && target.exists() && java.nio.file.Files.isSymbolicLink(target.toPath())) return null
        return target
    }
    private fun insideFilesDir(file: File): Boolean {
        val base = filesDir.canonicalFile.toPath()
        return runCatching { file.canonicalFile.toPath().startsWith(base) }.getOrDefault(false)
    }
    private fun insideRoot(file: File): Boolean {
        val base = root.canonicalFile.toPath()
        return runCatching { file.canonicalFile.toPath().startsWith(base) }.getOrDefault(false)
    }
    private fun hasSymlink(file: File): Boolean {
        val base = filesDir.canonicalFile.toPath()
        var cursor: File? = file.absoluteFile
        while (cursor != null) {
            val current = cursor ?: break
            if (!current.toPath().normalize().startsWith(base)) break
            if (java.nio.file.Files.isSymbolicLink(current.toPath())) return true
            val canonical = runCatching { current.canonicalFile.toPath() }.getOrNull()
            if (canonical == base) break
            cursor = current.parentFile
        }
        return false
    }
}
