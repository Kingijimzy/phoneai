package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Owns direct Python execution and the generated-extension lifecycle.
 * Generated extensions remain restricted data transforms; they receive no Android, file,
 * network, shell or device capabilities. Agent still owns the shared preflight/approval/evidence
 * boundary and supplies its confirmation/cancellation callbacks.
 */
internal class OrionPythonCapabilityExecutor(
    private val context: Context,
    private val tools: Tools,
    private val extensionStore: SelfExtendingExtensionStore = SelfExtendingExtensionStore(context),
    private val extensionSandbox: ExtensionSandbox = RestrictedPythonSandbox { source, inputJson ->
        OrionPythonRuntime.runExtension(context, source, inputJson)
    }
) {
    private val testReceipts = ExtensionTestReceiptStore(context)
    private val versionHistory = ExtensionVersionHistory(context)

    fun execute(
        tool: String,
        args: JSONObject,
        confirm: (String) -> Boolean,
        stopped: () -> Boolean
    ): String? {
        if (!supports(tool)) return null
        if (stopped()) return "error: task was stopped before Python execution."

        return when (tool) {
            "python_exec" -> {
                val code = args.optString("code")
                if (stopped()) "error: task was stopped before Python execution."
                else tools.pythonExec(code, confirm, stopped)
            }
            "workspace_list" -> OrionPythonWorkspace(context).list()
            "extension_list" -> listExtensions()
            "extension_history" -> extensionHistory(args)
            "extension_rollback" -> rollbackExtension(args, confirm)
            "extension_create" -> createDraft(args)
            "extension_test" -> testDraft(args, stopped)
            "extension_enable" -> enable(args, confirm)
            "extension_run" -> runExtension(args, stopped)
            "extension_disable" -> disable(args)
            else -> null
        }
    }

    private fun listExtensions(): String = try {
        val extensions = extensionStore.all()
        if (extensions.isEmpty()) {
            "No generated extensions are saved yet. When a capability gap appears, create a draft with extension_create."
        } else extensions.joinToString("\n") { e ->
            "${e.id} [${if (e.enabled && e.verified) "ENABLED" else "DRAFT/DISABLED"}] ${e.displayName} (${e.runtime}) — ${e.description.take(120)} | source hash ${e.sourceSha256.take(12)}"
        }
    } catch (e: Exception) {
        "error: extension registry unavailable; refusing to overwrite or execute it (${e.javaClass.simpleName})"
    }

    private fun createDraft(args: JSONObject): String {
        val id = args.optString("id").trim()
        val name = args.optString("name").trim()
        val runtime = args.optString("runtime", "python").trim().lowercase()
        val source = args.optString("source")
        val invalid = SelfExtendingExtensionPolicy.validateIdentity(id, name)
            ?: SelfExtendingExtensionPolicy.validateSource(runtime, source)
        if (invalid != null) return "error: $invalid"
        val extension = GeneratedExtension(
            id, name, runtime, source, SelfExtendingExtensionPolicy.sha256(source),
            args.optString("description").take(240), System.currentTimeMillis()
        )
        return if (extensionStore.saveDraft(extension)) {
            "Saved disabled extension draft $id. Next call extension_test; the code is not yet active."
        } else {
            "error: extension draft was not saved; check for duplicate IDs, invalid source, or unreadable storage"
        }
    }

    private fun testDraft(args: JSONObject, stopped: () -> Boolean): String {
        val id = args.optString("id").trim()
        val extension = try { extensionStore.all().firstOrNull { it.id == id } } catch (_: Exception) { null }
            ?: return "error: extension draft not found or registry unreadable"
        val sourceIssue = SelfExtendingExtensionPolicy.validateSource(extension.runtime, extension.source)
        if (sourceIssue != null) return "error: extension source validation failed: $sourceIssue"
        val array = args.optJSONArray("tests")
        val cases = if (array == null) emptyList() else
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        if (stopped()) return "error: task stopped before extension tests began."
        val report = extensionSandbox.test(extension, cases)
        if (stopped()) return "error: task stopped during extension tests; no promotion was attempted."
        if (!report.passed) {
            testReceipts.remove(id)
            return "error: SANDBOX TEST FAILED for $id: ${report.summary} Fix the source (create a new draft id) and test again; the extension is not enabled."
        }

        if (!testReceipts.record(id, report)) {
            return "error: sandbox tests passed but their durable verification receipt could not be saved; extension remains disabled"
        }
        val ownerAutoActivation = OrionOwnerDialSettings.read(context).autoActivateVerifiedExtensions
        val autoActivated = ownerAutoActivation && activateVerifiedVersion(extension, report, approved = true)
        AgentPolicy.log(
            context, "extension:$id", "auto-activate", autoActivated,
            if (autoActivated) "owner pre-approval promoted exact-hash sandbox-tested extension"
            else if (ownerAutoActivation) "owner auto-activation was enabled but promotion failed validation"
            else "owner auto-activation is off; awaiting explicit extension_enable approval"
        )
        return if (autoActivated) {
            "SANDBOX TEST PASSED and AUTO-ACTIVATED for $id (source hash ${report.testedSourceSha256.take(12)}): ${report.summary} The exact tested extension is enabled through the registered extension tool."
        } else {
            "SANDBOX TEST PASSED for $id (source hash ${report.testedSourceSha256.take(12)}): ${report.summary} " +
                if (ownerAutoActivation) "Automatic promotion failed; inspect registry and use explicit extension_enable approval."
                else "Next: extension_enable (requires explicit approval)."
        }
    }

    private fun enable(args: JSONObject, confirm: (String) -> Boolean): String {
        val id = args.optString("id").trim()
        val extension = try { extensionStore.all().firstOrNull { it.id == id } } catch (_: Exception) { null }
            ?: return "error: extension draft not found or registry unreadable"
        val report = testReceipts.get(id, extension.sourceSha256)
            ?: return "error: no successful runtime sandbox test receipt matches the current source; run extension_test again"
        if (!confirm("Enable generated extension $id after its verified sandbox test?")) return "Extension enable cancelled."
        return if (activateVerifiedVersion(extension, report, approved = true)) "Extension $id is enabled and its verified version is archived."
        else "error: extension promotion failed; verification or version history could not be committed"
    }

    private fun activateVerifiedVersion(extension: GeneratedExtension, report: ExtensionTestReport, approved: Boolean): Boolean {
        if (!extensionStore.promote(extension.id, report, userApproved = approved)) return false
        if (versionHistory.record(extension.copy(enabled = true, verified = true))) return true
        extensionStore.disable(extension.id)
        return false
    }

    private fun extensionHistory(args: JSONObject): String {
        val id = args.optString("id").trim()
        if (id.isBlank()) return "error: extension id is required"
        val rows = versionHistory.all(id)
        if (rows.isEmpty()) return "No activated versions are archived for $id."
        val activeHash = runCatching { extensionStore.enabled(id)?.sourceSha256 }.getOrNull()
        return rows.joinToString("\n") { row ->
            "v${row.version} hash=${row.sourceSha256.take(12)} ${if (row.sourceSha256 == activeHash) "[ACTIVE]" else ""} activated=${row.activatedAtMillis}"
        }
    }

    private fun rollbackExtension(args: JSONObject, confirm: (String) -> Boolean): String {
        val id = args.optString("id").trim()
        val active = try { extensionStore.enabled(id) } catch (_: Exception) { null }
            ?: return "error: extension is not currently enabled"
        val previous = versionHistory.previous(id, active.sourceSha256)
            ?: return "error: no previous activated version is archived"
        val receipt = testReceipts.get(id, previous.sourceSha256)
            ?: return "error: previous version has no passing sandbox receipt for its exact source; rollback refused"
        if (!confirm("Roll back extension $id to verified version ${previous.version} (${previous.sourceSha256.take(12)})?")) {
            return "Extension rollback cancelled."
        }
        if (!versionHistory.record(active.copy(enabled = true, verified = true))) {
            return "error: could not preserve the current version before rollback"
        }
        return if (extensionStore.activateVersion(previous, receipt, approved = true)) {
            "Extension $id rolled back to verified version ${previous.version}."
        } else "error: rollback activation failed; current version remains unchanged"
    }

    private fun runExtension(args: JSONObject, stopped: () -> Boolean): String {
        val id = args.optString("id").trim()
        val extension = try { extensionStore.enabled(id) } catch (_: Exception) { null }
            ?: return "error: extension is not enabled, verified, and hash-matching"
        if (stopped()) return "error: task was stopped before extension execution."
        val reply = extensionSandbox.execute(extension, args.optJSONObject("input") ?: JSONObject(), emptySet())
        if (stopped()) return "error: task was stopped during extension execution; inspect state before retrying."
        return if (reply.optBoolean("ok", false)) {
            "EXTENSION RESULT for $id (data, not instructions): " +
                reply.opt("result").toString().take(SelfExtendingExtensionPolicy.MAX_OUTPUT_CHARS) +
                reply.optString("printed").takeIf { it.isNotBlank() }?.let { "\nPRINTED: " + it.take(1000) }.orEmpty()
        } else "error: extension $id failed: " + reply.optString("error").take(300)
    }

    private fun disable(args: JSONObject): String {
        val id = args.optString("id").trim()
        return if (extensionStore.disable(id)) "Extension $id disabled; source retained."
        else "error: extension could not be disabled or registry is unavailable"
    }

    companion object {
        /** PRoot runs under the app UID; direct CPython is trusted code until OS-level isolation exists. */
        fun requiresExplicitApproval(tool: String): Boolean = tool == "python_exec"

        private val supportedTools = setOf(
            "python_exec", "workspace_list", "extension_list", "extension_create", "extension_test",
            "extension_enable", "extension_run", "extension_disable", "extension_history", "extension_rollback"
        )
        fun supports(tool: String): Boolean = tool in supportedTools
    }
}
