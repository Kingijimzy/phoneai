package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedToolsTest {
    private val none = emptyMap<String, Triple<Int, Int, Long>>()

    @Test fun surfaceIsSmallAndKeepsDiscovery() {
        val names = ToolRegistry.focusedToolsFor("hello", emptySet(), none).map { it.name }
        assertTrue("got ${names.size}", names.size <= ToolRegistry.FOCUSED_LIMIT)
        assertTrue("capability_search" in names && "capability_load" in names && "ask_user" in names)
        assertTrue(names.size < ToolRegistry.nativeToolsFor("hello").size)
    }

    @Test fun requestFitBringsTheRightToolIn() {
        val names = ToolRegistry.focusedToolsFor("generate an image of a lion", emptySet(), none).map { it.name }
        assertTrue("image_generate" in names)
    }

    @Test fun provenToolsStayInReachAndFailingOnesDoNot() {
        val stats = mapOf("weather" to Triple(9, 0, 0L), "sms" to Triple(1, 8, 0L))
        val names = ToolRegistry.focusedToolsFor("hello", emptySet(), stats).map { it.name }
        assertTrue("weather" in names)
        assertFalse("sms" in names)
    }

    @Test fun previousToolGroupCarriesForward() {
        val names = ToolRegistry.focusedToolsFor("continue", setOf("browser_click_verify"), none).map { it.name }
        assertTrue("browser_click_verify" in names)
        assertEquals(names.size, names.distinct().size)
    }
}
