package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionIdentityUnificationTest {
    private lateinit var c: Context

    @Before fun setUp() {
        c = RuntimeEnvironment.getApplication()
        MemoryStore.clear(c)
        OrionKnowledgeGraph.clear(c)
        KnowledgeGraph.clear(c)
        c.getSharedPreferences("orion_mind", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        MemoryStore.clear(c)
        OrionKnowledgeGraph.clear(c)
        KnowledgeGraph.clear(c)
    }

    @Test fun approvedMemoriesAlsoReachTheKnowledgeGraphPageNotJustManualTeaching() {
        assertTrue(KnowledgeGraph.all(c).isEmpty())
        MemoryStore.addOwnerConfirmed(c, "general", "user preference: dark themes")
        assertTrue(KnowledgeGraph.all(c).any { it.b.contains("dark themes") })
    }

    @Test fun identityPageAndMindPageEditTheSameCharacterText() {
        val before = OrionMindStore.character(c)
        assertTrue(before.contains("YOUR CHARACTER"))
        OrionMindStore.setCharacter(c, "Terse and dry. Nothing else.")
        assertEquals("Terse and dry. Nothing else.", OrionMindStore.character(c))
        // Clearing it must fall back to the one built-in default, not a second stale copy.
        OrionMindStore.setCharacter(c, "")
        assertEquals(before, OrionMindStore.character(c))
    }

    @Test fun characterProfileNoLongerInjectsASecondPhilosophyField() {
        Prefs.setOrionEntityDescription(c, "a calm partner")
        val instruction = CharacterProfile.systemInstruction("orion", c)
        assertTrue(instruction.contains("a calm partner"))
        assertFalse(instruction.contains("OWNER-EDITED PHILOSOPHY"))
    }
}
