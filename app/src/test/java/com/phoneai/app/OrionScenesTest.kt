package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionScenesTest {
    @Test fun moodPhrasesSelectTheRightScene() {
        assertEquals("coding", OrionScenes.detect("I feel like coding today")?.id)
        assertEquals("coding", OrionScenes.detect("let's build something")?.id)
        assertEquals("trading", OrionScenes.detect("time to trade")?.id)
        assertEquals("focus", OrionScenes.detect("I need to focus")?.id)
        assertEquals("morning", OrionScenes.detect("good morning orion")?.id)
        assertEquals("tired", OrionScenes.detect("I'm so tired of this")?.id)
    }

    @Test fun ordinaryRequestsAreNotScenes() {
        assertNull(OrionScenes.detect("what is the price of bitcoin"))
        assertNull(OrionScenes.detect("fix the build error in Agent.kt"))
        assertNull(OrionScenes.detect(null))
        assertEquals("", OrionScenes.brief("hello"))
    }

    @Test fun sceneToolsJoinTheFocusedSurfaceAndBriefIsConcrete() {
        val names = ToolRegistry.focusedToolsFor("I feel like coding", emptySet(), emptyMap()).map { it.name }
        assertTrue("devspace" in names)
        assertTrue(OrionScenes.brief("I feel like coding").contains("Do not ask what to work on"))
    }
}
