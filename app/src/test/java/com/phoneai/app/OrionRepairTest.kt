package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRepairTest {
    @Test fun singleValuedFactsAreReplacedListsAreNot() {
        assertTrue(OrionFactStore.isSingleValued("lives in"))
        assertTrue(OrionFactStore.isSingleValued("works_as"))
        assertTrue(OrionFactStore.isSingleValued("favorite color"))
        assertFalse(OrionFactStore.isSingleValued("likes"))
        assertFalse(OrionFactStore.isSingleValued("owns"))
        assertFalse(OrionFactStore.isSingleValued("plays"))
        assertFalse(OrionFactStore.isSingleValued(""))
    }

    @Test fun researchArgumentAliasesAreAccepted() {
        val f = { j: String, names: Array<String> -> OrionResearchCapabilityExecutor.firstArg(JSONObject(j), *names) }
        assertEquals("https://x.dev", f("{\"link\":\"https://x.dev\"}", arrayOf("url", "link")))
        assertEquals("cats", f("{\"q\":\" cats \"}", arrayOf("query", "q")))
        assertEquals("", f("{}", arrayOf("url", "link")))
    }

    @Test fun discoveryToolsAreNeverDirectlyDispatchable() {
        assertFalse(ToolRegistry.isImplemented("capability_load"))
        assertFalse(ToolRegistry.isImplemented("capability_search"))
        assertFalse(ToolRegistry.isImplemented("no_such_tool_xyz"))
        assertTrue(ToolRegistry.isImplemented("brain"))
    }

    @Test fun clickDescriptionTellsTheModelToPassTheLabel() {
        assertTrue(ToolRegistry.find("click")!!.promptLine.contains("text?"))
    }
}
