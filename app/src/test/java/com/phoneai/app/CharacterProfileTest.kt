package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterProfileTest {
    @Test fun everySelectableCharacterCarriesTheSameAuthorityInvariants() {
        val invariant = CharacterProfile.BEHAVIOUR_INVARIANTS
        assertTrue(invariant.contains("presentation only"))
        assertTrue(invariant.contains("may grant permissions"))
        assertTrue(invariant.contains("bypass confirmation"))
        assertTrue(invariant.contains("trading restrictions"))
        assertTrue(invariant.contains("user-control rule"))

        CharacterProfile.all.forEach { profile ->
            val instruction = CharacterProfile.systemInstruction(profile.id)
            assertTrue("Missing invariant for ${profile.id}", instruction.contains(invariant))
            assertTrue("Missing profile label for ${profile.id}", instruction.contains(profile.label))
        }
    }

    @Test fun unknownProfileFallsBackToOrionWithoutDroppingInvariants() {
        assertEquals(CharacterProfile.all.first(), CharacterProfile.get("not-a-profile"))
        assertTrue(CharacterProfile.systemInstruction("not-a-profile")
            .contains(CharacterProfile.BEHAVIOUR_INVARIANTS))
    }

    @Test fun changingCharacterChangesVoiceGuidanceButNotSafetyInvariants() {
        val calm = CharacterProfile.systemInstruction("orion")
        val operator = CharacterProfile.systemInstruction("atlas")
        assertFalse(calm == operator)
        assertTrue(calm.endsWith(CharacterProfile.BEHAVIOUR_INVARIANTS))
        assertTrue(operator.endsWith(CharacterProfile.BEHAVIOUR_INVARIANTS))
    }
}
