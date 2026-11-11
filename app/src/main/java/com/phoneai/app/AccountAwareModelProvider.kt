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
        onDelta: ((String) -> Unit)?
    ): OrionModelTurn {
        val provider = selected(vision)
        if (vision && provider.id in ACCOUNT_IDS) {
            throw AiException(provider.label + " does not expose a verified vision route through its account CLI.")
        }
        if (provider.id !in ACCOUNT_IDS) {
            return fallback.chatTurn(messages, vision, maxTokens, temperature, fastResponse, tools, onDelta)
        }
        check(!cancelled) { "Account model request was cancelled." }
        val prompt = transcript(messages)
        require(prompt.isNotBlank()) { "No text content was supplied to the account model." }
        val model = Prefs.model(app, provider, false).takeIf { it.isNotBlank() }
        val answer = synchronized(lock) {
            check(!cancelled) { "Account model request was cancelled." }
            when (provider.id) {
                "codex" -> runCodex(prompt, model, onDelta)
                "antigravity" -> runAntigravity(prompt, model, onDelta)
                else -> error("Unsupported account provider: " + provider.id)
            }
        }
        return OrionModelTurn(answer)
    }

    private fun runCodex(prompt: String, requestedModel: String?, onDelta: ((String) -> Unit)?): String {
        val installer = CodexRuntimeInstaller(app)
        val client = installer.openAppServer(app.filesDir.absolutePath)
        activeCodex = client
        try {
            check(client.initialize()) { "Codex app-server initialization failed." }
            val account = client.readAccount()
            check(account?.isChatGptAccount == true) { "Sign in with your ChatGPT account in Account Connections first." }
            val catalogue = runCatching { client.availableModels() }.getOrDefault(emptyList())
            val model = requestedModel?.takeIf { catalogue.isEmpty() || it in catalogue }
                ?: catalogue.firstOrNull()
            val thread = client.startThread(model)
            val answer = client.runTurnAndAwait(thread, prompt, model, onDelta = onDelta)
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

    private fun runAntigravity(prompt: String, requestedModel: String?, onDelta: ((String) -> Unit)?): String {
        val installer = AntigravityRuntimeInstaller(app)
        installer.install().getOrThrow()
        val model = requestedModel?.takeIf { it.isNotBlank() }
        val answer = installer.runPrompt(app.filesDir.absolutePath, prompt, model)
        check(answer.isNotBlank()) { "Antigravity returned an empty model response." }
        Prefs.setAccountConnected(app, "antigravity", true)
        accountRequests++
        onDelta?.invoke(answer)
        return answer
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
