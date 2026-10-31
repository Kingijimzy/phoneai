package com.phoneai.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Lifecycle contract for self-extending tools.
 *
 * Generated source is never executed by this class. A platform sandbox must implement
 * ExtensionSandbox and enforce process isolation, no network by default, bounded CPU/time,
 * bounded memory/output, and an explicit capability bridge. A syntax check or denylist is
 * not a security boundary.
 */
internal object SelfExtendingExtensionPolicy {
    const val MAX_SOURCE_BYTES = 32 * 1024
    const val MAX_OUTPUT_CHARS = 8 * 1024
    const val MAX_TESTS = 12
    private val idPattern = Regex("^[a-z][a-z0-9_]{2,47}$")
    private val forbiddenPython = listOf(
        Regex("(?m)^\\s*(import|from)\\s+(os|sys|subprocess|socket|ctypes|multiprocessing|threading|pathlib|shutil)\\b"),
        Regex("\\b(eval|exec|compile|__import__)\\s*\\("),
        Regex("(?m)^\\s*(import|from)\\s+(requests|urllib|http|ftplib|paramiko)\\b"),
        Regex("\\b(open|input)\\s*\\(")
    )

    fun validateIdentity(id: String, displayName: String): String? = when {
        !idPattern.matches(id) -> "Extension id must be a lowercase identifier (3–48 characters)."
        displayName.isBlank() || displayName.length > 80 -> "Extension name must be 1–80 characters."
        else -> null
    }

    fun validateSource(runtime: String, source: String): String? {
        if (source.isBlank()) return "Generated source is empty."
        if (source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) return "Generated source exceeds the 32 KiB limit."
        if (runtime !in setOf("python", "javascript")) return "Only Python and JavaScript extension sources are supported."
        if (runtime == "python" && forbiddenPython.any { it.containsMatchIn(source) }) {
            return "Source uses a restricted Python operation; request a simpler capability-scoped implementation."
        }
        // JS extensions must run in a sandbox without host globals. This catches common
        // host escape attempts early, but the actual runtime still has to enforce isolation.
        if (runtime == "javascript" && Regex("\\b(process|require|globalThis|Deno|Bun)\\b|\\b(eval|Function)\\s*\\(").containsMatchIn(source)) {
            return "Source references a restricted JavaScript host/evaluation feature."
        }
        return null
    }

    fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun mayPromote(testPassed: Boolean, sourceHash: String, testedHash: String, userApproved: Boolean): Boolean =
        testPassed && sourceHash.isNotBlank() && sourceHash == testedHash && userApproved
}

internal data class GeneratedExtension(
    val id: String,
    val displayName: String,
    val runtime: String,
    val source: String,
    val sourceSha256: String,
    val description: String,
    val createdAtMillis: Long,
    val enabled: Boolean = false,
    val verified: Boolean = false
)

internal data class ExtensionTestReport(
    val passed: Boolean,
    val testedSourceSha256: String,
    val summary: String,
    val output: String = ""
)

/**
 * Implementations must run untrusted code in a real isolated sandbox, not the app process.
 * Capabilities are deliberately passed as a narrow list, never as Android Context or shell.
 */
internal interface ExtensionSandbox {
    fun test(extension: GeneratedExtension, testCases: List<JSONObject>): ExtensionTestReport
    fun execute(extension: GeneratedExtension, input: JSONObject, grantedCapabilities: Set<String>): JSONObject
}

/** Durable store for drafts and promoted extensions; failed writes are never reported as success. */
internal class SelfExtendingExtensionStore(context: Context) {
    private val lock = Any()
    private val file = AtomicFile(File(context.filesDir, "orion_generated_extensions.json"))

    fun all(): List<GeneratedExtension> = synchronized(lock) {
        val bytes = try { file.openRead().use { it.readBytes() } } catch (_: java.io.FileNotFoundException) {
            return@synchronized emptyList()
        }
        val root = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (e: Exception) {
            throw IllegalStateException("Generated extension registry is corrupt; refusing to overwrite it.", e)
        }
        val array = root.optJSONArray("extensions") ?: throw IllegalStateException("Generated extension registry has no extensions array.")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            try {
                GeneratedExtension(
                    id = item.getString("id"),
                    displayName = item.getString("displayName"),
                    runtime = item.getString("runtime"),
                    source = item.getString("source"),
                    sourceSha256 = item.getString("sourceSha256"),
                    description = item.optString("description"),
                    createdAtMillis = item.getLong("createdAtMillis"),
                    enabled = item.optBoolean("enabled", false),
                    verified = item.optBoolean("verified", false)
                )
            } catch (_: Exception) { null }
        }
    }

    fun saveDraft(extension: GeneratedExtension): Boolean = synchronized(lock) {
        if (SelfExtendingExtensionPolicy.validateIdentity(extension.id, extension.displayName) != null) return@synchronized false
        if (SelfExtendingExtensionPolicy.validateSource(extension.runtime, extension.source) != null) return@synchronized false
        if (SelfExtendingExtensionPolicy.sha256(extension.source) != extension.sourceSha256) return@synchronized false
        val current = try { all() } catch (_: Exception) { return@synchronized false }
        if (current.any { it.id == extension.id }) return@synchronized false
        persist(current + extension.copy(enabled = false, verified = false))
    }

    /** Promote only after sandbox tests passed for the exact source and the user approves it. */
    fun promote(id: String, report: ExtensionTestReport, userApproved: Boolean): Boolean = synchronized(lock) {
        val current = try { all() } catch (_: Exception) { return@synchronized false }
        val target = current.firstOrNull { it.id == id } ?: return@synchronized false
        val allowed = SelfExtendingExtensionPolicy.mayPromote(
            report.passed, target.sourceSha256, report.testedSourceSha256, userApproved
        )
        if (!allowed) return@synchronized false
        persist(current.map { if (it.id == id) it.copy(enabled = true, verified = true) else it })
    }

    fun disable(id: String): Boolean = synchronized(lock) {
        val current = try { all() } catch (_: Exception) { return@synchronized false }
        if (current.none { it.id == id }) return@synchronized false
        persist(current.map { if (it.id == id) it.copy(enabled = false) else it })
    }

    fun enabled(id: String): GeneratedExtension? = all().firstOrNull { it.id == id && it.enabled && it.verified && it.sourceSha256 == SelfExtendingExtensionPolicy.sha256(it.source) }

    private fun persist(extensions: List<GeneratedExtension>): Boolean {
        val array = JSONArray()
        extensions.forEach { e ->
            array.put(JSONObject().put("id", e.id).put("displayName", e.displayName)
                .put("runtime", e.runtime).put("source", e.source).put("sourceSha256", e.sourceSha256)
                .put("description", e.description).put("createdAtMillis", e.createdAtMillis)
                .put("enabled", e.enabled).put("verified", e.verified))
        }
        val output = JSONObject().put("version", 1).put("extensions", array).toString().toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(output)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) file.failWrite(stream)
            false
        }
    }
}
