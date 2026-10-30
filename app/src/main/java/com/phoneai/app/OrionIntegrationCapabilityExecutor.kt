package com.phoneai.app

import org.json.JSONObject

/**
 * Owns connected-service operations. Credentials remain in the encrypted vault/connectors;
 * this executor only coordinates the existing authenticated connector APIs and secure UI.
 */
internal object OrionIntegrationCapabilityExecutor {
    private val supportedTools = setOf(
        "integration_status", "service_status", "connect_service", "github_account_read",
        "gmail_list_messages", "vault_list", "vault_request", "vault_get", "capability_note"
    )

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(
        tool: String,
        args: JSONObject,
        service: PhoneAiService,
        tools: Tools,
        githubAccountRead: (String) -> String
    ): String = when (tool) {
        "integration_status" -> tools.integrationStatus()
        "service_status" -> tools.serviceStatus()
        "connect_service" -> tools.connectService(args.optString("service"))
        "github_account_read" -> githubAccountRead(args.optString("path"))
        "gmail_list_messages" -> GmailConnector.read(service, args.optString("query"), args.optInt("limit", 5))
        "vault_list" -> tools.vaultList()
        "vault_request" -> tools.vaultRequest(args.optString("name"), args.optString("host"), args.optString("style"))
        "vault_get" -> tools.vaultGet(args.optString("name"), args.optString("url"))
        "capability_note" -> tools.capabilityNote(args.optString("goal"), args.optString("provider"))
        else -> "error: integrations executor does not implement '$tool'."
    }
}
