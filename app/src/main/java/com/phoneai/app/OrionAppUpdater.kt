package com.phoneai.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update Orion from inside Orion: find the newest CI-built release, download the APK, hand it to the
 * system installer. No browser, no GitHub page. Android itself still asks the owner to confirm an install.
 */
internal object OrionAppUpdater {
    private const val REPO = "Kingijimzy/phoneai"
    private const val PREFS = "orion_updater"

    data class Release(val tag: String, val name: String, val apkUrl: String, val apiAssetUrl: String, val sizeBytes: Long)

    /** Pure: pick the APK asset out of a releases/latest payload. */
    internal fun parseRelease(json: String): Release? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = o.optString("tag_name").ifBlank { return null }
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true))
                return Release(tag, o.optString("name"), a.optString("browser_download_url"), a.optString("url"), a.optLong("size"))
        }
        return null
    }

    /** Pure: numeric build order from tags like build-123; unknown formats never claim to be newer. */
    internal fun isNewer(latestTag: String, installedTag: String?): Boolean {
        val l = Regex("(\\d+)$").find(latestTag)?.value?.toLongOrNull() ?: return false
        val i = installedTag?.let { Regex("(\\d+)$").find(it)?.value?.toLongOrNull() } ?: return true
        return l > i
    }

    internal fun safeApk(r: Release) = r.size() in 1_000_000..400_000_000 && (r.apkUrl.startsWith("https://") || r.apiAssetUrl.startsWith("https://api.github.com/"))
    private fun Release.size() = sizeBytes

    private fun token(c: Context) = runCatching { OrionConnectionStore.tokenForConnector(c, "github") }.getOrDefault("")
    /** Build number baked into the APK by CI (versionName 1.0.N, the same N as the release tag build-N). */
    internal fun tagFromVersionName(versionName: String?): String? =
        Regex("^\\d+\\.\\d+\\.(\\d+)$").find(versionName.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { "build-$it" }

    fun installedTag(c: Context): String? {
        val baked = runCatching { tagFromVersionName(c.packageManager.getPackageInfo(c.packageName, 0).versionName) }.getOrNull()
        return baked ?: c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tag", null)
    }

    private fun getJson(c: Context, path: String): Pair<Int, String> = try {
        val conn = (URL("https://api.github.com$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "Orion-Android")
            token(c).takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        val code = conn.responseCode
        code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
    } catch (e: Exception) { 0 to ("could not reach GitHub (" + e.message + ")").take(160) }

    fun check(c: Context): String {
        val (code, text) = getJson(c, "/repos/$REPO/releases/latest")
        if (code !in 200..299) return "update check failed: " + text.take(160)
        val r = parseRelease(text) ?: return "update check: the latest release has no APK yet (CI may still be building)."
        val have = installedTag(c)
        return if (isNewer(r.tag, have)) "UPDATE AVAILABLE: ${r.tag} (${r.sizeBytes / 1_000_000} MB). Installed: ${have ?: "unknown"}. Call self_edit {action:\"update_install\"} to download and install."
        else "Orion is up to date (${r.tag})."
    }

    private fun download(c: Context, r: Release, dest: File): String? {
        // Private repos need the API asset route; GitHub answers with a redirect to a signed URL that must not receive our token.
        var url = if (token(c).isNotBlank() && r.apiAssetUrl.isNotBlank()) r.apiAssetUrl else r.apkUrl
        var authed = url == r.apiAssetUrl
        repeat(5) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "Orion-Android")
                if (authed) { setRequestProperty("Authorization", "Bearer " + token(c)); setRequestProperty("Accept", "application/octet-stream") }
            }
            val code = conn.responseCode
            if (code in 300..399) { url = conn.getHeaderField("Location") ?: return "redirect without a location"; authed = false; return@repeat }
            if (code !in 200..299) return "download failed: HTTP $code"
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            return if (dest.length() >= 1_000_000) null else "download looks truncated (${dest.length()} bytes)"
        }
        return "too many redirects"
    }

    fun install(c: Context): String {
        val (code, text) = getJson(c, "/repos/$REPO/releases/latest")
        if (code !in 200..299) return "update failed: " + text.take(160)
        val r = parseRelease(text) ?: return "update failed: no APK in the latest release."
        if (!safeApk(r)) return "update refused: the release asset looks wrong (size ${r.sizeBytes})."
        if (!isNewer(r.tag, installedTag(c))) return "Already on ${r.tag}; nothing to install."
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Android needs a one-time permission: allow Orion to install apps (opened the setting), then ask me to update again."
        }
        val file = File(c.cacheDir, "update/orion-${r.tag}.apk")
        download(c, r, file)?.let { return "update failed: $it" }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", file)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tag", r.tag).apply()
        return "Downloaded ${r.tag} (${file.length() / 1_000_000} MB) and opened the installer. Confirm the install prompt to finish."
    }
}
