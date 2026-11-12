package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionRouterTest {
    @Test fun parsesEachRoute() {
        assertEquals(OrionRouter.Kind.CHAT, OrionRouter.parse("{\"route\":\"chat\"}")!!.kind)
        assertEquals(OrionRouter.Kind.ACT, OrionRouter.parse("```json\n{\"route\":\"ACT\"}\n```")!!.kind)
        assertEquals(OrionRouter.Kind.CONTINUE, OrionRouter.parse("sure: {\"route\":\"continue\",\"plan\":false}")!!.kind)
        assertEquals(OrionRouter.Kind.LOOKUP, OrionRouter.parse("{\"route\":\"lookup\"}")!!.kind)
    }
    @Test fun planImpliesPlanFlagAndSequential() {
        val d = OrionRouter.parse("{\"route\":\"plan\",\"sequential\":true}")!!
        assertTrue(d.plan); assertTrue(d.sequential); assertTrue(d.needsTools); assertEquals("model", d.source)
    }
    @Test fun malformedOrUnknownIsRejectedSoFallbackRuns() {
        assertNull(OrionRouter.parse("I think this is chat"))
        assertNull(OrionRouter.parse("{\"route\":\"banana\"}"))
        assertNull(OrionRouter.parse("{broken"))
    }
    @Test fun chatDoesNotNeedTools() = assertFalse(OrionRouter.parse("{\"route\":\"chat\"}")!!.needsTools)
    @Test fun messageCarriesContextAndCapabilities() {
        val m = OrionRouter.userMessage("do the other one", listOf("user" to "make a tool", "assistant" to "done"), "build tool X", "phone_control=unavailable")
        assertTrue(m.contains("build tool X")); assertTrue(m.contains("phone_control=unavailable")); assertTrue(m.contains("LATEST MESSAGE: do the other one"))
    }
    @Test fun messagePreservesFullConversationEnvironmentAndLatestRequest() {
        val turns = (1..12).map { "user" to "turn-$it " + "x".repeat(260) }
        val environment = "registered tools: " + "tool,".repeat(180) + "last-capability"
        val objective = "active objective " + "objective-detail ".repeat(40)
        val command = "latest request " + "latest-detail ".repeat(100)
        val message = OrionRouter.userMessage(command, turns, objective, environment)
        assertTrue(message.contains("turn-1"))
        assertTrue(message.contains("turn-12"))
        assertTrue(message.contains("last-capability"))
        assertTrue(message.contains("objective-detail"))
        assertTrue(message.contains("latest-detail"))
    }

    @Test fun conversationRestoreDoesNotApplyAnArbitraryTurnOrCharacterLimit() {
        val turns = (1..60).map { "user" to "turn-$it " + "detail ".repeat(900) }
        val restored = ConversationContextPolicy.recent(turns)
        assertEquals(60, restored.size)
        assertTrue(restored.first().second.contains("turn-1"))
        assertTrue(restored.first().second.length > 4000)
        assertTrue(restored.last().second.contains("turn-60"))
    }

}
