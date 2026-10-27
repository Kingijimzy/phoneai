package com.phoneai.app

import java.security.MessageDigest

/**
 * Security policy for Orion's no-root Linux runtime.
 * Artifact metadata must be pinned by a reviewed release manifest; never compute a checksum from
 * an untrusted download and then treat that checksum as a trust anchor.
 */
internal object LinuxRuntimePolicy {
    enum class State { NOT_INSTALLED, DOWNLOADING, VERIFYING, EXTRACTING, READY, FAILED }

    data class Artifact(
        val url: String,
        val sha256: String,
        val bytes: Long
    )

    data class Manifest(
        val version: String,
        val rootfsUrl: String,
        val rootfsSha256: String,
        val rootfsBytes: Long,
        /** Separate executable artifact; a rootfs checksum does not authenticate proot. */
        val prootUrl: String = "",
        val prootSha256: String = "",
        val prootBytes: Long = 0L
    ) {
        fun rootfs() = Artifact(rootfsUrl, rootfsSha256, rootfsBytes)
        fun proot() = Artifact(prootUrl, prootSha256, prootBytes)
    }

    const val MAX_ROOTFS_BYTES = 300_000_000L
    const val MAX_PROOT_BYTES = 30_000_000L
    const val MAX_COMMAND = 4000
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val versionPattern = Regex("^[A-Za-z0-9._-]{1,32}$")

    private fun validArtifact(a: Artifact, maxBytes: Long): Boolean =
        a.url.startsWith("https://") &&
            a.url.none { it.isWhitespace() } &&
            sha256Pattern.matches(a.sha256) &&
            a.bytes in 1..maxBytes

    /** Null means both artifacts have pinned HTTPS URLs, byte lengths and independent SHA-256 values. */
    fun validate(m: Manifest): String? = when {
        !versionPattern.matches(m.version) -> "version must be 1-32 letters, digits, dots, dashes or underscores"
        !validArtifact(m.rootfs(), MAX_ROOTFS_BYTES) -> "rootfs URL, checksum or size is invalid"
        !validArtifact(m.proot(), MAX_PROOT_BYTES) -> "proot URL, checksum or size is invalid"
        else -> null
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun checksumMatches(expected: String, bytes: ByteArray): Boolean =
        sha256Pattern.matches(expected) && sha256Hex(bytes) == expected

    fun isReady(installedVersion: String?, m: Manifest): Boolean =
        installedVersion != null && installedVersion == m.version

    /**
     * Arguments are fixed by Orion, not supplied by a model. The only model-controlled value is the
     * bounded shell command, which is passed as one argument to /bin/sh after the execution policy.
     */
    fun prootArgs(prootPath: String, rootfsDir: String, command: String): List<String>? {
        if (command.isBlank() || command.length > MAX_COMMAND || command.contains('\u0000')) return null
        if (!prootPath.startsWith("/") || prootPath.contains("..") ||
            !rootfsDir.startsWith("/") || rootfsDir.contains("..")) return null
        return listOf(
            prootPath, "-r", rootfsDir,
            "-b", "/dev", "-b", "/proc",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "PATH=/usr/local/bin:/usr/bin:/bin", "TERM=dumb", "LANG=C.UTF-8",
            "/bin/sh", "-c", command
        )
    }
}
