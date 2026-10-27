package com.phoneai.app

import java.security.MessageDigest

/**
 * Rules for Orion's bundled Linux runtime (a root filesystem run with proot, so no root is needed).
 * Pure logic only: the manifest must be pinned and https, downloads must match their checksum, the runtime
 * counts as ready only for the exact version that was installed, and every command goes through one
 * proot invocation with a cleared environment and a fixed set of bind mounts.
 */
internal object LinuxRuntimePolicy {
    enum class State { NOT_INSTALLED, DOWNLOADING, VERIFYING, EXTRACTING, READY, FAILED }

    data class Manifest(val version: String, val rootfsUrl: String, val rootfsSha256: String, val rootfsBytes: Long)

    const val MAX_ROOTFS_BYTES = 300_000_000L
    const val MAX_COMMAND = 4000
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val versionPattern = Regex("^[A-Za-z0-9._-]{1,32}$")

    /** Returns a reason when the manifest must not be used, or null when it is acceptable. */
    fun validate(m: Manifest): String? = when {
        !versionPattern.matches(m.version) -> "version must be 1-32 letters, digits, dots, dashes or underscores"
        !m.rootfsUrl.startsWith("https://") || m.rootfsUrl.any { it.isWhitespace() } -> "download address must be https with no spaces"
        !sha256Pattern.matches(m.rootfsSha256) -> "checksum must be 64 lowercase hex characters"
        m.rootfsBytes <= 0 || m.rootfsBytes > MAX_ROOTFS_BYTES -> "download size is out of range"
        else -> null
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** A download is trusted only when it matches the pinned checksum exactly. */
    fun checksumMatches(expected: String, bytes: ByteArray): Boolean =
        sha256Pattern.matches(expected) && sha256Hex(bytes) == expected

    /** Ready only when the marker written after a successful install names this exact version. */
    fun isReady(installedVersion: String?, m: Manifest): Boolean = installedVersion != null && installedVersion == m.version

    /**
     * The one way a command reaches the Linux runtime: proot with the root filesystem, /dev and /proc bound,
     * a cleared environment, and a fixed working directory. Returns null for anything unsafe.
     */
    fun prootArgs(prootPath: String, rootfsDir: String, command: String): List<String>? {
        if (command.isBlank() || command.length > MAX_COMMAND || command.contains('\u0000')) return null
        if (!prootPath.startsWith("/") || !rootfsDir.startsWith("/") || rootfsDir.contains("..")) return null
        return listOf(
            prootPath, "-r", rootfsDir, "-b", "/dev", "-b", "/proc", "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/bin:/usr/bin:/bin", "TERM=dumb", "LANG=C.UTF-8",
            "/bin/sh", "-c", command
        )
    }
}
