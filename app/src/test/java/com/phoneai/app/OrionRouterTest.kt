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
}
