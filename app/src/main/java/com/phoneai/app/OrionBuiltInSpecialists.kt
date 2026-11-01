package com.phoneai.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete, read-only-by-default specialist adapters.
 *
 * Coding produces a bounded patch proposal in Orion's private files area. It never writes
 * into a user workspace or executes generated code without a separate owner-approved workflow.
 */
internal class OrionBuiltInSpecialists(private val context: Context) {
    fun workers(): List<OrionTaskWorker> = listOf(
        specialist(OrionSpecialist.RESEARCH, "orion-research") { research(it) },
        specialist(OrionSpecialist.CODING, "orion-coding") { coding(it) },
        specialist(OrionSpecialist.DIAGNOSTICS, "orion-diagnostics") { diagnostics(it) },
        specialist(OrionSpecialist.TRADING_ANALYSIS, "orion-trading") { trading(it) },
        specialist(OrionSpecialist.VERIFICATION, "orion-verification") { verify(it) }
    )

    private fun specialist(role: OrionSpecialist, id: String, run: (OrionTaskEnvelope) -> OrionWorkerResult): OrionTaskWorker =
        OrionSpecialistWorker(id, role, OrionSpecialistHandler(run))

    private fun success(kind: String, task: OrionTaskEnvelope, payload: JSONObject): OrionWorkerResult {
        val body = JSONObject().put("schema", 1).put("task_id", task.taskId)
            .put("kind", kind).put("created_at", System.currentTimeMillis()).put("payload", payload)
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = java.io.File(context.filesDir, "orion_evidence_$digest.json")
        return try {
            file.writeBytes(bytes)
            OrionWorkerResult.Success("evidence:$digest")
        } catch (_: Exception) { OrionWorkerResult.Failure("evidence_persist_failed", true) }
    }

    private fun research(task: OrionTaskEnvelope): OrionWorkerResult {
        val url = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(task.objective)?.value
            ?: return OrionWorkerResult.NeedsOwner("Research requires a specific public HTTP(S) URL. Supply the source URL; free-form web discovery is not silently assumed.")
        val result = BrowserResearch.fetch(url, AtomicBoolean(false))
        val page = result.page ?: return OrionWorkerResult.Failure("research_fetch_failed:" + (result.error ?: "unknown"), true)
        return success("research", task, JSONObject().put("source_url", page.sourceUrl)
            .put("title", page.title).put("content_type", page.contentType).put("text", page.text.take(8_000)))
    }

    private fun trading(task: OrionTaskEnvelope): OrionWorkerResult {
        if (!AgentPolicy.enabled(context, "trading_research"))
            return OrionWorkerResult.NeedsOwner(AgentPolicy.packOffMessage("trading_research"))
        val setups = runCatching { Scanner.scan(context) }.getOrElse {
            return OrionWorkerResult.Failure("trading_scan_failed", true)
        }
        val rows = org.json.JSONArray()
        setups.take(30).forEach { s ->
            rows.put(JSONObject().put("symbol", s.symbol).put("type", s.type)
                .put("timeframe", s.tf).put("score", s.score).put("side", s.side).put("entry", s.entry).put("stop", s.stop).put("target", s.target).put("note", s.note).put("rr", s.rr()))
        }
        return success("trading_analysis", task, JSONObject().put("watchlist", Prefs.watchlist(context))
            .put("setups", rows).put("note", "Read-only scan. No orders or account mutations were submitted."))
    }

    private fun diagnostics(task: OrionTaskEnvelope): OrionWorkerResult {
        val snapshot = runCatching { OrionCapabilityHub.snapshot(context) }.getOrElse {
            return OrionWorkerResult.Failure("diagnostics_snapshot_failed", true)
        }
        val caps = org.json.JSONArray()
        snapshot.capabilities.forEach { cap ->
            caps.put(JSONObject().put("name", cap.name).put("state", OrionCapabilityHub.stateLabel(cap.state))
                .put("detail", cap.detail.take(500)).put("tools", org.json.JSONArray(cap.tools)))
        }
        val limits = org.json.JSONArray()
        snapshot.limitations.forEach { limits.put(it.take(300)) }
        return success("diagnostics", task, JSONObject().put("checked_at", snapshot.checkedAt)
            .put("registered_tools", snapshot.registeredTools).put("available_tools", snapshot.availableTools)
            .put("capabilities", caps).put("limitations", limits))
    }

    private fun verify(task: OrionTaskEnvelope): OrionWorkerResult {
        if (task.inputEvidenceRefs.isEmpty())
            return OrionWorkerResult.NeedsOwner("Verification needs one or more evidence references to inspect.")
        val rows = org.json.JSONArray()
        var allValid = true
        task.inputEvidenceRefs.forEach { ref ->
            val digest = ref.removePrefix("evidence:")
            val file = java.io.File(context.filesDir, "orion_evidence_$digest.json")
            val valid = digest.matches(Regex("^[a-f0-9]{64}$")) && file.isFile &&
                runCatching {
                    val bytes = file.readBytes()
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == digest &&
                        JSONObject(String(bytes, Charsets.UTF_8)).optString("task_id").isNotBlank()
                }.getOrDefault(false)
            allValid = allValid && valid
            rows.put(JSONObject().put("ref", ref).put("valid", valid))
        }
        return success("verification", task, JSONObject().put("all_valid", allValid)
            .put("checked", rows).put("rule", "Evidence integrity and existence only; semantic correctness still requires domain review."))
    }

    private fun coding(task: OrionTaskEnvelope): OrionWorkerResult {
        if (!AgentPolicy.enabled(context, "workspace_shell"))
            return OrionWorkerResult.NeedsOwner(AgentPolicy.packOffMessage("workspace_shell"))
        val uri = context.getSharedPreferences("phoneai_workspace", Context.MODE_PRIVATE)
            .getString("tree_uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return OrionWorkerResult.NeedsOwner("Select a workspace folder in Orion Workspace Terminal before requesting a coding patch.")
        val match = Regex("(?i)\\b(?:read|inspect|review)\\s+([A-Za-z0-9_.-]{1,120})").find(task.objective)
        val fileName = match?.groupValues?.get(1)
            ?: return OrionWorkerResult.NeedsOwner("Coding adapter accepts an explicit workspace filename: 'review filename.ext' or 'inspect filename.ext'.")
        return try {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)
            var found: Uri? = null
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == fileName && cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) {
                        found = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)); break
                    }
                }
            }
            val target = found ?: return OrionWorkerResult.Failure("workspace_file_not_found", false)
            val source = context.contentResolver.openInputStream(target)?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(80_000) }
                ?: return OrionWorkerResult.Failure("workspace_read_failed", true)
            val report = JSONObject().put("workspace_uri", uri.toString()).put("file", fileName)
                .put("source_sha256", MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) })
                .put("source_excerpt", source.take(12_000))
                .put("instruction", task.objective.take(1_000))
                .put("status", "source_captured_patch_not_applied")
                .put("safety", "No generated code executed; no workspace file modified. Return a proposed diff for owner review.")
            success("coding_review", task, report)
        } catch (_: SecurityException) {
            OrionWorkerResult.NeedsOwner("Android has not granted Orion access to the selected workspace folder. Re-select it in Workspace Terminal.")
        } catch (_: Exception) { OrionWorkerResult.Failure("workspace_coding_read_failed", true) }
    }
}
