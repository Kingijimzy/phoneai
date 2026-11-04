package com.phoneai.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** App-private persistent Python workspace. Paths cannot escape this directory or follow symlinks. */
internal class OrionPythonWorkspace(context: Context) {
    private val filesDir = context.applicationContext.filesDir
    private val root = File(filesDir, "orion-python/workspace")
    companion object {
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        private const val MAX_ENTRIES = 50_000
        private val IGNORED_LIST_DIRECTORIES = setOf("node_modules", ".gradle", ".git", ".idea", "build", "dist", "target", "__pycache__", ".venv", "venv")
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,179}$")
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
            .filter { it != root && it.isFile && !it.isHidden }
            .take(500).map { root.toPath().relativize(it.toPath()).toString() }
            .sorted().joinToString("\n").ifBlank { "(workspace empty)" }.take(12_000)
    }
    @Synchronized fun read(path: String): String {
        val file = resolve(path, true) ?: return "error: invalid or missing workspace file"
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return "error: file is not regular or exceeds 2 MiB"
        return runCatching { FileInputStream(file).use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrElse { "error: workspace read failed (${it.javaClass.simpleName})" }
    }
    @Synchronized fun write(path: String, content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) return "error: workspace files are limited to 2 MiB"
        val file = resolve(path, false) ?: return "error: invalid workspace path"
        val parent = file.parentFile ?: return "error: invalid workspace path"
        if (!parent.exists() && !parent.mkdirs()) return "error: could not create workspace directory"
        if (!insideRoot(parent) || hasSymlink(parent)) return "error: workspace path crossed a symlink boundary"
        val current = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val previous = if (file.isFile) file.length() else 0L
        if (current - previous + bytes.size > MAX_TOTAL_BYTES) return "error: workspace storage quota (256 MiB) exceeded"
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
    @Synchronized fun delete(path: String): Boolean {
        val file = resolve(path, true) ?: return false
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return false
        return file.delete()
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
        if (!NAME.matches(relative) || relative.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
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
