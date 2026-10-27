package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class TaskScopePolicyTest {
    @Test fun classifiesDistinctTaskModes() {
        assertEquals(TaskMode.CHAT, TaskScopePolicy.modeFor("Explain how Bluetooth works"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Open WhatsApp and read my last chat"))
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("Research recent Android accessibility changes online"))
        assertEquals(TaskMode.READ_ONLY, TaskScopePolicy.modeFor("Read-only: inspect my notifications without changing anything"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("What time is it"))
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("What is the weather forecast in Lagos"))
    }


    @Test fun accountConnectionRequestsAreActionableNotConversation() {
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("I would like connect my GitHub account"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Check if my Gmail is connected"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("I'm checking which services are currently connected"))
    }

    @Test fun shortConfirmationKeepsTheIntegrationTaskScope() {
        val previous = "I would like connect my GitHub account"
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor(TaskScopePolicy.scopeFor("Yes", previous, 1_000)))
    }

    @Test fun naturalResearchFollowUpsAreClassifiedAsResearch() {
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("Go do research about it"))
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("Do research about GitHub connection options"))
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("Go research official Gmail authorization docs"))
    }

    @Test fun explicitWhatsAppSendRequestAllowsPhoneToolScope() {
        val request = "open WhatsApp and send kingi jimzy this message good morning chams"
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor(request))
        assertNull(TaskScopePolicy.blockReason(request, "open_app"))
        assertNull(TaskScopePolicy.blockReason(request, "read_screen"))
        assertNotNull(TaskScopePolicy.blockReason(request, "bybit_public_ticker"))
    }

    @Test fun explicitActionIsNotDowngradedByConversationWording() {
        assertEquals(
            TaskMode.PHONE_AUTOMATION,
            TaskScopePolicy.modeFor("Please help me with this: open WhatsApp and send good morning")
        )
        assertEquals(TaskMode.CHAT, TaskScopePolicy.modeFor("Explain what WhatsApp is used for"))
    }

    @Test fun mentioningAnAppInAQuestionDoesNotTriggerPhoneAutomation() {
        assertEquals(TaskMode.CHAT, TaskScopePolicy.modeFor("Explain what WhatsApp is used for"))
        assertEquals(TaskMode.CHAT, TaskScopePolicy.modeFor("What are the advantages of Telegram?"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Open WhatsApp and send good morning"))
    }

    @Test fun chatCannotInvokeToolsEvenIfModelRequestsOne() {
        assertNotNull(TaskScopePolicy.blockReason("Explain how Bluetooth works", "read_screen"))
        assertNotNull(TaskScopePolicy.blockReason("Explain how Bluetooth works", "open_app"))
    }

    @Test fun researchAndReadOnlyCannotMutateState() {
        assertNull(TaskScopePolicy.blockReason("Research Android accessibility online", "web_search"))
        assertNotNull(TaskScopePolicy.blockReason("Research Android accessibility online", "open_app"))
        assertNull(TaskScopePolicy.blockReason("Read-only inspect my notifications", "read_screen"))
        assertNotNull(TaskScopePolicy.blockReason("Read-only inspect my notifications", "type"))
    }

    @Test fun unrelatedSpecialistGroupsAreBlocked() {
        assertNotNull(TaskScopePolicy.blockReason("Open WhatsApp and read my last chat", "bybit_public_ticker"))
        assertNotNull(TaskScopePolicy.blockReason("What is the BTC price on Bybit", "telegram_send"))
        assertNull(TaskScopePolicy.blockReason("What is the BTC price on Bybit", "bybit_public_ticker"))
        assertNull(TaskScopePolicy.blockReason("Open Telegram and read my messages", "telegram_status"))
    }

    @Test fun unknownToolsAreAlwaysDenied() {
        assertNotNull(TaskScopePolicy.blockReason("Open WhatsApp", "run_shell"))
    }

    @Test fun shortFollowUpInheritsRecentActionScope() {
        val prev = "open WhatsApp and send kingi jimzy good morning"
        val scope = TaskScopePolicy.scopeFor("yes go ahead", prev, 30_000)
        assertNull(TaskScopePolicy.blockReason(scope, "open_app"))
        assertNotNull(TaskScopePolicy.blockReason("yes go ahead", "open_app"))
    }

    @Test fun staleOrLongFollowUpDoesNotInherit() {
        val prev = "open WhatsApp and send kingi jimzy good morning"
        assertEquals("yes go ahead", TaskScopePolicy.scopeFor("yes go ahead", prev, 11 * 60 * 1000L))
        val long = "tell me a long story about the history of the roman empire please now"
        assertEquals(long, TaskScopePolicy.scopeFor(long, prev, 1000))
    }

    @Test fun ordinaryActionVerbsInsideExplanationsDoNotTriggerAutomation() {
        assertEquals(TaskMode.CHAT, TaskScopePolicy.modeFor("I want to write a poem"))
        assertEquals(TaskMode.CHAT, TaskScopePolicy.modeFor("Explain how to open a file"))
        assertEquals(TaskMode.CHAT, TaskScopePolicy.modeFor("Show me how computer memory works"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Can you please open WhatsApp"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Write a message to Amma on WhatsApp"))
    }

    @Test fun localSearchRequestsRouteToReadOnlyResearchTools() {
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("Find me a restaurant near me"))
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("What is the weather forecast in Lagos"))
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("I want to search the web for Android accessibility changes"))
        assertEquals(TaskMode.RESEARCH, TaskScopePolicy.modeFor("Search for restaurants near me"))
        assertNull(TaskScopePolicy.blockReason("Find me a restaurant near me", "web_search"))
        assertNull(TaskScopePolicy.blockReason("Search for restaurants near me", "web_search"))
    }

    @Test fun ordinaryMentionsOfMemoryAndTasksStayInChat() {
        listOf(
            "I remember when we first talked",
            "I have a task about learning how memory works",
            "I forgot my card at home",
            "Tell me how computer memory works"
        ).forEach { assertEquals("$it should stay in chat", TaskMode.CHAT, TaskScopePolicy.modeFor(it)) }
    }

    @Test fun explicitMemoryAndTaskCommandsRouteToAutomation() {
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Remember that I prefer solid graphite UI"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Create a task to call Amma tomorrow"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Show my tasks"))
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("Forget the note about my old project"))
    }

    @Test fun openWhatsAppRequestIsPhoneAutomation() {
        assertEquals(TaskMode.PHONE_AUTOMATION, TaskScopePolicy.modeFor("open WhatsApp and send kingi jimzy this message good morning chams"))
    }
    @Test fun planModeBlocksWriteToolsAtDispatchBoundary() {
        val blocked = AgentDispatchPolicy.blockReason(
            request = "Open WhatsApp",
            tool = "open_app",
            args = org.json.JSONObject().put("name", "WhatsApp"),
            executionMode = "PLAN"
        )
        assertNotNull(blocked)
        assertTrue(blocked!!.contains("Plan mode"))
    }

    @Test fun planModeStillAllowsReadOnlyToolsWithinScope() {
        val result = AgentDispatchPolicy.blockReason(
            request = "Open WhatsApp and read my last chat",
            tool = "read_screen",
            args = org.json.JSONObject(),
            executionMode = "PLAN"
        )
        assertNull(result)
    }

    @Test fun fullModeDoesNotBypassTaskScope() {
        val result = AgentDispatchPolicy.blockReason(
            request = "Explain how Bluetooth works",
            tool = "open_app",
            args = org.json.JSONObject().put("name", "WhatsApp"),
            executionMode = "FULL"
        )
        assertNotNull(result)
    }

    @Test fun readOnlyTradingBlocksMutationsAcrossCommonWordings() {
        listOf(
            "buy 20 usdt of BTC stop 60000",
            "CLOSE all positions",
            "exit my trades",
            "flatten everything",
            "panic",
            "cancel every open orders",
            "switch to live account",
            "enable real trading",
            "set real money mode",
            "turn on order placement"
        ).forEach { assertTrue("Should block: $it", TradingCommandPolicy.isMutatingCommand(it)) }
    }

    @Test fun readOnlyTradingAllowsAnalysisAndOrdinaryDiscussion() {
        listOf(
            "analyze BTC",
            "what is the price of ETH",
            "show my watchlist",
            "explain what a stop loss is",
            "how do I learn trading"
        ).forEach { assertFalse("Should allow: $it", TradingCommandPolicy.isMutatingCommand(it)) }
    }

}
