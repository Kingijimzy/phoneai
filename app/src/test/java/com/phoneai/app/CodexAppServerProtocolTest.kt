package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerProtocolTest {
    @Test fun deviceLoginUsesOfficialChatGptDeviceFlow() {
        val request = CodexAppServerProtocol.startChatGptDeviceLogin(7).toJson()
        assertFalse(request.has("jsonrpc"))
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

    @Test fun threadStartCarriesOnlyRegisteredOrionDynamicToolsAndApprovalReview() {
        val tools = listOf(
            ToolDefinition("battery", "battery {}: report battery status", "report battery status", ToolAccess.READ),
            ToolDefinition("open_app", "open_app {name}: open installed app", "open installed app", ToolAccess.WRITE)
        )
        val request = CodexAppServerProtocol.startThread(11, "gpt-5-codex", tools).toJson()
        val params = request.getJSONObject("params")
        assertEquals("auto_review", params.getString("approvalsReviewer"))
        val dynamic = params.getJSONArray("dynamicTools")
        assertEquals(2, dynamic.length())
        assertEquals("function", dynamic.getJSONObject(0).getString("type"))
        assertEquals("battery", dynamic.getJSONObject(0).getString("name"))
        assertTrue(dynamic.getJSONObject(0).getString("description").contains("report battery status"))
        assertEquals("object", dynamic.getJSONObject(0).getJSONObject("inputSchema").getString("type"))
        assertEquals("string", dynamic.getJSONObject(1).getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("name").getString("type"))
        assertTrue(dynamic.getJSONObject(1).getJSONObject("inputSchema").getJSONArray("required").toString().contains("name"))
        assertEquals("gpt-5-codex", params.getString("model"))
    }

    @Test fun initializeAcknowledgementMatchesPinnedCodexResponseContract() {
        assertTrue(CodexAppServerProtocol.isInitializeResponse(
            JSONObject().put("userAgent", "codex-app-server/0.167.1")
                .put("codexHome", "/root/.codex")
                .put("platformFamily", "unix")
                .put("platformOs", "linux")
        ))
        assertFalse(CodexAppServerProtocol.isInitializeResponse(
            JSONObject().put("serverInfo", JSONObject().put("name", "codex"))
                .put("capabilities", JSONObject())
        ))
    }

    @Test fun initializeOptsIntoExperimentalDynamicToolApi() {
        val params = CodexAppServerProtocol.initialize(1).toJson().getJSONObject("params")
        assertTrue(params.getJSONObject("capabilities").getBoolean("experimentalApi"))
    }

    @Test fun dynamicToolResponseUsesAppServerInputTextContentItems() {
        val response = CodexAppServerProtocol.dynamicToolResponse("Battery 82 percent", true)
        assertTrue(response.getBoolean("success"))
        val item = response.getJSONArray("contentItems").getJSONObject(0)
        assertEquals("inputText", item.getString("type"))
        assertEquals("Battery 82 percent", item.getString("text"))
        assertFalse(CodexAppServerProtocol.dynamicToolResponse("error: denied", false).getBoolean("success"))
    }

    @Test fun notificationsAreNotResponses() {
        assertTrue(CodexAppServerProtocol.isNotification(JSONObject().put("method", "account/login/completed")))
        assertFalse(CodexAppServerProtocol.isNotification(JSONObject().put("id", 1).put("method", "account/login/completed")))
    }

    @Test fun validatesCodexDynamicToolCallsAgainstTheExactActiveThreadAndTurn() {
        val actualServerPayload = JSONObject()
            .put("threadId", "thread-1")
            .put("turnId", "turn-1")
            .put("callId", "call-1")
            .put("namespace", JSONObject.NULL)
            .put("tool", "battery")
            .put("arguments", JSONObject().put("detail", "full"))
        assertTrue(CodexAppServerProtocol.toolCallBelongsToActiveTurn(actualServerPayload, "thread-1", "turn-1"))
        assertFalse(CodexAppServerProtocol.toolCallBelongsToActiveTurn(actualServerPayload, "thread-1", "turn-2"))
        assertFalse(CodexAppServerProtocol.toolCallBelongsToActiveTurn(actualServerPayload, "other-thread", "turn-1"))
        assertFalse(CodexAppServerProtocol.toolCallBelongsToActiveTurn(
            JSONObject(actualServerPayload.toString()).put("threadId", ""),
            "thread-1",
            "turn-1"
        ))
    }
    @Test fun resumeUsesPersistentThreadWithoutHydratingFullHistory() {
        val request = CodexAppServerProtocol.resumeThread(21, "thr_existing").toJson()
        assertEquals("thread/resume", request.getString("method"))
        assertEquals("thr_existing", request.getJSONObject("params").getString("threadId"))
        assertTrue(request.getJSONObject("params").getBoolean("excludeTurns"))
    }

    @Test fun manualCompactionTargetsOnlyTheSelectedThread() {
        val request = CodexAppServerProtocol.compactThread(22, "thr_existing").toJson()
        assertEquals("thread/compact/start", request.getString("method"))
        assertEquals("thr_existing", request.getJSONObject("params").getString("threadId"))
    }

    @Test fun timeoutInterruptTargetsOnlyTheActiveTurn() {
        val request = CodexAppServerProtocol.interruptTurn(23, "thr_existing", "turn_active").toJson()
        assertEquals("turn/interrupt", request.getString("method"))
        assertEquals("thr_existing", request.getJSONObject("params").getString("threadId"))
        assertEquals("turn_active", request.getJSONObject("params").getString("turnId"))
    }

}
