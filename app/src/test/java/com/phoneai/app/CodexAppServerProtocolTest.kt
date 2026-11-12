package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerProtocolTest {
    @Test fun deviceLoginUsesOfficialChatGptDeviceFlow() {
        val request = CodexAppServerProtocol.startChatGptDeviceLogin(7).toJson()
        assertEquals("account/login/start", request.getString("method"))
        assertEquals("chatgptDeviceCode", request.getJSONObject("params").getString("type"))
    }

    @Test fun deviceLoginRejectsNonHttpsOrMissingCode() {
        assertNull(CodexAppServerProtocol.parseDeviceLogin(JSONObject().put("authUrl", "http://bad").put("userCode", "ABCD")))
        assertNull(CodexAppServerProtocol.parseDeviceLogin(JSONObject().put("authUrl", "https://auth.openai.com/codex/device")))
        assertNotNull(CodexAppServerProtocol.parseDeviceLogin(JSONObject().put("verificationUrl", "https://auth.openai.com/codex/device").put("userCode", "ABCD-EFGH")))
    }

    @Test fun accountEntitlementIsDerivedFromServerPlan() {
        val account = CodexAppServerProtocol.parseAccount(
            JSONObject().put("account", JSONObject().put("type", "chatgpt").put("planType", "plus").put("email", "owner@example.com"))
        )!!
        assertTrue(account.isChatGptAccount)
        assertTrue(account.hasSubscriptionEntitlement)
        assertEquals("owner@example.com", account.email)
    }

    @Test fun freePlanIsNotMisreportedAsSubscriptionEntitlement() {
        val account = CodexAppServerProtocol.parseAccount(
            JSONObject().put("type", "chatgpt").put("planType", "free")
        )!!
        assertFalse(account.hasSubscriptionEntitlement)
    }

    @Test fun modelCatalogueReadsServerIdsOnly() {
        val result = JSONObject().put("data", org.json.JSONArray()
            .put(JSONObject().put("id", "gpt-5-codex"))
            .put(JSONObject().put("id", "gpt-5-codex"))
            .put(JSONObject().put("name", "missing-id")))
        assertEquals(listOf("gpt-5-codex"), CodexAppServerProtocol.parseModels(result))
    }

    @Test fun turnRequiresRealThreadAndInput() {
        val request = CodexAppServerProtocol.startTurn(9, "thread-1", "inspect this repo").toJson()
        assertEquals("turn/start", request.getString("method"))
        assertEquals("thread-1", request.getJSONObject("params").getString("threadId"))
        assertEquals("inspect this repo", request.getJSONObject("params").getJSONArray("input").getJSONObject(0).getString("text"))
    }

    @Test fun notificationsAreNotResponses() {
        assertTrue(CodexAppServerProtocol.isNotification(JSONObject().put("method", "account/login/completed")))
        assertFalse(CodexAppServerProtocol.isNotification(JSONObject().put("id", 1).put("method", "account/login/completed")))
    }
}
