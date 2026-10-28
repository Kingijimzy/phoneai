package com.phoneai.app

import java.security.MessageDigest

/** Pinned release inputs for Orion's no-root ARM64 Alpine runtime. */
internal object LinuxRuntimePolicy {
    enum class State { NOT_INSTALLED, DOWNLOADING, VERIFYING, EXTRACTING, READY, FAILED }
    data class Artifact(val url: String, val sha256: String, val bytes: Long)
    data class Manifest(
        val version: String,
        val rootfsUrl: String,
        val rootfsSha256: String,
        val rootfsBytes: Long,
        /** Hash of APK-packaged lib/arm64-v8a/libproot.so (the Android PIE executable). */
        val prootSha256: String,
        val prootBytes: Long
    ) {
        fun rootfs() = Artifact(rootfsUrl, rootfsSha256, rootfsBytes)
    }

    val productionManifest = Manifest(
        version = "alpine-3.22.7-proot-1a71df0-recovery2",
        rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.7-aarch64.tar.gz",
        rootfsSha256 = "7e9b34ff3304381909e566e2c93de450664ccc724191261423974fc121c11623",
        rootfsBytes = 3971679L,
        prootSha256 = "91c3f3ce38482fba523c0dcbfa5eb883e8aae063236bff3d091da6a58f1c94c4",
        prootBytes = 294416L
    )
    // SHA-256 pinned in .github/workflows/build.yml for the APK-packaged Android W^X loader.
    const val PROOT_LOADER_SHA256 = "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04"
    const val MAX_ROOTFS_BYTES = 300_000_000L
    const val MAX_COMMAND = 4000
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val versionPattern = Regex("^[A-Za-z0-9._-]{1,48}$")

    private fun validArtifact(a: Artifact, maxBytes: Long): Boolean =
        a.url.startsWith("https://") && a.url.none { it.isWhitespace() } &&
            sha256Pattern.matches(a.sha256) && a.bytes in 1..maxBytes

    fun validate(m: Manifest): String? = when {
        !versionPattern.matches(m.version) -> "invalid runtime version"
        !validArtifact(m.rootfs(), MAX_ROOTFS_BYTES) -> "rootfs URL, checksum or size is invalid"
        !sha256Pattern.matches(m.prootSha256) || m.prootBytes !in 1..30_000_000L ->
            "bundled PRoot checksum or size is invalid"
        else -> null
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun checksumMatches(expected: String, bytes: ByteArray): Boolean =
        sha256Pattern.matches(expected) && sha256Hex(bytes) == expected

    fun isReady(installedVersion: String?, m: Manifest): Boolean =
        installedVersion != null && installedVersion == m.version

    /** Fixed argv shape; model text is passed as one /bin/sh -c argument, never host-shell interpolation. */
    fun prootArgs(prootPath: String, rootfsDir: String, command: String): List<String>? {
        if (command.isBlank() || command.length > MAX_COMMAND || command.contains('\u0000')) return null
        if (!prootPath.startsWith("/") || prootPath.contains("..") ||
            !rootfsDir.startsWith("/") || rootfsDir.contains("..")) return null
        return listOf(
            prootPath, "-r", rootfsDir,
            "-b", "/dev", "-b", "/proc",
            "-w", "/root",
            "/bin/sh", "-c", command
        )
    }
}
