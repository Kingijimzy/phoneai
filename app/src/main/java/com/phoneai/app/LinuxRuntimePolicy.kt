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
        // Bump the install epoch so devices discard the rootfs installed with the broken PRoot pin.
        version = "alpine-3.22.7-proot-1a71df0-recovery4",
        rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.7-aarch64.tar.gz",
        rootfsSha256 = "7e9b34ff3304381909e566e2c93de450664ccc724191261423974fc121c11623",
        rootfsBytes = 3971679L,
        // Must match the exact libproot.so produced and verified by .github/workflows/build.yml.
        prootSha256 = "22c0959bd03feedc20d98335681166992eace5fd4c369971975b4a116b351b50",
        prootBytes = 294416L
    )
    // SHA-256 pinned in .github/workflows/build.yml for the APK-packaged Android W^X loader.
    const val PROOT_LOADER_SHA256 = "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04"
    const val MAX_ROOTFS_BYTES = 300_000_000L
    const val MAX_COMMAND = 128_000
    const val MIN_PROCESS_TIMEOUT_MS = 250L
    const val MAX_PROCESS_TIMEOUT_MS = 1_800_000L

    /**
     * Normalize model/worker supplied timeouts at the process boundary.
     * Values below the platform minimum become the minimum; long-running profiles are
     * bounded by the PRoot supervisor's hard 30-minute ceiling. Keep this normalization
     * centralized so model adapters, task resumes and direct shell callers cannot disagree.
     */
    fun normalizeProcessTimeout(requestedMs: Long): Long =
        requestedMs.coerceIn(MIN_PROCESS_TIMEOUT_MS, MAX_PROCESS_TIMEOUT_MS)
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
    fun prootArgs(
        prootPath: String,
        rootfsDir: String,
        command: String,
        workspaceHostPath: String? = null,
        androidAppDataPath: String? = null,
        sharedStoragePath: String? = null
    ): List<String>? {
        if (command.isBlank() || command.length > MAX_COMMAND || command.contains('\u0000')) return null
        if (!prootPath.startsWith("/") || prootPath.contains("..") ||
            !rootfsDir.startsWith("/") || rootfsDir.contains("..")) return null
        if (workspaceHostPath != null && (!workspaceHostPath.startsWith("/") || workspaceHostPath.contains("..") || workspaceHostPath.contains(':') || workspaceHostPath.contains('\u0000'))) return null
        val hostPaths = listOfNotNull(androidAppDataPath, sharedStoragePath)
        if (hostPaths.any { !it.startsWith("/") || it.contains("..") || it.contains(':') || it.contains('\u0000') }) return null
        return buildList {
            // PRoot -0 maps the guest process to Alpine root without granting Android host-root access.
            // The previous launcher inherited Android app UID 10390, which cannot write to
            // root-owned guest paths such as /root/.api-anything even though HOME was /root.
            addAll(listOf(prootPath, "-0", "-r", rootfsDir, "-b", "/dev", "-b", "/proc"))
            // Expose only host paths that the Android app UID can already access. Android's
            // permission model and SELinux continue to enforce every host read/write.
            if (androidAppDataPath != null) addAll(listOf("-b", "$androidAppDataPath:/android-app"))
            if (sharedStoragePath != null) addAll(listOf("-b", "$sharedStoragePath:/sdcard"))
            if (workspaceHostPath != null) addAll(listOf("-b", "$workspaceHostPath:/workspace"))
            addAll(listOf("-w", "/root", "/bin/sh", "-c", command))
        }
    }
}