package com.phoneai.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Installs the pinned Alpine rootfs in app-private storage. PRoot is packaged as an ABI-specific
 * native library and executed from nativeLibraryDir (never copied from downloads to filesDir).
 */
internal class LinuxRuntimeInstaller(
    context: Context,
    private val progress: (String, Long, Long) -> Unit = { _, _, _ -> }
) {
    private val app = context.applicationContext
    private val base = File(app.filesDir, "orion-linux")
    private val downloads = File(base, "downloads")
    private val prootTmp = File(base, "proot-tmp")
    private val rootfs = File(base, "rootfs")
    private val proot = File(app.applicationInfo.nativeLibraryDir, "libproot.so")
    private val prootLoader = File(app.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    private val marker = File(base, "installed-version")
    /** Survives process death during apk mutation; the next Linux command restores the pinned base rootfs. */
    private val packageOperationMarker = File(base, "package-operation-in-progress")

    fun isReady(manifest: LinuxRuntimePolicy.Manifest): Boolean =
        !packageOperationMarker.exists() && isReadyIgnoringPackageMarker(manifest)

    private fun isReadyIgnoringPackageMarker(manifest: LinuxRuntimePolicy.Manifest): Boolean =
        LinuxRuntimePolicy.validate(manifest) == null &&
            LinuxRuntimePolicy.isReady(marker.takeIf { it.isFile }?.readText()?.trim(), manifest) &&
            proot.isFile && proot.canExecute() && sha256(proot) == manifest.prootSha256 &&
            prootLoader.isFile && prootLoader.canExecute() && sha256(prootLoader) == LinuxRuntimePolicy.PROOT_LOADER_SHA256 &&
            File(rootfs, "bin/sh").exists()

    @Synchronized
    fun install(manifest: LinuxRuntimePolicy.Manifest): Result<Unit> = runCatching {
        LinuxRuntimePolicy.validate(manifest)?.let { error("Invalid Linux manifest: $it") }
        check(proot.isFile && proot.canExecute()) {
            "Bundled ARM64 PRoot is missing or not executable. Rebuild the APK with the pinned native runtime."
        }
        check(sha256(proot) == manifest.prootSha256) { "Bundled PRoot SHA-256 mismatch; refusing Linux runtime." }
        check(prootLoader.isFile && prootLoader.canExecute()) {
            "Bundled PRoot loader is missing or not executable. Rebuild the APK with libproot-loader.so."
        }
        check(sha256(prootLoader) == LinuxRuntimePolicy.PROOT_LOADER_SHA256) {
            "Bundled PRoot loader SHA-256 mismatch; refusing Linux runtime."
        }
        check(base.mkdirs() || base.isDirectory) { "Could not create Linux runtime directory." }
        check(downloads.mkdirs() || downloads.isDirectory) { "Could not create downloads directory." }

        val archive = download(manifest.rootfs(), "rootfs-${manifest.version}.tar.gz", LinuxRuntimePolicy.MAX_ROOTFS_BYTES)
        val staging = File(base, "rootfs.staging")
        val backup = File(base, "rootfs.previous")
        val markerBackup = File(base, "installed-version.previous")
        val markerTemp = File(base, "installed-version.part")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create staging directory." }
        try {
            GZIPInputStream(BufferedInputStream(archive.inputStream())).use { TarExtractor.extract(it, staging) }
            val guestShell = File(staging, "bin/sh")
            check(guestShell.exists()) { "Verified rootfs has no /bin/sh." }
            check(File(staging, "etc/alpine-release").isFile) { "Rootfs is not a valid Alpine installation." }

            // Verify the symlink target and executable mode before invoking PRoot. A file's
            // existence alone is insufficient: Android's extraction defaults can strip +x.
            val resolvedShell = runCatching { guestShell.canonicalFile }.getOrNull()
            check(resolvedShell != null && resolvedShell.isFile) {
                "Alpine /bin/sh is a broken symlink. Resolved path: ${resolvedShell?.path ?: "unavailable"}"
            }
            check(resolvedShell.canExecute()) {
                "Alpine shell target is not executable: ${resolvedShell.path}; mode restoration failed."
            }
            check(guestShell.canExecute()) {
                "Alpine /bin/sh is not executable after extraction: ${guestShell.path}."
            }

            // Acceptance test runs against staging before touching the working installation.
            val smoke = runGuest(staging, "printf 'ORION_PROOT_OK\\n'; uname -m; cat /etc/alpine-release", 10_000L)
            check(smoke.exitCode == 0 && smoke.output.contains("ORION_PROOT_OK") &&
                Regex("(?m)^aarch64\\s*$").containsMatchIn(smoke.output) &&
                smoke.output.contains("3.22.7")) {
                "Guest-shell acceptance test failed; previous runtime was preserved. Output: ${smoke.output.take(500)}"
            }
            markerTemp.writeText(manifest.version)
            backup.deleteRecursively()
            markerBackup.delete()
            try {
                if (rootfs.exists()) check(rootfs.renameTo(backup)) { "Could not back up existing rootfs." }
                if (marker.exists()) check(marker.renameTo(markerBackup)) { "Could not back up install marker." }
                check(staging.renameTo(rootfs)) { "Could not activate new rootfs." }
                check(markerTemp.renameTo(marker)) { "Could not commit install marker." }
                check(isReadyIgnoringPackageMarker(manifest)) { "Installed runtime failed readiness checks." }
                // Clear the interrupted-operation marker only after the fresh rootfs passes all checks.
                if (packageOperationMarker.exists()) {
                    check(packageOperationMarker.delete()) { "Could not clear the package recovery marker after rootfs repair." }
                }
            } catch (failure: Throwable) {
                rootfs.deleteRecursively()
                marker.delete()
                if (backup.exists()) check(backup.renameTo(rootfs)) { "Rollback failed restoring rootfs." }
                if (markerBackup.exists()) check(markerBackup.renameTo(marker)) { "Rollback failed restoring install marker." }
                throw failure
            }
            backup.deleteRecursively()
            markerBackup.delete()
        } finally {
            staging.deleteRecursively()
            markerTemp.delete()
        }
    }

    fun execute(manifest: LinuxRuntimePolicy.Manifest, command: String, timeoutMs: Long = 60_000L): String {
        if (!isReady(manifest)) return "error: Alpine runtime is not installed or failed readiness checks. Install/repair the pinned Linux runtime first."
        if (timeoutMs !in 250L..180_000L) return "error: Linux timeout must be between 250 and 180000 ms."
        if (command == "__orion_install__") return "error: internal command is not executable"
        val args = LinuxRuntimePolicy.prootArgs(proot.absolutePath, rootfs.absolutePath, command)
            ?: return "error: Linux command rejected by runtime policy."
        val packageMutation = ShellExecutionRouting.isPackageOperation(command)
        if (packageMutation) {
            // If Android kills Orion mid-install, this durable marker forces a clean pinned-rootfs
            // recovery before any later command can use a potentially half-written apk database.
            try {
                check(base.mkdirs() || base.isDirectory) { "Could not create Linux runtime directory." }
                packageOperationMarker.writeText("Interrupted apk operation requires recovery.")
            } catch (failure: Exception) {
                return "error: could not protect the Alpine package database before mutation: ${(failure.message ?: failure.javaClass.simpleName).take(180)}"
            }
        }
        val result = try {
            runProcess(args, rootfs, timeoutMs)
        } catch (failure: IOException) {
            // Keep the marker after interrupted package operations so the next call repairs the rootfs.
            return "error: Linux process I/O failed safely: ${(failure.message ?: failure.javaClass.simpleName).take(240)}"
        } catch (failure: SecurityException) {
            return "error: Android denied Linux process execution: ${(failure.message ?: "permission denied").take(240)}"
        }
        if (packageMutation && !result.timedOut && result.exitCode == 0) {
            packageOperationMarker.delete()
        }
        return if (result.timedOut) "error: Linux command timed out after ${timeoutMs} ms" +
            if (packageMutation) ". The next Linux command will restore the pinned base rootfs before continuing." else ""
        else "exit=${result.exitCode}\n${result.output}" +
            if (packageMutation && result.exitCode != 0) "\nPackage operation failed; the next Linux command will restore the pinned base rootfs before continuing." else ""
    }

    private data class ProcessResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    private fun runGuest(root: File, command: String, timeoutMs: Long): ProcessResult {
        val args = LinuxRuntimePolicy.prootArgs(proot.absolutePath, root.absolutePath, command)
            ?: error("Linux acceptance command rejected by policy.")
        return runProcess(args, root, timeoutMs)
    }

    private fun runProcess(args: List<String>, workingDir: File, timeoutMs: Long): ProcessResult {
        check(prootTmp.mkdirs() || prootTmp.isDirectory) {
            "Could not create writable PRoot temporary directory."
        }
        check(prootTmp.isDirectory && prootTmp.canWrite()) {
            "PRoot temporary directory is not writable."
        }
        val process = ProcessBuilder(args).directory(workingDir).redirectErrorStream(true).apply {
            environment().clear()
            environment()["HOME"] = "/root"
            environment()["PATH"] = "/usr/local/bin:/usr/bin:/bin"
            environment()["TERM"] = "dumb"
            environment()["LANG"] = "C.UTF-8"
            environment()["TMPDIR"] = "/tmp"
            environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
            // Required on Android 10+ to execute guest ELF binaries despite app-data W^X policy.
            environment()["PROOT_LOADER"] = prootLoader.absolutePath
        }.start()
        val output = StringBuilder()
        // A process may close its pipe while the worker is blocked in read(). Android can
        // surface that race as InterruptedIOException when cancellation/timeout closes the
        // stream on another thread. Keep expected shutdown noise inside the worker instead of
        // letting it become an uncaught thread exception.
        val readerFailure = java.util.concurrent.atomic.AtomicReference<IOException?>(null)
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = try {
                            reader.readLine()
                        } catch (closed: InterruptedIOException) {
                            if (process.isAlive) readerFailure.compareAndSet(null, closed)
                            break
                        } ?: break
                        synchronized(output) {
                            if (output.length < 6000) output.append(line).append('\n')
                        }
                    }
                }
            } catch (closed: IOException) {
                // Closing the pipe after process exit is normal; retain only unexpected errors.
                if (process.isAlive) readerFailure.compareAndSet(null, closed)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (failure: Throwable) {
                readerFailure.compareAndSet(null, IOException("Process output reader failed", failure))
            }
        }, "OrionLinuxProcessOutput").apply {
            isDaemon = true
            start()
        }

        val finished = try {
            process.waitFor(timeoutMs.coerceIn(250L, 180_000L), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            process.destroy()
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for Linux process.", interrupted)
        }

        if (!finished) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }

        // Process termination closes the pipe and releases a blocked reader. Give it time to
        // drain remaining output, then close the stream only after the process is no longer alive.
        try {
            reader.join(1_000)
            if (reader.isAlive && !process.isAlive) {
                runCatching { process.inputStream.close() }
                reader.join(250)
            }
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            reader.interrupt()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while collecting Linux process output.", interrupted)
        }

        val captured = synchronized(output) { output.toString().take(6000) }
        if (finished && readerFailure.get() != null) {
            throw IOException("Could not reliably collect Linux process output.", readerFailure.get())
        }
        return if (!finished) ProcessResult(-1, captured, true)
        else ProcessResult(process.exitValue(), captured, false)
    }

    private fun download(a: LinuxRuntimePolicy.Artifact, name: String, maxBytes: Long): File {
        require(a.bytes in 1..maxBytes) { "Artifact size exceeds policy." }
        val destination = File(downloads, name)
        if (destination.isFile && destination.length() == a.bytes && sha256(destination) == a.sha256) {
            progress(name, a.bytes, a.bytes); return destination
        }
        val part = File(downloads, "$name.part")
        if (part.length() > a.bytes) part.delete()
        val offset = part.length()
        val connection = (URL(a.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 45_000; instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val status = connection.responseCode
            if (offset > 0 && status != HttpURLConnection.HTTP_PARTIAL) {
                part.delete(); connection.disconnect(); return download(a, name, maxBytes)
            }
            check(status in 200..299) { "Rootfs download failed: HTTP $status" }
            val append = offset > 0 && status == HttpURLConnection.HTTP_PARTIAL
            var total = if (append) offset else 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n
                        check(total <= a.bytes && total <= maxBytes) { "Rootfs exceeded pinned size." }
                        output.write(buffer, 0, n); progress(name, total, a.bytes)
                    }
                }
            }
            check(part.length() == a.bytes) { "Rootfs length does not match pinned manifest." }
            check(sha256(part) == a.sha256) { "Rootfs SHA-256 mismatch; refusing archive." }
            if (destination.exists()) check(destination.delete()) { "Could not replace stale rootfs archive." }
            check(part.renameTo(destination)) { "Could not commit verified rootfs archive." }
            return destination
        } finally { connection.disconnect() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
