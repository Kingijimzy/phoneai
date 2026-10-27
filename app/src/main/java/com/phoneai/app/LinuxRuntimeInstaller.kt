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
 * length and SHA-256 match. Extraction uses the traversal-safe TarExtractor and staging directory.
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
        check(base.mkdirs() || base.isDirectory) { "Could not create Linux runtime directory." }
        check(downloads.mkdirs() || downloads.isDirectory) { "Could not create downloads directory." }

        val archive = download(manifest.rootfs(), "rootfs-${manifest.version}.tar.gz", LinuxRuntimePolicy.MAX_ROOTFS_BYTES)
        val prootFile = download(manifest.proot(), "proot-${manifest.version}", LinuxRuntimePolicy.MAX_PROOT_BYTES)
        val staging = File(base, "rootfs.staging")
        val rootfsBackup = File(base, "rootfs.previous")
        val prootBackup = File(base, "proot.previous")
        val markerBackup = File(base, "installed-version.previous")
        val prootTemp = File(proot.parentFile ?: File(base, "bin").also { it.mkdirs() }, "proot.part")
        val markerTemp = File(base, "installed-version.part")

        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create staging directory." }
        try {
            GZIPInputStream(BufferedInputStream(archive.inputStream())).use { TarExtractor.extract(it, staging) }
            check(File(staging, "bin/sh").isFile) { "Verified rootfs has no /bin/sh." }

            // Prepare every new file before moving the active installation.
            prootFile.copyTo(prootTemp, overwrite = true)
            check(prootTemp.setExecutable(true, true)) { "Could not mark verified proot executable." }
            markerTemp.writeText(manifest.version)

            rootfsBackup.deleteRecursively()
            prootBackup.delete()
            markerBackup.delete()
            if (rootfs.exists()) check(rootfs.renameTo(rootfsBackup)) { "Could not back up existing rootfs." }
            if (proot.exists()) check(proot.renameTo(prootBackup)) { "Could not back up existing proot." }
            if (marker.exists()) check(marker.renameTo(markerBackup)) { "Could not back up install marker." }

            try {
                check(staging.renameTo(rootfs)) { "Could not activate new rootfs." }
                check(prootTemp.renameTo(proot)) { "Could not activate verified proot." }
                check(markerTemp.renameTo(marker)) { "Could not commit install marker." }
                check(File(rootfs, "bin/sh").isFile && proot.canExecute()) { "Installed runtime failed readiness checks." }
            } catch (failure: Throwable) {
                // Roll back all active paths together; never leave a mixed-version runtime marked ready.
                rootfs.deleteRecursively()
                proot.delete()
                marker.delete()
                if (rootfsBackup.exists()) check(rootfsBackup.renameTo(rootfs)) { "Rollback failed restoring rootfs." }
                if (prootBackup.exists()) check(prootBackup.renameTo(proot)) { "Rollback failed restoring proot." }
                if (markerBackup.exists()) check(markerBackup.renameTo(marker)) { "Rollback failed restoring install marker." }
                throw failure
            }
            rootfsBackup.deleteRecursively()
            prootBackup.delete()
            markerBackup.delete()
        } finally {
            staging.deleteRecursively()
            prootTemp.delete()
            markerTemp.delete()
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
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        if (output.length < 6000) output.append(line).append('\n')
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs.coerceIn(250L, 60_000L), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            reader.join(500)
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
        val offset = part.length()
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
        } finally {
            connection.disconnect()
        }
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
