package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceSectionTest {
    @Test fun doesNotRepeatToolSignaturesButListsWhatIsLoadable() {
        val text = ToolRegistry.guidanceSection("write a python calculator", "", setOf("capability_load"))
        assertFalse(text.contains("Tools:\n"))
        assertTrue(text.contains("capability_load before use"))
        assertFalse(text.substringAfter("capability_load before use").substringBefore("\n").contains("capability_load,"))
        assertTrue(text.contains("USER-VISIBLE FILE SAVE RULE"))
        assertTrue(text.contains("NEVER SAY"))
    }

    @Test fun isMuchSmallerThanTheFullPromptSection() {
        val full = ToolRegistry.promptSection("hello", "").length
        val lean = ToolRegistry.guidanceSection("hello", "", ToolRegistry.focusedToolsFor("hello").map { it.name }).length
        assertTrue("lean=$lean full=$full", lean < full)
    }
}
