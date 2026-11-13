package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterProfileTest {
    @Test fun orionIsTheOnlySelectableConversationalIdentity() {
        assertEquals(1, CharacterProfile.all.size)
        assertEquals("orion", CharacterProfile.all.single().id)
        assertEquals(CharacterProfile.all.single(), CharacterProfile.get("nova"))
        assertEquals(CharacterProfile.all.single(), CharacterProfile.get("atlas"))
        assertEquals(CharacterProfile.all.single(), CharacterProfile.get("not-a-profile"))
    }

    @Test fun everyConversationReceivesTheSamePrimeIdentityAndAuthorityInvariants() {
        assertEquals("orion-prime-4", OrionPrimeDirective.VERSION)
        val instruction = CharacterProfile.systemInstruction("orion")
        assertTrue(instruction.contains(OrionPrimeDirective.SHARED_IDENTITY_RULES))
        assertTrue(instruction.contains(CharacterProfile.BEHAVIOUR_INVARIANTS))
        assertTrue(instruction.contains("ONLY CONVERSATIONAL IDENTITY: Orion"))
        assertTrue(instruction.endsWith(CharacterProfile.BEHAVIOUR_INVARIANTS))
        assertEquals(instruction, CharacterProfile.systemInstruction("nova"))
        assertEquals(instruction, CharacterProfile.systemInstruction("atlas"))
    }
}
