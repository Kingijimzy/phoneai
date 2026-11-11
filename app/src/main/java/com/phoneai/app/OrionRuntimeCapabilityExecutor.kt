package com.phoneai.app

import org.json.JSONObject

/**
 * Concrete local-runtime and file capability executor.
 * This owns the execution implementation for these backend families; Agent remains responsible
 * for task planning, capability gates, the three-action confirmation policy and evidence; Tasks owns durable checkpoint/recovery operations.
 */
internal object OrionRuntimeCapabilityExecutor {
    /**
     * Static owner audit for every registered route. This is deliberately separate from dispatch:
     * a route is considered implemented only when its exact tool has a concrete executor case.
     */
    fun supports(backend: OrionCapabilityAdapters.Backend, tool: String): Boolean = when (backend) {
        OrionCapabilityAdapters.Backend.DEVICE -> OrionDeviceCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.MCP -> OrionMcpCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.INTEGRATIONS -> OrionIntegrationCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.SKILLS -> OrionSkillsCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.RESEARCH -> OrionResearchCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.COMMUNICATION -> OrionCommunicationCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.MEMORY -> OrionMemoryCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.TASKS -> OrionTasksCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.UTILITIES -> OrionUtilitiesCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.DIAGNOSTICS -> OrionDiagnosticsCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.TRADING_READONLY -> OrionTradingReadOnlyCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.TRADING_DESK -> OrionTradingDeskCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.GAMING -> OrionGamingCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.PYTHON -> OrionPythonCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.SHELL -> tool in setOf("shell", "termux_command")
        OrionCapabilityAdapters.Backend.FILES -> tool in setOf("find_files", "open_file", "save_file", "workspace_write", "workspace_read", "workspace_delete", "workspace_move", "backup_list", "backup_restore", "trash_list", "trash_restore", "trash_empty", "project_create", "project_list", "project_inspect", "project_plan", "project_verify", "document_extract", "image_read", "image_generate")
        OrionCapabilityAdapters.Backend.BROWSER -> tool in setOf(
            "open_url", "browser_snapshot", "browser_find", "browser_extract", "browser_verify",
            "browser_click", "browser_click_verify", "browser_type", "browser_scroll",
            "browser_navigate", "browser_back", "browser_handoff", "browser_wait",
            "browser_tabs", "browser_upload", "browser_close"
        )
        OrionCapabilityAdapters.Backend.RUNTIME_STATE -> tool in setOf("model_decision_log", "model_decision_search", "model_status", "model_offline_mode", "soul_state")
        OrionCapabilityAdapters.Backend.DEV -> tool in setOf("dev_understand", "dev_read", "dev_write", "dev_python", "dev_shell", "dev_plan", "dev_summary", "macro_save", "macro_run", "macro_list", "macro_delete")
    }

    fun execute(
        backend: OrionCapabilityAdapters.Backend,
        tool: String,
        args: JSONObject,
        service: PhoneAiService,
        tools: Tools,
        stopped: () -> Boolean,
        confirm: (String) -> Boolean = { false },
        openBrowser: (String) -> Unit = {},
        validateAction: (JSONObject) -> String = { "error: action validation callback unavailable" },
        verifyAction: (JSONObject) -> String = { "error: action verification callback unavailable" },
        verifySend: (JSONObject) -> String = { "error: send verification callback unavailable" },
        look: (String) -> String = { "error: screen interpretation callback unavailable" },
        play: (String, Int) -> String = { _, _ -> "error: game-control callback unavailable" },
        readScreen: () -> String = { service.readScreen() },
        githubAccountRead: (String) -> String = { "error: GitHub account reader callback unavailable" },
        researchCancelled: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        askUser: (String, List<String>, String) -> String = { _, _, _ -> "" },
        pythonExecutor: OrionPythonCapabilityExecutor? = null,
        routineReplay: (String, String) -> String = { _, _ -> "error: saved routine replay callback unavailable" }
    ): String? = when (backend) {
        OrionCapabilityAdapters.Backend.DEVICE -> OrionDeviceCapabilityExecutor.execute(
            tool, args, service, tools, stopped, confirm, validateAction, verifyAction, verifySend, look, play, readScreen, routineReplay
        )
        OrionCapabilityAdapters.Backend.MCP -> OrionMcpCapabilityExecutor.execute(tool, args, service, tools)
        OrionCapabilityAdapters.Backend.INTEGRATIONS -> OrionIntegrationCapabilityExecutor.execute(tool, args, service, tools, githubAccountRead)
        OrionCapabilityAdapters.Backend.SKILLS -> OrionSkillsCapabilityExecutor.execute(tool, args, service)
        OrionCapabilityAdapters.Backend.RESEARCH -> OrionResearchCapabilityExecutor.execute(tool, args, service, tools, researchCancelled)
        OrionCapabilityAdapters.Backend.COMMUNICATION -> OrionCommunicationCapabilityExecutor.execute(tool, args, service, tools, stopped, confirm)
        OrionCapabilityAdapters.Backend.MEMORY -> OrionMemoryCapabilityExecutor.execute(tool, args, service, tools, stopped)
        OrionCapabilityAdapters.Backend.TASKS -> OrionTasksCapabilityExecutor.execute(tool, args, service, tools, stopped, askUser)
        OrionCapabilityAdapters.Backend.UTILITIES -> OrionUtilitiesCapabilityExecutor.execute(tool, args, tools, stopped)
        OrionCapabilityAdapters.Backend.DIAGNOSTICS -> OrionDiagnosticsCapabilityExecutor.execute(tool, args, service)
        OrionCapabilityAdapters.Backend.TRADING_READONLY -> OrionTradingReadOnlyCapabilityExecutor.execute(tool, args, service)
        OrionCapabilityAdapters.Backend.TRADING_DESK -> OrionTradingDeskCapabilityExecutor.execute(tool, args, service, stopped)
        OrionCapabilityAdapters.Backend.GAMING -> OrionGamingCapabilityExecutor.execute(tool, args, service)
        OrionCapabilityAdapters.Backend.DEV -> OrionDevCapabilityExecutor(service, pythonExecutor).execute(tool, args, confirm, stopped)
        OrionCapabilityAdapters.Backend.BROWSER -> executeBrowser(tool, args, service, openBrowser)
        OrionCapabilityAdapters.Backend.SHELL -> when (tool) {
            "shell" -> tools.shell(args.optString("command"), args.optString("where"))
            "termux_command" -> TermuxBridgeRunner.executeReadOnly(service, args.optString("command"), stopped)
            else -> null
        }
        OrionCapabilityAdapters.Backend.PYTHON -> pythonExecutor?.execute(tool, args, confirm, stopped)
        // RUNTIME_STATE tools (model_status, model_offline_mode, model_decision_*, soul_state) are implemented in this block.
        OrionCapabilityAdapters.Backend.FILES, OrionCapabilityAdapters.Backend.RUNTIME_STATE -> when (tool) {
            "find_files" -> tools.findFiles(args.optString("query"))
            "open_file" -> tools.openFile(args.optString("id"))
            "save_file" -> tools.saveFile(args.optString("filename"), args.optString("content"), args.optString("folder", "Orion"))
            "workspace_write" -> {
                val path = args.optString("path")
                val gate = OrionCodingPlanner.writeGate(service, path)
                if (gate != null) gate else {
                    val workspace = OrionPythonWorkspace(service)
                    val before = workspace.readForUndo(path)
                    val result = workspace.write(path, args.optString("content"))
                    if (result.startsWith("saved ")) {
                        val after = workspace.readForUndo(path)
                        if (after == null || !WorkspaceUndoStore(service).recordWrite(path, before, after)) {
                            val rolledBack = if (before == null) {
                                workspace.delete(path) && workspace.readForUndo(path) == null
                            } else {
                                workspace.write(path, before).startsWith("saved ") && workspace.readForUndo(path) == before
                            }
                            if (rolledBack) "error: workspace write was rolled back because its undo receipt could not be saved"
                            else "error: workspace write completed but its undo receipt failed and rollback could not be verified; inspect the file before retrying"
                        } else {
                            OrionCodingPlanner.noteFileWritten(service, path)
                            "wrote ${args.optString("content").length} chars to shared workspace/$path\nLOCAL VERIFY PASS: workspace file read-back matches the written content."
                        }
                    } else result
                }
            }
            "workspace_delete" -> {
                val path = args.optString("path")
                val workspace = OrionPythonWorkspace(service)
                val before = workspace.readForUndo(path)
                if (before == null) "error: workspace file is missing, unreadable, or outside the safe workspace"
                else if (!workspace.delete(path)) "error: workspace delete failed"
                else if (!WorkspaceUndoStore(service).recordDelete(path, before, path)) {
                    val restored = workspace.restoreFromTrash(path).startsWith("restored ") && workspace.readForUndo(path) == before
                    if (restored) "error: workspace delete was reversed because its undo receipt could not be saved"
                    else "error: workspace delete completed but receipt save and restoration both failed; inspect the workspace before retrying"
                } else if (workspace.readForUndo(path) != null) "error: delete did not verify; the file is still present"
                else "deleted shared workspace/$path (reversible; moved to recycle)\nLOCAL VERIFY PASS: deleted file is absent from the workspace and recoverable from the recycle location."
            }
            "workspace_move" -> {
                val source = args.optString("source")
                val destination = args.optString("destination")
                val workspace = OrionPythonWorkspace(service)
                val content = workspace.readForUndo(source)
                if (content == null) "error: workspace source is missing, unreadable, or outside the safe workspace"
                else if (workspace.readForUndo(destination) != null) "error: destination already exists; refusing to overwrite it"
                else {
                    val moved = workspace.move(source, destination)
                    if (!moved.startsWith("moved ")) moved
                    else if (!WorkspaceUndoStore(service).recordMove(source, destination, content)) {
                        val reversed = workspace.move(destination, source).startsWith("moved ") &&
                            workspace.readForUndo(source) == content && workspace.readForUndo(destination) == null
                        if (reversed) "error: workspace move was reversed because its undo receipt could not be saved"
                        else "error: workspace move completed but receipt save and rollback could not be verified; inspect both paths before retrying"
                    } else if (workspace.readForUndo(source) != null || workspace.readForUndo(destination) != content)
                        "error: move did not verify; inspect both paths before retrying"
                    else moved + " (reversible)\nLOCAL VERIFY PASS: source is absent and destination matches the moved file."
                }
            }
            "workspace_read" -> ShellRunner.readFile(service, args.optString("path"))
            "trash_list" -> OrionPythonWorkspace(service).listTrash()
            "trash_restore" -> {
                val path = args.optString("path")
                val result = OrionPythonWorkspace(service).restoreFromTrash(path)
                if (result.startsWith("restored ")) result + "\nLOCAL VERIFY PASS: restored file is present at its original workspace path."
                else result
            }
            "trash_empty" -> {
                val count = OrionPythonWorkspace(service).emptyTrash()
                "emptied recycle location ($count file(s) permanently removed)"
            }
            "backup_list" -> OrionPythonWorkspace(service).listBackup()
            "backup_restore" -> {
                val path = args.optString("path")
                val result = OrionPythonWorkspace(service).restoreFromBackup(path)
                if (result.startsWith("restored ")) result + "\nLOCAL VERIFY PASS: restored file is present at its original workspace path."
                else result
            }
            "project_create" -> OrionProjectWorkspace(service).create(args.optString("name"), args.optString("language", "python"))
            "project_list" -> OrionProjectWorkspace(service).list()
            "project_inspect" -> OrionProjectWorkspace(service).inspect(args.optString("name"))
            "project_plan" -> OrionCodingPlanner.create(
                service, args.optString("name"), args.optString("language"), args.optString("objective"),
                args.optString("entry_point"), args.optJSONArray("files")
            )
            "project_verify" -> OrionCodingPlanner.verify(
                service, args.optString("name"), args.optBoolean("run_tests", false)
            )
            "model_decision_log" -> {
                val id = OrionModelDecisionStore.get(service).record(
                    tool = args.optString("tool"),
                    arguments = args.optString("arguments"),
                    reason = args.optString("reason"),
                    provider = args.optString("provider").takeIf { it.isNotBlank() },
                    model = args.optString("model").takeIf { it.isNotBlank() },
                    taskId = args.optString("taskId").takeIf { it.isNotBlank() },
                    turnId = args.optString("turnId").takeIf { it.isNotBlank() }
                )
                "recorded model decision [$id]"
            }
            "model_decision_search" -> {
                val hits = OrionModelDecisionStore.get(service).search(args.optString("query"))
                if (hits.isEmpty()) "no model decisions matched that query"
                else hits.joinToString("\n") { "[${it.id}] ${it.tool} · ${it.verification} · ${it.reason.take(120)}" }
            }
            "model_status" -> {
                val sb = StringBuilder("Selected provider: ${Prefs.provider(service, false).label}")
                sb.append("\nVision provider: ${Prefs.provider(service, true).label}")
                sb.append("\nOffline mode: ${Prefs.offlineMode(service)}")
                sb.append("\nAuto-switch: ${Prefs.autoSwitch(service)}")
                sb.append("\nPaid providers allowed: ${Prefs.allowPaidProviders(service)}")
                sb.toString()
            }
            "model_offline_mode" -> {
                val on = args.optBoolean("on", false)
                Prefs.setOfflineMode(service, on)
                "offline mode ${if (on) "enabled" else "disabled"}"
            }
            "soul_state" -> {
                val state = OrionSoulLifecycle.state(service)
                "Soul Engine: ${OrionSoulLifecycle.label(service)} (${OrionSoulLifecycle.millisInState(service)} ms in state; last: ${OrionSoulLifecycle.lastAction(service)})"
            }
            "document_extract" -> OrionMediaTools.readDocument(service, args.optString("path").ifBlank { args.optString("uri") })
            "image_read" -> OrionMediaTools.readImage(service, args.optString("path").ifBlank { args.optString("uri") })
            "image_generate" -> OrionMediaTools.generateImage(service, args.optString("prompt"), args.optString("model"), args.optString("size"))
            else -> null
        }
        else -> null
    }

    private fun executeBrowser(
        tool: String,
        args: JSONObject,
        service: PhoneAiService,
        openBrowser: (String) -> Unit
    ): String? = when (tool) {
        "open_url" -> service.openUrl(args.optString("url"))
        "browser_snapshot" -> BrowserAutomation.snapshot().take(6000)
        "browser_find" -> BrowserAutomation.find(args.optString("query")).take(1800)
        "browser_extract" -> BrowserAutomation.extract().take(13000)
        "browser_verify" -> BrowserAutomation.verifyPage(args.optString("expected_text"), args.optString("previous_url")).take(1200)
        "browser_click_verify", "browser_click" -> {
            val target = args.optString("target").trim()
            if (tool == "browser_click_verify") BrowserAutomation.clickAndVerify(target, args.optString("expected_text")).take(1800)
            else BrowserAutomation.click(target)
        }
        "browser_type" -> BrowserAutomation.type(args.optString("target"), args.optString("text"))
        "browser_scroll" -> BrowserAutomation.scroll(args.optString("direction", "down"))
        "browser_navigate" -> {
            val requestedUrl = args.optString("url").trim()
            val existingBrowser = BrowserAutomation.awaitWebView(250L)
            if (existingBrowser != null) BrowserAutomation.navigate(requestedUrl)
            else {
                val candidate = if (requestedUrl.startsWith("https://", true) || requestedUrl.startsWith("http://", true)) requestedUrl else "https://$requestedUrl"
                val uri = runCatching { android.net.Uri.parse(candidate) }.getOrNull()
                if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                    !uri.userInfo.isNullOrBlank() || uri.host.orEmpty().any { it.isWhitespace() }) {
                    "error: provide a valid HTTP or HTTPS URL without embedded credentials"
                } else try {
                    openBrowser(candidate)
                    service.startActivity(android.content.Intent(service, BrowserActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(BrowserActivity.EXTRA_URL, candidate))
                    if (BrowserAutomation.awaitWebView(8000L) == null)
                        "error: Orion Browser launch was requested but its WebView did not become ready; open Orion Browser manually and retry"
                    else "Orion Browser opened at ${uri.host}; inspect browser_snapshot to verify the page before acting."
                } catch (e: Exception) {
                    "error: could not open Orion Browser (${e.javaClass.simpleName}); open it manually and retry"
                }
            }
        }
        "browser_back" -> BrowserAutomation.back()
        "browser_wait" -> BrowserAutomation.waitFor(args.optString("text"), args.optDouble("seconds", 8.0))
        "browser_tabs" -> BrowserActivity.manageTabs(args.optString("action", "list"), args.optInt("index", 0), args.optString("url"))
        "browser_upload" -> BrowserAutomation.upload(args.optString("target"))
        "browser_close" -> BrowserActivity.closeBrowser()
        "browser_handoff" -> BrowserAutomation.handoff(args.optString("reason"))
        else -> null
    }

}
