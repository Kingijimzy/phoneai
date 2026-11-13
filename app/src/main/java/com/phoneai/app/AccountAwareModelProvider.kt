package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes account-backed providers through their official native clients; all API-key
 * providers continue through Nim unchanged. Account readiness is recorded only after a
 * real model response has been received.
 */
internal class AccountAwareModelProvider(
    context: Context,
    private val fallback: OrionModelProvider,
    private val commandMode: Boolean
) : OrionModelProvider {
    private val app = context.applicationContext
    private val lock = Any()
    @Volatile private var cancelled = false
    @Volatile private var activeCodex: CodexAppServerClient? = null
    @Volatile private var activeAntigravity: LinuxCommandSession? = null
    @Volatile private var accountRequests = 0

    private fun selected(vision: Boolean): Provider {
        if (commandMode && !vision) {
            Prefs.commandProviderId(app)?.let { Providers.find(it) }?.let { return it }
            return Prefs.defaultCommandProvider(app)
        }
        return Prefs.provider(app, vision)
    }

    override fun chat(
        messages: JSONArray,
        vision: Boolean,
        maxTokens: Int,
        temperature: Double,
        fastResponse: Boolean,
        onDelta: ((String) -> Unit)?
    ): String = chatTurn(messages, vision, maxTokens, temperature, fastResponse, emptyList(), onDelta).text

    override fun chatTurn(
        messages: JSONArray,
        vision: Boolean,
        maxTokens: Int,
        temperature: Double,
        fastResponse: Boolean,
        tools: List<ToolDefinition>,
        onDelta: ((String) -> Unit)?,
        onToolCall: ((String, JSONObject) -> String)?
    ): OrionModelTurn {
        val provider = selected(vision)
        if (vision && provider.id in ACCOUNT_IDS) {
            throw AiException(provider.label + " does not expose a verified vision route through its account CLI.")
        }
        if (provider.id !in ACCOUNT_IDS) {
            return fallback.chatTurn(messages, vision, maxTokens, temperature, fastResponse, tools, onDelta)
        }
        check(!cancelled) { "Account model request was cancelled." }
        val model = (if (commandMode && !vision) Prefs.commandModelId(app) else null)
            ?: Prefs.model(app, provider, false).takeIf { it.isNotBlank() }
        // Account CLIs have their own hard payload limits. Apply the same provider-aware
        // context governance used by API providers before serialising the transcript.
        // Antigravity's CLI rejects prompts above 100k characters; unknown account models
        // deliberately receive the conservative 16k-token policy rather than an assumed
        // large context window. This keeps the newest user turn and complete tool exchanges.
        val answer = synchronized(lock) {
            check(!cancelled) { "Account model request was cancelled." }
            when (provider.id) {
                // Codex owns durable conversation history. Sending Orion's entire transcript
                // on every request creates a new, oversized, stateless Codex session.
                "codex" -> runCodex(messages, model, tools, onDelta, onToolCall)
                // Antigravity's headless CLI is prompt-oriented, so it still receives a
                // bounded transcript rather than Codex's native thread lifecycle.
                "antigravity" -> {
                    val contextCapabilities = OrionProviderContextPolicy.capabilities(
                        provider.id, model.orEmpty(), transport = "chat"
                    )
                    val budgetedMessages = OrionProviderContextPolicy.fitMessages(
                        messages, contextCapabilities, maxTokens
                    )
                    // The CLI has no durable native thread API. Keep the latest complete
                    // messages and discard older history deterministically instead of failing
                    // the entire request after the provider-specific budgeter has run.
                    val prompt = boundedRecentTranscript(budgetedMessages, 60_000)
                    require(prompt.isNotBlank()) { "No text content was supplied to Antigravity." }
                    runAntigravity(prompt, model, tools, onDelta, onToolCall)
                }
                else -> error("Unsupported account provider: " + provider.id)
            }
        }
        // Both account agents expose the exact current Orion tool set through a transport
        // adapter that routes calls back into the same guarded Central Brain executor.
        val nativeBridgeReady = provider.id in ACCOUNT_IDS && tools.isNotEmpty() && onToolCall != null
        return OrionModelTurn(answer, toolCallingAvailable = nativeBridgeReady)
    }

    private fun runCodex(
        messages: JSONArray,
        requestedModel: String?,
        tools: List<ToolDefinition>,
        onDelta: ((String) -> Unit)?,
        onToolCall: ((String, JSONObject) -> String)?
    ): String {
        val installer = CodexRuntimeInstaller(app)
        val client = installer.openAppServer(app.filesDir.absolutePath)
        activeCodex = client
        val preferences = app.getSharedPreferences("phoneai", Context.MODE_PRIVATE)
        try {
            check(client.initialize()) { "Codex app-server initialization failed." }
            val account = client.readAccount()
            check(account?.isChatGptAccount == true) { "Sign in with your ChatGPT account in Account Connections first." }
            val catalogue = runCatching { client.availableModels() }.getOrDefault(emptyList())
            val model = when {
                requestedModel.isNullOrBlank() -> catalogue.firstOrNull()
                catalogue.isEmpty() -> requestedModel
                requestedModel != null && requestedModel in catalogue -> requestedModel
                !Prefs.autoSwitch(app) -> throw AiException(
                    "The selected Codex model '$requestedModel' is not in the current account catalogue. Auto-switch is off, so Orion kept your selection."
                )
                else -> catalogue.firstOrNull()
            }

            // Bind the durable Codex thread to its model and exact dynamic-tool contract.
            // If either changes, create a fresh native thread rather than resuming a thread
            // whose provider/model or callable schema no longer matches Orion's current turn.
            val toolFingerprint = tools.sortedBy { it.name }.joinToString("|") {
                it.name + ":" + it.inputSchema.toString()
            }
            val storedThread = preferences.getString("codex_native_thread_id", null)
            val storedModel = preferences.getString("codex_native_thread_model", null)
            val storedTools = preferences.getString("codex_native_thread_tools", null)
            val canResume = !storedThread.isNullOrBlank() &&
                storedModel == model.orEmpty() && storedTools == toolFingerprint

            val thread = if (canResume) {
                try {
                    client.resumeThread(storedThread!!)
                } catch (failure: Exception) {
                    val detail = failure.message.orEmpty().lowercase()
                    val staleThread = detail.contains("not found") ||
                        detail.contains("unknown thread") ||
                        detail.contains("no such thread") ||
                        detail.contains("does not exist")
                    if (!staleThread) throw failure
                    client.startThread(model, tools)
                }
            } else {
                client.startThread(model, tools)
            }

            preferences.edit()
                .putString("codex_native_thread_id", thread)
                .putString("codex_native_thread_model", model.orEmpty())
                .putString("codex_native_thread_tools", toolFingerprint)
                .apply()

            // A resumed thread already contains its own prior user/assistant/tool history.
            // Send only the newest user turn. Seed a newly-created thread with a bounded
            // recent transcript once, so existing Orion context is not discarded.
            val input = if (canResume && thread == storedThread) {
                latestUserText(messages)
            } else {
                boundedRecentTranscript(messages, 60_000)
            }
            require(input.isNotBlank()) { "No current user text was supplied to Codex." }
            val answer = client.runTurnAndAwait(
                thread, input, model,
                timeoutMs = 600_000L,
                onDelta = onDelta,
                onToolCall = onToolCall
            )
            check(answer.isNotBlank()) { "Codex returned an empty model response." }
            Prefs.setAccountConnected(app, "codex", true)
            accountRequests++
            return answer
        } catch (failure: Exception) {
            if (failure.message.orEmpty().contains("auth", true) ||
                failure.message.orEmpty().contains("sign in", true)) {
                Prefs.setAccountConnected(app, "codex", false)
            }
            throw failure
        } finally {
            activeCodex = null
            client.close()
        }
    }

    private fun latestUserText(messages: JSONArray): String {
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (!message.optString("role").equals("user", ignoreCase = true)) continue
            val text = contentText(message.opt("content")).trim()
            if (text.isNotBlank()) return text
        }
        return ""
    }


    /** Keep whole recent messages when seeding a new native thread; never cut through a message. */
    private fun boundedRecentTranscript(messages: JSONArray, maxChars: Int): String {
        val selected = ArrayList<String>()
        var used = 0
        var omittedOlderMessages = false
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            val body = contentText(message.opt("content")).trim()
            if (body.isBlank()) continue
            val role = message.optString("role").ifBlank { "user" }.uppercase()
            val chunk = "$role: $body"
            if (selected.isNotEmpty() && used + chunk.length + 2 > maxChars) {
                omittedOlderMessages = true
                break
            }
            selected.add(if (chunk.length > maxChars) chunk.takeLast(maxChars) else chunk)
            used += chunk.length + 2
            if (used >= maxChars) {
                omittedOlderMessages = index > 0
                break
            }
        }
        val recent = selected.asReversed().joinToString("\n\n")
        return if (omittedOlderMessages) {
            "[Orion compacted older chat history to fit the provider's prompt limit. Continue from the recent messages below.]\n\n$recent"
        } else recent
    }

    private fun contentText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") in setOf("text", "input_text", "output_text")) {
                    append(part.optString("text")).append('\n')
                }
            }
        }
        is JSONObject -> content.optString("text")
        else -> ""
    }

    private fun runAntigravity(
        prompt: String,
        requestedModel: String?,
        tools: List<ToolDefinition>,
        onDelta: ((String) -> Unit)?,
        onToolCall: ((String, JSONObject) -> String)?
    ): String {
        val installer = AntigravityRuntimeInstaller(app)
        installer.install().getOrThrow()
        val model = requestedModel?.takeIf { it.isNotBlank() }
        try {
            val answer = installer.runPrompt(
                app.filesDir.absolutePath,
                prompt,
                model,
                tools = tools,
                onToolCall = onToolCall,
                onDelta = onDelta,
                onSession = { session -> activeAntigravity = session }
            )
            check(answer.isNotBlank()) { "Antigravity returned an empty model response." }
            Prefs.setAccountConnected(app, "antigravity", true)
            accountRequests++
            return answer
        } catch (failure: Exception) {
            if (failure.message.orEmpty().contains("auth", true) ||
                failure.message.orEmpty().contains("sign in", true)) {
                Prefs.setAccountConnected(app, "antigravity", false)
            }
            throw failure
        } finally {
            activeAntigravity = null
        }
    }

    override fun testModel(provider: Provider, model: String, vision: Boolean): String {
        if (provider.id !in ACCOUNT_IDS) return fallback.testModel(provider, model, vision)
        return try {
            val message = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "Reply with the exact text ORION_PROVIDER_OK."))
                .put(JSONObject().put("role", "user").put("content", "Verify this account model connection."))
            val previous = Prefs.model(app, provider, false)
            Prefs.setModel(app, provider, false, model)
            try {
                val result = chat(message, false, 256, 0.0, true, null)
                if (result.contains("ORION_PROVIDER_OK")) "" else "The provider returned a response but did not pass Orion's verification challenge."
            } finally {
                Prefs.setModel(app, provider, false, previous)
            }
        } catch (failure: Exception) {
            failure.message ?: failure.javaClass.simpleName
        }
    }

    override fun allModels(provider: Provider): List<String> {
        if (provider.id !in ACCOUNT_IDS) return fallback.allModels(provider)
        val models = when (provider.id) {
            "codex" -> {
                val client = CodexRuntimeInstaller(app).openAppServer(app.filesDir.absolutePath)
                activeCodex = client
                try {
                    check(client.initialize()) { "Codex app-server initialization failed." }
                    check(client.readAccount()?.isChatGptAccount == true) { "Sign in with ChatGPT first." }
                    client.availableModels()
                } finally {
                    activeCodex = null
                    client.close()
                }
            }
            "antigravity" -> AntigravityRuntimeInstaller(app).let {
                it.install().getOrThrow()
                it.listModels(app.filesDir.absolutePath)
            }
            else -> emptyList()
        }
        if (models.isNotEmpty()) Prefs.saveCachedModels(app, provider.id, models)
        return models
    }

    override fun cancel() {
        cancelled = true
        fallback.cancel()
        activeCodex?.close()
        activeAntigravity?.close()
    }

    override fun resume() {
        cancelled = false
        fallback.resume()
    }

    override fun requestsUsed(): Int = fallback.requestsUsed() + accountRequests

    override fun beginCommand() {
        accountRequests = 0
        fallback.beginCommand()
    }

    private fun transcript(messages: JSONArray): String {
        val out = StringBuilder()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role").ifBlank { "user" }
            val content = message.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text")).append('\n')
                    }
                }
                is JSONObject -> content.optString("text")
                else -> ""
            }
            if (text.isNotBlank()) out.append(role.uppercase()).append(": ").append(text).append("\n\n")
        }
        return out.toString().trim()
    }

    companion object {
        private val ACCOUNT_IDS = setOf("codex", "antigravity")
    }
}
