package com.phoneai.app

import org.json.JSONObject

/**
 * Concrete local-runtime and file capability executor.
 * This owns the execution implementation for these backend families; Agent remains responsible
 * for task planning, policy gates, approvals and evidence; Tasks owns durable checkpoint/recovery operations.
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
        OrionCapabilityAdapters.Backend.UTILITIES -> OrionUtilitiesCapabilityExecutor.supports(tool)\n        OrionCapabilityAdapters.Backend.DIAGNOSTICS -> OrionDiagnosticsCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.TRADING_READONLY -> OrionTradingReadOnlyCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.TRADING_DESK -> OrionTradingDeskCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.GAMING -> OrionGamingCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.PYTHON -> OrionPythonCapabilityExecutor.supports(tool)
        OrionCapabilityAdapters.Backend.SHELL -> tool in setOf("shell", "termux_command")
        OrionCapabilityAdapters.Backend.FILES -> tool in setOf("find_files", "open_file", "save_file", "workspace_write", "workspace_read")
        OrionCapabilityAdapters.Backend.BROWSER -> tool in setOf(
            "open_url", "browser_snapshot", "browser_find", "browser_extract", "browser_verify",
            "browser_click", "browser_click_verify", "browser_type", "browser_scroll",
            "browser_navigate", "browser_back", "browser_handoff", "browser_wait",
            "browser_tabs", "browser_upload", "browser_close"
        )
        OrionCapabilityAdapters.Backend.CORE -> false
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
        pythonExecutor: OrionPythonCapabilityExecutor? = null
    ): String? = when (backend) {
        OrionCapabilityAdapters.Backend.DEVICE -> OrionDeviceCapabilityExecutor.execute(
            tool, args, service, tools, stopped, confirm, validateAction, verifyAction, verifySend, look, play, readScreen
        )
        OrionCapabilityAdapters.Backend.MCP -> OrionMcpCapabilityExecutor.execute(tool, args, service, tools, confirm)
        OrionCapabilityAdapters.Backend.INTEGRATIONS -> OrionIntegrationCapabilityExecutor.execute(tool, args, service, tools, githubAccountRead)
        OrionCapabilityAdapters.Backend.SKILLS -> OrionSkillsCapabilityExecutor.execute(tool, args, service)
        OrionCapabilityAdapters.Backend.RESEARCH -> OrionResearchCapabilityExecutor.execute(tool, args, service, tools, researchCancelled)
        OrionCapabilityAdapters.Backend.COMMUNICATION -> OrionCommunicationCapabilityExecutor.execute(tool, args, service, tools, stopped, confirm)
        OrionCapabilityAdapters.Backend.MEMORY -> OrionMemoryCapabilityExecutor.execute(tool, args, service, tools, stopped)
        OrionCapabilityAdapters.Backend.TASKS -> OrionTasksCapabilityExecutor.execute(tool, args, service, tools, stopped, confirm, askUser)
        OrionCapabilityAdapters.Backend.UTILITIES -> OrionUtilitiesCapabilityExecutor.execute(tool, args, tools, stopped)\n        OrionCapabilityAdapters.Backend.DIAGNOSTICS -> OrionDiagnosticsCapabilityExecutor.execute(tool, args, service, confirm)
        OrionCapabilityAdapters.Backend.TRADING_READONLY -> OrionTradingReadOnlyCapabilityExecutor.execute(tool, args, service)
        OrionCapabilityAdapters.Backend.TRADING_DESK -> OrionTradingDeskCapabilityExecutor.execute(tool, args, service, stopped)
        OrionCapabilityAdapters.Backend.GAMING -> OrionGamingCapabilityExecutor.execute(tool, args, service)
        OrionCapabilityAdapters.Backend.BROWSER -> executeBrowser(tool, args, service, confirm, openBrowser)
        OrionCapabilityAdapters.Backend.SHELL -> when (tool) {
            "shell" -> tools.shell(args.optString("command"), args.optString("where"))
            "termux_command" -> TermuxBridgeRunner.executeReadOnly(service, args.optString("command"), stopped)
            else -> null
        }
        OrionCapabilityAdapters.Backend.PYTHON -> pythonExecutor?.execute(tool, args, confirm, stopped)
        OrionCapabilityAdapters.Backend.FILES -> when (tool) {
            "find_files" -> tools.findFiles(args.optString("query"))
            "open_file" -> tools.openFile(args.optString("id"))
            "save_file" -> tools.saveFile(args.optString("filename"), args.optString("content"), args.optString("folder", "Orion"))
            "workspace_write" -> ShellRunner.writeFile(service, args.optString("path"), args.optString("content"))
            "workspace_read" -> ShellRunner.readFile(service, args.optString("path"))
            else -> null
        }
        else -> null
    }

    private fun executeBrowser(
        tool: String,
        args: JSONObject,
        service: PhoneAiService,
        confirm: (String) -> Boolean,
        openBrowser: (String) -> Unit
    ): String? = when (tool) {
        "open_url" -> service.openUrl(args.optString("url"))
        "browser_snapshot" -> BrowserAutomation.snapshot().take(6000)
        "browser_find" -> BrowserAutomation.find(args.optString("query")).take(1800)
        "browser_extract" -> BrowserAutomation.extract().take(13000)
        "browser_verify" -> BrowserAutomation.verifyPage(args.optString("expected_text"), args.optString("previous_url")).take(1200)
        "browser_click_verify", "browser_click" -> {
            val target = args.optString("target").trim()
            val risky = Regex("(?i)\\b(submit|send|post|publish|buy|purchase|pay|checkout|delete|remove|transfer|withdraw|order|trade|subscribe|confirm|place order|save changes)\\b")
                .containsMatchIn(target)
            val approved = !risky || confirm(
                if (tool == "browser_click_verify")
                    "Orion is about to click '$target' and inspect the resulting page. This may submit or change data. Continue?"
                else "Orion is about to click '$target' on the current webpage. This may submit or change data. Continue?"
            )
            if (!approved) "error: user cancelled browser click"
            else if (tool == "browser_click_verify") BrowserAutomation.clickAndVerify(target, args.optString("expected_text")).take(1800)
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
