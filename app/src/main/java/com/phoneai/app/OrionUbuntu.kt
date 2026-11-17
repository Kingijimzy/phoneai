package com.phoneai.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ubuntu 24.04 (arm64) runtime for Orion's coding space, the same userspace-PRoot approach as the
 * DeepSeek harness. The official ubuntu-base tarball is pinned at install time from Canonical's
 * published SHA256SUMS (fetched over TLS), then verified byte-for-byte before extraction.
 */
internal object OrionUbuntu {
    private const val BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
    private val tarName = Regex("^ubuntu-base-24\\.04(?:\\.\\d+)?-base-arm64\\.tar\\.gz$")

    /** Parses `<sha256> *<file>` lines and returns the newest matching arm64 base tarball. */
    fun pick(sums: String): Pair<String, String>? = sums.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), 2)
        if (parts.size != 2) return@mapNotNull null
        val name = parts[1].removePrefix("*")
        val sha = parts[0].lowercase()
        if (tarName.matches(name) && Regex("^[0-9a-f]{64}$").matches(sha)) name to sha else null
    }.maxByOrNull { it.first }

    fun manifestFor(name: String, sha: String, bytes: Long): LinuxRuntimePolicy.Manifest {
        val prod = LinuxRuntimePolicy.productionManifest
        return LinuxRuntimePolicy.Manifest(
            version = "ubuntu-" + name.removePrefix("ubuntu-base-").substringBefore("-base") + "-" + sha.take(8) + "-p1",
            rootfsUrl = BASE + name, rootfsSha256 = sha, rootfsBytes = bytes,
            prootSha256 = prod.prootSha256, prootBytes = prod.prootBytes
        )
    }

    private fun cache(c: Context) = File(c.applicationContext.filesDir, "orion-ubuntu-manifest.txt")

    private fun fromCache(c: Context): LinuxRuntimePolicy.Manifest? = runCatching {
        val l = cache(c).readLines()
        manifestFor(l[0], l[1], l[2].toLong()).takeIf { LinuxRuntimePolicy.validate(it) == null }
    }.getOrNull()

    private fun get(url: String, head: Boolean = false): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
            if (head) requestMethod = "HEAD"
        }
        return try {
            if (head) conn.responseCode to (conn.contentLengthLong.toString())
            else conn.responseCode to conn.inputStream.bufferedReader().use { it.readText().take(200_000) }
        } finally { conn.disconnect() }
    }

    /** The pinned manifest: cached after the first successful lookup so readiness checks work offline. */
    fun manifest(c: Context, refresh: Boolean = false): Result<LinuxRuntimePolicy.Manifest> {
        if (!refresh) fromCache(c)?.let { return Result.success(it) }
        return runCatching {
            val (code, body) = get(BASE + "SHA256SUMS")
            check(code in 200..299) { "Ubuntu checksum list unavailable (HTTP $code)" }
            val (name, sha) = pick(body) ?: error("No Ubuntu 24.04 arm64 base image listed by Canonical.")
            val (hc, len) = get(BASE + name, head = true)
            val bytes = len.toLongOrNull() ?: 0L
            check(hc in 200..299 && bytes > 0) { "Ubuntu image size unavailable (HTTP $hc)" }
            val m = manifestFor(name, sha, bytes)
            LinuxRuntimePolicy.validate(m)?.let { error(it) }
            cache(c).writeText("$name\n$sha\n$bytes\n")
            m
        }.recoverCatching { if (!refresh) throw it else fromCache(c) ?: throw it }
    }

    fun isInstalled(c: Context): Boolean {
        val m = fromCache(c) ?: return false
        return LinuxRuntimeInstaller(c, LinuxRuntimeInstaller.Flavor.UBUNTU).isReady(m)
    }

    /** Backend name for coding-space commands: Ubuntu once it is installed, otherwise the Alpine runtime. */
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("orion_runtime", 0)

    /** "auto" (Ubuntu when installed), "ubuntu" or "linux" (Alpine). Ubuntu is the primary environment. */
    fun choice(c: Context): String = prefs(c).getString("choice", "auto").orEmpty().ifBlank { "auto" }
    fun setChoice(c: Context, value: String): Boolean {
        if (value !in setOf("auto", "ubuntu", "linux")) return false
        prefs(c).edit().putString("choice", value).apply(); return true
    }

    fun where(c: Context): String = when (choice(c)) {
        "linux" -> "linux"
        else -> if (isInstalled(c)) "ubuntu" else "linux"
    }

    fun label(c: Context): String = if (where(c) == "ubuntu") "Ubuntu 24.04" else "Alpine"

    /** Package profiles mapped to Ubuntu package names. */
    val profiles: Map<String, List<String>> = mapOf(
        "base" to listOf("python3", "python3-pip", "python3-venv", "nodejs", "npm", "git", "bash", "curl", "wget", "jq", "build-essential", "openssh-client", "sqlite3", "rsync", "zip", "unzip", "tar", "xz-utils", "ca-certificates", "nano", "less", "procps"),
        "web" to listOf("nginx", "php-cli", "golang-go"),
        "data" to listOf("sqlite3", "postgresql-client", "gnuplot", "p7zip-full"),
        "media" to listOf("ffmpeg", "imagemagick", "libimage-exiftool-perl"),
        "db" to listOf("sqlite3", "postgresql", "redis-server"),
        "net" to listOf("nmap", "dnsutils", "iproute2", "socat", "tcpdump", "mosquitto-clients")
    )

    fun setupScript(profile: String): String? {
        val names = if (profile == "all") profiles.values.flatten().distinct() else profiles[profile] ?: return null
        return OrionStudio.APT_HEAL + "; apt-get update 2>&1 | grep -E '^(Get:|Fetched|E:)'; " +
            "apt-get install -y --no-install-recommends " + names.joinToString(" ") + " 2>&1; echo SETUP_DONE"
    }

    /** Installs (or repairs) the Ubuntu runtime; returns a short human-readable result. */
    fun install(c: Context, onProgress: (String) -> Unit = {}): String {
        val m = manifest(c, refresh = true).getOrElse { return "error: could not look up Ubuntu: ${it.message}" }
        val installer = LinuxRuntimeInstaller(c, LinuxRuntimeInstaller.Flavor.UBUNTU) { name, cur, total ->
            OrionProgress.bytesProgress(name, cur, total)
            onProgress(if (total > 0) "$name ${cur * 100 / total}%" else name)
        }
        if (installer.isReady(m)) return "Ubuntu ${m.version} is already installed."
        val r = installer.install(m)
        return if (r.isSuccess && installer.isReady(m)) "Ubuntu ready: ${m.version}. Run devspace setup to install the toolchain."
        else "error: Ubuntu install failed: ${r.exceptionOrNull()?.message ?: "readiness checks failed"}. Alpine remains available."
    }
}
