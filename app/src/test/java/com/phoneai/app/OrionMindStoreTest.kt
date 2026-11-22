package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionMindStoreTest {
    private lateinit var c: Context

    @Before fun setUp() {
        c = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("orion_mind", Context.MODE_PRIVATE).edit().clear().commit()
        OrionMindStore.sync(c)
    }

    @After fun tearDown() {
        c.getSharedPreferences("orion_mind", Context.MODE_PRIVATE).edit().clear().commit()
        OrionMindStore.sync(c)
    }

    @Test fun defaultsComeFromCodeAndEditsOverrideThenReset() {
        assertEquals(OrionPersona.CHARACTER, OrionMindStore.character(c))
        OrionMindStore.setCharacter(c, "  I am blunt and brief.  ")
        assertEquals("I am blunt and brief.", OrionMindStore.character(c))
        OrionMindStore.setCharacter(c, null)
        assertEquals(OrionPersona.CHARACTER, OrionMindStore.character(c))
    }

    @Test fun ownerRulesBecomeAPromptBlockOnlyWhenPresent() {
        assertEquals("", OrionMindStore.rulesBlock(c))
        OrionMindStore.setOwnerRules(c, "Result first.")
        assertTrue(OrionMindStore.rulesBlock(c).contains("Result first."))
        OrionMindStore.setOwnerRules(c, "")
        assertEquals("", OrionMindStore.rulesBlock(c))
    }

    @Test fun textIsBounded() {
        OrionMindStore.setOwnerRules(c, "x".repeat(10_000))
        assertEquals(OrionMindStore.MAX_TEXT, OrionMindStore.ownerRules(c).length)
    }

    @Test fun switchedOffSceneStopsMatchingAndEditedBriefIsUsed() {
        assertEquals("coding", OrionScenes.detect("I feel like coding")?.id)
        OrionMindStore.setSceneEnabled(c, "coding", false)
        assertNull(OrionScenes.detect("I feel like coding"))
        OrionMindStore.setSceneEnabled(c, "coding", true)
        OrionMindStore.setSceneBrief(c, "coding", "Open the repo and say nothing else.")
        assertEquals("Open the repo and say nothing else.", OrionScenes.brief("I feel like coding"))
        OrionMindStore.setSceneBrief(c, "coding", null)
        assertTrue(OrionScenes.brief("I feel like coding").contains("Do not ask what to work on"))
        assertFalse(OrionScenes.disabled.contains("coding"))
    }
}
