package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire protocol for the official OpenAI Codex app-server.
 *
 * This class deliberately contains no OAuth implementation and never handles raw
 * ChatGPT cookies. The app-server owns the ChatGPT device/browser login and refresh
 * lifecycle; Orion only sends JSON-RPC messages and consumes its documented events.
 */
internal object CodexAppServerProtocol {
    const val SERVER_NAME = "orion-phoneai"
    const val SERVER_VERSION = "1.0"
    const val CLIENT_NAME = "orion"
    const val CLIENT_TITLE = "Orion"

    data class Request(val id: Int, val method: String, val params: JSONObject) {
        fun toJson(): JSONObject = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
    }

    data class DeviceLogin(val authUrl: String, val userCode: String) {
        val isUsable: Boolean get() =
            authUrl.startsWith("https://") && userCode.isNotBlank()
    }

    data class Account(val type: String, val planType: String, val email: String?) {
        val isChatGptAccount: Boolean get() = type.equals("chatgpt", true)
        val hasSubscriptionEntitlement: Boolean get() =
            planType.lowercase() in setOf("plus", "pro", "business", "enterprise", "edu")
    }

    fun initialize(id: Int = 1): Request = Request(
        id, "initialize",
        JSONObject()
            .put("clientInfo", JSONObject().put("name", CLIENT_NAME).put("title", CLIENT_TITLE).put("version", SERVER_VERSION))
            .put("capabilities", JSONObject())
    )

    fun startChatGptDeviceLogin(id: Int): Request = Request(
        id, "account/login/start",
        JSONObject().put("type", "chatgptDeviceCode")
    )

    fun readAccount(id: Int): Request = Request(id, "account/read", JSONObject())

    fun listModels(id: Int): Request = Request(id, "model/list", JSONObject())

    fun startThread(id: Int, model: String? = null): Request {
        val params = JSONObject()
        if (!model.isNullOrBlank()) params.put("model", model)
        return Request(id, "thread/start", params)
    }

    fun startTurn(id: Int, threadId: String, text: String, model: String? = null): Request {
        require(threadId.isNotBlank()) { "Codex thread id is required" }
        require(text.isNotBlank()) { "Codex turn input is required" }
        val params = JSONObject()
            .put("threadId", threadId)
            .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        if (!model.isNullOrBlank()) params.put("model", model)
        return Request(id, "turn/start", params)
    }

    fun parseDeviceLogin(result: JSONObject): DeviceLogin? {
        val url = result.optString("verificationUrl").ifBlank { result.optString("authUrl") }
        val code = result.optString("userCode").ifBlank { result.optString("code") }
        return DeviceLogin(url, code).takeIf { it.isUsable }
    }

    fun parseAccount(result: JSONObject): Account? {
        val account = result.optJSONObject("account") ?: result
        val type = account.optString("type")
        if (type.isBlank()) return null
        val plan = account.optString("planType").ifBlank { account.optString("plan_type") }
        val email = account.optString("email").takeIf { it.isNotBlank() }
        return Account(type, plan, email)
    }

    fun parseModels(result: JSONObject): List<String> {
        val models = result.optJSONArray("data") ?: result.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).mapNotNull { index ->
            val item = models.optJSONObject(index) ?: return@mapNotNull null
            item.optString("id").takeIf { it.isNotBlank() }
        }.distinct()
    }

    fun isNotification(message: JSONObject): Boolean =
        message.has("method") && !message.has("id")

    fun isLoginCompleted(message: JSONObject): Boolean =
        message.optString("method") == "account/login/completed"

    fun isAccountUpdated(message: JSONObject): Boolean =
        message.optString("method") == "account/updated"

    fun errorMessage(message: JSONObject): String? {
        val error = message.optJSONObject("error") ?: return null
        return error.optString("message").takeIf { it.isNotBlank() }
    }
}
