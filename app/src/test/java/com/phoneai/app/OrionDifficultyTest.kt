package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionDifficultyTest {
    @Test fun trivialTurnsAreFastAndHardOnesAreDeepOnAnyProvider() {
        assertEquals(ModelRouter.Tier.FAST, OrionDifficulty.tierFor("hi"))
        assertEquals(ModelRouter.Tier.FAST, OrionDifficulty.tierFor("thanks, that worked"))
        val hard = "Research the top three competitors, then compare their pricing, then design a plan and debug why our churn analysis fails, step by step."
        assertEquals(ModelRouter.Tier.DEEP, OrionDifficulty.tierFor(hard))
        assertEquals(ModelRouter.Tier.CHAT, OrionDifficulty.tierFor("open the camera app and take a look at the weather for me today please"))
    }

    @Test fun aCorrectionAfterAMissEscalates() {
        val plain = OrionDifficulty.score("check the build")
        assertTrue(OrionDifficulty.score("check the build", correction = true) >= plain + 2)
    }

    @Test fun modelPowerReadsSizeAndFamilyWithoutConfusingGemini() {
        assertTrue(OrionDifficulty.power("llama-3.1-405b") > OrionDifficulty.power("llama-3.3-70b-versatile"))
        assertTrue(OrionDifficulty.power("llama-3.3-70b-versatile") > OrionDifficulty.power("llama-3.1-8b-instant"))
        assertTrue(OrionDifficulty.power("gemini-2.5-pro") > OrionDifficulty.power("gemini-2.5-flash"))
        assertTrue("gemini must not read as 'mini'", OrionDifficulty.power("gemini-2.5-pro") > 50.0)
    }

    private val catalogue = listOf("llama-3.1-8b-instant", "llama-3.3-70b-versatile", "llama-3.1-405b", "text-embedding-3", "whisper-large-v3")

    @Test fun deepPicksAClearlyStrongerChatModelAndFastAClearlyLighterOne() {
        assertEquals("llama-3.1-405b", OrionDifficulty.pick(ModelRouter.Tier.DEEP, "llama-3.3-70b-versatile", catalogue))
        assertEquals("llama-3.1-8b-instant", OrionDifficulty.pick(ModelRouter.Tier.FAST, "llama-3.3-70b-versatile", catalogue))
    }

    @Test fun neverPicksNonChatModelsGuessesOrNoOpSwaps() {
        assertNull(OrionDifficulty.pick(ModelRouter.Tier.CHAT, "llama-3.3-70b-versatile", catalogue))
        assertNull(OrionDifficulty.pick(ModelRouter.Tier.DEEP, "llama-3.1-405b", catalogue))
        assertNull(OrionDifficulty.pick(ModelRouter.Tier.DEEP, "x", emptyList()))
        assertNull(OrionDifficulty.pick(ModelRouter.Tier.FAST, "whisper-large-v3", listOf("text-embedding-3", "whisper-large-v3")))
    }
}
