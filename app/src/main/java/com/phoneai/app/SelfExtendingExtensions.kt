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
 * Generated source is executed by the selected ExtensionSandbox runtime adapter:
 * "python" uses the restricted data-transform interpreter; "python_full" uses full CPython.
 * The full CPython PRoot guest shares the Android app UID and is not an OS security boundary.
 */
internal object SelfExtendingExtensionPolicy {
    const val MAX_SOURCE_BYTES = 8_000
    const val MAX_FULL_SOURCE_BYTES = 64_000
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
        // Secret scanning applies to every runtime. Full CPython's broader execution
        // permissions do not make hard-coded credentials safe to persist in an extension.
        OrionSecurityPolicy.findEmbeddedSecret(source)?.let { return it.message }
        if (runtime != "python_full" && source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) return "Generated source exceeds the 8,000-byte runtime limit."
        if (runtime == "python_full") {
            if (source.toByteArray(Charsets.UTF_8).size > MAX_FULL_SOURCE_BYTES) {
                return "Full CPython extension source exceeds the " + MAX_FULL_SOURCE_BYTES + "-byte storage limit."
            }
            return null
        }
        if (runtime != "python") return "Supported extension runtimes: python and python_full."
        OrionSecurityPolicy.findEmbeddedSecret(source)?.let { return it.message }
        if (runtime == "python" && forbiddenPython.any { it.containsMatchIn(source) }) {
            return "Source uses a restricted Python operation; request a simpler capability-scoped implementation."
        }
        return null
    }

    fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun mayPromote(
        testPassed: Boolean,
        sourceHash: String,
        testedHash: String,
        userApproved: Boolean,
        autoActivateVerified: Boolean = false,
        runtime: String = ""
    ): Boolean =
        testPassed && sourceHash.isNotBlank() && sourceHash == testedHash &&
            (userApproved || (autoActivateVerified && runtime in setOf("python", "python_full")))
}

/**
 * Autonomous promotion policy for generated extensions after exact-source runtime tests pass.
 * Defaults on to honor the owner's autonomous self-evolution preference; the owner can
 * disable it at any time through the extension_auto_activation tool.
 */
internal object ExtensionAutonomySettings {
    private const val PREFS = "orion_extension_autonomy"
    private const val KEY_AUTO_ACTIVATE = "auto_activate_after_tests"

    fun autoActivationEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_ACTIVATE, true)

    fun setAutoActivationEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_ACTIVATE, enabled).apply()
    }
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
 * Runtime implementations: "python" is the restricted data-transform interpreter;
 * "python_full" is the full CPython workspace runtime and runs with the app's Android UID.
 * PRoot is not an OS security boundary. Android's own app sandbox and permission model remain
 * authoritative for host resources.
 */
internal interface ExtensionSandbox {
    fun test(extension: GeneratedExtension, testCases: List<JSONObject>): ExtensionTestReport
    fun execute(extension: GeneratedExtension, input: JSONObject, grantedCapabilities: Set<String>): JSONObject
}

/** Durable store for drafts and promoted extensions; failed writes are never reported as success. */
internal class SelfExtendingExtensionStore(context: Context) {
    private val lock = Any()
    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, "orion_generated_extensions.json"))

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
        if (OrionSecurityPolicy.findEmbeddedSecret(extension.description) != null) return@synchronized false
        if (SelfExtendingExtensionPolicy.sha256(extension.source) != extension.sourceSha256) return@synchronized false
        val current = try { all() } catch (_: Exception) { return@synchronized false }
        if (current.any { it.id == extension.id }) return@synchronized false
        persist(current + extension.copy(enabled = false, verified = false))
    }

    /** Replace an extension with a new disabled draft; the caller archives the active version first. */
    fun saveRevision(extension: GeneratedExtension): Boolean = synchronized(lock) {
        if (SelfExtendingExtensionPolicy.validateIdentity(extension.id, extension.displayName) != null ||
            SelfExtendingExtensionPolicy.validateSource(extension.runtime, extension.source) != null ||
            OrionSecurityPolicy.findEmbeddedSecret(extension.description) != null ||
            SelfExtendingExtensionPolicy.sha256(extension.source) != extension.sourceSha256) return@synchronized false
        val current = try { all() } catch (_: Exception) { return@synchronized false }
        val previous = current.firstOrNull { it.id == extension.id } ?: return@synchronized false
        if (previous.sourceSha256 == extension.sourceSha256) return@synchronized false
        persist(current.map { if (it.id == extension.id) extension.copy(enabled = false, verified = false) else it })
    }

    /** Promote after hash-bound sandbox tests; verified extension runtimes may auto-activate when the owner setting allows. */
    fun promote(id: String, report: ExtensionTestReport, userApproved: Boolean): Boolean = synchronized(lock) {
        val current = try { all() } catch (_: Exception) { return@synchronized false }
        val target = current.firstOrNull { it.id == id } ?: return@synchronized false
        val allowed = SelfExtendingExtensionPolicy.mayPromote(
            testPassed = report.passed,
            sourceHash = target.sourceSha256,
            testedHash = report.testedSourceSha256,
            userApproved = userApproved,
            autoActivateSafePython = ExtensionAutonomySettings.autoActivationEnabled(appContext),
            runtime = target.runtime
        )
        if (!allowed) return@synchronized false
        persist(current.map { if (it.id == id) it.copy(enabled = true, verified = true) else it })
    }

    /** Activate an archived source only with a passing receipt for that exact source hash. */
    fun activateVersion(version: ExtensionVersionHistory.Version, report: ExtensionTestReport, approved: Boolean): Boolean = synchronized(lock) {
        if (!SelfExtendingExtensionPolicy.mayPromote(
                testPassed = report.passed,
                sourceHash = version.sourceSha256,
                testedHash = report.testedSourceSha256,
                userApproved = approved,
                autoActivateSafePython = ExtensionAutonomySettings.autoActivationEnabled(appContext),
                runtime = version.runtime
            )) return@synchronized false
        if (SelfExtendingExtensionPolicy.validateIdentity(version.id, version.displayName) != null ||
            SelfExtendingExtensionPolicy.validateSource(version.runtime, version.source) != null ||
            SelfExtendingExtensionPolicy.sha256(version.source) != version.sourceSha256) return@synchronized false
        val current = try { all() } catch (_: Exception) { return@synchronized false }
        val restored = version.extension(enabled = true)
        if (current.none { it.id == version.id }) return@synchronized false
        persist(current.map { if (it.id == version.id) restored else it })
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
