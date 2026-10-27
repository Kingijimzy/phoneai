package com.phoneai.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * App-private, resumable Linux runtime installation. Neither artifact is trusted until its pinned
 * length and SHA-256 match. Extraction uses the existing traversal-safe TarExtractor and a staging
 * directory; Android root access is not required.
 */
internal class LinuxRuntimeInstaller(
    context: Context,
    private val progress: (String, Long, Long) -> Unit = { _, _, _ -> }
) {
    private val base = File(context.filesDir, "orion-linux")
    private val downloads = File(base, "downloads")
    private val rootfs = File(base, "rootfs")
    private val proot = File(base, "bin/proot")
    private val marker = File(base, "installed-version")

    fun isReady(manifest: LinuxRuntimePolicy.Manifest): Boolean =
        LinuxRuntimePolicy.validate(manifest) == null &&
            LinuxRuntimePolicy.isReady(marker.takeIf { it.isFile }?.readText()?.trim(), manifest) &&
            proot.isFile && proot.canExecute() && File(rootfs, "bin/sh").isFile

    @Synchronized
    fun install(manifest: LinuxRuntimePolicy.Manifest): Result<Unit> = runCatching {
        LinuxRuntimePolicy.validate(manifest)?.let { error("Invalid Linux manifest: $it") }
        downloads.mkdirs()
        val archive = download(manifest.rootfs(), "rootfs-${manifest.version}.tar.gz", LinuxRuntimePolicy.MAX_ROOTFS_BYTES)
        val prootFile = download(manifest.proot(), "proot-${manifest.version}", LinuxRuntimePolicy.MAX_PROOT_BYTES)
        val staging = File(base, "rootfs.staging")
        val backup = File(base, "rootfs.previous")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create staging directory." }
        try {
            GZIPInputStream(BufferedInputStream(archive.inputStream())).use { TarExtractor.extract(it, staging) }
            check(File(staging, "bin/sh").isFile) { "Verified rootfs has no /bin/sh." }
            if (backup.exists()) check(backup.deleteRecursively()) { "Could not clear old backup." }
            if (rootfs.exists()) check(rootfs.renameTo(backup)) { "Could not stage old rootfs." }
            if (!staging.renameTo(rootfs)) {
                if (backup.exists()) backup.renameTo(rootfs)
                error("Could not activate new rootfs.")
            }
            proot.parentFile?.mkdirs()
            val temp = File(proot.parentFile, "proot.part")
            prootFile.copyTo(temp, overwrite = true)
            check(temp.setExecutable(true, true)) { "Could not mark verified proot executable." }
            check(temp.renameTo(proot)) { "Could not activate verified proot." }
            val markerTemp = File(base, "installed-version.part")
            markerTemp.writeText(manifest.version)
            check(markerTemp.renameTo(marker)) { "Could not commit install marker." }
            backup.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }

    fun execute(manifest: LinuxRuntimePolicy.Manifest, command: String, timeoutMs: Long = 15_000L): String {
        check(isReady(manifest)) { "Linux runtime is not installed for version ${manifest.version}." }
        val args = LinuxRuntimePolicy.prootArgs(proot.absolutePath, rootfs.absolutePath, command)
            ?: error("Linux command rejected by runtime policy.")
        val process = ProcessBuilder(args).directory(rootfs).redirectErrorStream(true).apply {
            environment().clear()
            environment()["HOME"] = "/root"
            environment()["PATH"] = "/usr/local/bin:/usr/bin:/bin"
            environment()["TERM"] = "dumb"
            environment()["LANG"] = "C.UTF-8"
        }.start()
        // Drain output concurrently so a full pipe cannot deadlock waitFor().
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (output.length < 6000) synchronized(output) { output.append(line).append('\n') } }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs.coerceIn(250L, 60_000L), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            return "error: Linux command timed out"
        }
        reader.join(500)
        return "exit=${process.exitValue()}\n${synchronized(output) { output.toString().take(6000) }}"
    }

    private fun download(a: LinuxRuntimePolicy.Artifact, name: String, maxBytes: Long): File {
        require(a.bytes in 1..maxBytes) { "Artifact size exceeds policy." }
        val destination = File(downloads, name)
        if (destination.isFile && destination.length() == a.bytes && sha256(destination) == a.sha256) {
            progress(name, a.bytes, a.bytes)
            return destination
        }
        val part = File(downloads, "$name.part")
        if (part.length() > a.bytes) part.delete()
        var offset = part.length()
        val connection = (URL(a.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val status = connection.responseCode
            if (offset > 0 && status != HttpURLConnection.HTTP_PARTIAL) {
                part.delete()
                offset = 0L
                connection.disconnect()
                return download(a, name, maxBytes)
            }
            check(status in 200..299) { "Artifact download failed: HTTP $status" }
            val append = offset > 0 && status == HttpURLConnection.HTTP_PARTIAL
            var total = if (append) offset else 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        check(total <= a.bytes && total <= maxBytes) { "Artifact exceeded pinned size." }
                        output.write(buffer, 0, n)
                        progress(name, total, a.bytes)
                    }
                }
            }
            check(part.length() == a.bytes) { "Artifact length does not match pinned manifest." }
            check(sha256(part) == a.sha256) { "SHA-256 mismatch; refusing artifact." }
            if (destination.exists()) check(destination.delete()) { "Could not replace stale artifact." }
            check(part.renameTo(destination)) { "Could not commit verified artifact." }
            return destination
        } finally { connection.disconnect() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
