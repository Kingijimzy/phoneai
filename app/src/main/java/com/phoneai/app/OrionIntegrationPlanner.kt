package com.phoneai.app

/**
 * Capability discovery contract for integration requests.
 *
 * This helper is intentionally deterministic and Android-free. It does not claim that an
 * integration is available merely because it knows the provider name: callers must pass the
 * live registered-tool list and the actual connection state from the app's credential store.
 * Authorization and installation are separate steps and always require explicit user approval.
 */
internal object OrionIntegrationPlanner {
    enum class Provider { GITHUB, GMAIL, GOOGLE_DRIVE, TELEGRAM, TERMUX, UNKNOWN }
    enum class State { CONNECTED, AVAILABLE_NOT_CONNECTED, CAPABILITY_MISSING, UNKNOWN }
    enum class NextStep { USE_EXISTING, ASK_TO_CONNECT, ASK_TO_ENABLE_OR_INSTALL, RESEARCH_AND_PROPOSE, CLARIFY }

    data class Plan(
        val provider: Provider,
        val state: State,
        val nextStep: NextStep,
        val requiredCapabilities: List<String>,
        val needsUserAuthorization: Boolean,
        val mayReadPrivateData: Boolean,
        val explanation: String
    )

    /**
     * Resolve against saved connection metadata and granted scopes. This is the runtime-facing
     * entry point: unknown or missing state is never treated as connected.
     */
    fun planFor(
        context: android.content.Context,
        request: String,
        registeredCapabilities: Set<String>
    ): Plan {
        val provider = providerFor(request)
        if (provider == Provider.UNKNOWN) {
            return plan(provider, State.UNKNOWN, request, registeredCapabilities)
        }
        val (id, scope) = when (provider) {
            Provider.GITHUB -> "github" to "repo"
            Provider.GMAIL -> "gmail" to "https://www.googleapis.com/auth/gmail.readonly"
            Provider.GOOGLE_DRIVE -> "google_drive" to "https://www.googleapis.com/auth/drive.readonly"
            Provider.TELEGRAM -> "telegram" to "telegram.messages"
            Provider.TERMUX -> "termux" to "shell.execute"
            Provider.UNKNOWN -> "" to ""
        }
        val connection = OrionConnectionStore.get(context, id)
        val state = when (provider) {
            // Telegram uses a locally configured bot bridge, not Orion's OAuth connection registry.
            Provider.TELEGRAM -> when {
                "telegram.messages" !in registeredCapabilities && runCatching { TelegramBridge.isConfigured(context) }.getOrDefault(false) ->
                    State.CAPABILITY_MISSING
                "telegram.messages" !in registeredCapabilities -> State.CAPABILITY_MISSING
                runCatching { TelegramBridge.isConfigured(context) }.getOrDefault(false) -> State.CONNECTED
                else -> State.AVAILABLE_NOT_CONNECTED
            }
            // Termux readiness includes its package, external-command permission, bridge setting and pack.
            Provider.TERMUX -> if ("shell.execute" in registeredCapabilities &&
                runCatching { TermuxBridgeRunner.readiness(context) == null }.getOrDefault(false)) {
                State.CONNECTED
            } else {
                State.CAPABILITY_MISSING
            }
            // Other providers are connected only when a real provider token and its required scope exist.
            else -> when {
                connection != null && connection.secretAvailable && scope in connection.scopes -> State.CONNECTED
                connection != null -> State.CAPABILITY_MISSING
                else -> State.AVAILABLE_NOT_CONNECTED
            }
        }
        return plan(provider, state, request, registeredCapabilities)
    }

    /** Resolve a natural-language request to a known provider without treating credentials as input. */
    fun providerFor(request: String): Provider {
        val q = request.lowercase()
        return when {
            Regex("\\b(github|git hub|repository|repo|pull request|commit)\\b").containsMatchIn(q) -> Provider.GITHUB
            Regex("\\b(gmail|google mail|email|inbox|emails|messages)\\b").containsMatchIn(q) -> Provider.GMAIL
            Regex("\\b(google drive|drive files|drive document)\\b").containsMatchIn(q) -> Provider.GOOGLE_DRIVE
            Regex("\\b(telegram|telegram bot)\\b").containsMatchIn(q) -> Provider.TELEGRAM
            Regex("\\b(termux|terminal|shell|command line|cli)\\b").containsMatchIn(q) -> Provider.TERMUX
            else -> Provider.UNKNOWN
        }
    }

    /**
     * Make a plan from facts supplied by the runtime. Missing/unknown status never implies access.
     * A private-data request is marked for authorization even if the provider is installed.
     */
    fun plan(
        provider: Provider,
        state: State,
        request: String,
        registeredCapabilities: Set<String>
    ): Plan {
        if (provider == Provider.UNKNOWN) {
            return Plan(provider, state, NextStep.CLARIFY, emptyList(), true, false,
                "I couldn't identify the requested service reliably; ask which account or service they mean.")
        }
        val required = when (provider) {
            Provider.GITHUB -> listOf("github.account.read")
            Provider.GMAIL -> listOf("gmail.messages.read")
            Provider.GOOGLE_DRIVE -> listOf("google_drive.files.read")
            Provider.TELEGRAM -> listOf("telegram.messages")
            Provider.TERMUX -> listOf("shell.execute")
            Provider.UNKNOWN -> emptyList()
        }
        val asksPrivateRead = Regex("(?i)\\b(read|search|check|summari[sz]e|open|view|fetch|list)\\b").containsMatchIn(request)
        val missing = required.any { it !in registeredCapabilities }
        val next = when {
            state == State.CONNECTED && !missing -> NextStep.USE_EXISTING
            state == State.AVAILABLE_NOT_CONNECTED && !missing -> NextStep.ASK_TO_CONNECT
            state == State.CAPABILITY_MISSING -> NextStep.ASK_TO_ENABLE_OR_INSTALL
            state == State.UNKNOWN || missing -> NextStep.RESEARCH_AND_PROPOSE
            else -> NextStep.RESEARCH_AND_PROPOSE
        }
        val explanation = when (next) {
            NextStep.USE_EXISTING -> "Use only the already-connected capability and its granted scope."
            NextStep.ASK_TO_CONNECT -> "Explain the requested access and ask the user to authorize the provider."
            NextStep.ASK_TO_ENABLE_OR_INSTALL -> "Explain the missing capability, research a safe supported implementation, and ask before installing or enabling it."
            NextStep.RESEARCH_AND_PROPOSE -> "Verify the live tool registry and connection state, research official integration documentation, then present the concrete missing prerequisite and ask before changing the app or installing anything."
            NextStep.CLARIFY -> "Ask one concise question to identify the service."
        }
        return Plan(
            provider = provider,
            state = state,
            nextStep = next,
            requiredCapabilities = required,
            needsUserAuthorization = state != State.CONNECTED || asksPrivateRead,
            mayReadPrivateData = provider == Provider.GMAIL || provider == Provider.GOOGLE_DRIVE || provider == Provider.GITHUB,
            explanation = explanation
        )
    }
}
