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
        const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,179}$")
    }
    enum class RootStatus { MISSING, READY, UNSAFE }

    @Synchronized fun rootStatus(): RootStatus {
        if (hasSymlink(root) || !insideFilesDir(root)) return RootStatus.UNSAFE
        if (!root.exists()) return RootStatus.MISSING
        return if (root.isDirectory && root.canRead() && root.canWrite()) RootStatus.READY else RootStatus.UNSAFE
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

    @Synchronized fun list(): String {
        ensureRoot()
        return root.walkTopDown().filter { it != root && it.isFile && !it.isHidden }
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
        if (current - previous + bytes.size > MAX_TOTAL_BYTES) return "error: workspace storage quota (20 MiB) exceeded"
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
    private fun ensureRoot() {
        check(rootStatus() != RootStatus.UNSAFE) { "workspace path is unsafe" }
        if (!root.exists()) check(root.mkdirs()) { "workspace unavailable" }
        check(rootStatus() == RootStatus.READY) { "workspace unavailable" }
    }
    private fun resolve(path: String, mustExist: Boolean): File? {
        if (!NAME.matches(path) || path.startsWith("/") || path.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        ensureRoot()
        val target = File(root, path)
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
        var cursor: File? = file
        while (cursor != null && cursor.path.startsWith(root.path)) {
            if (java.nio.file.Files.isSymbolicLink(cursor.toPath())) return true
            if (cursor == root) break
            cursor = cursor.parentFile
        }
        return false
    }
}
