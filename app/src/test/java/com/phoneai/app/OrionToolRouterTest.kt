package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionToolRouterTest {
    private fun names(q: String, stats: Map<String, Triple<Int, Int, Long>> = emptyMap()) = OrionToolRouter.rank(q, ToolRegistry.all, stats).map { it.tool }

    @Test fun imageRequestRoutesToImageGeneration() = assertTrue(names("generate an image of a mountain at sunset").contains("image_generate"))
    @Test fun pythonRequestRoutesToPython() = assertTrue(names("run python code to calculate compound interest").take(3).contains("python_exec"))
    @Test fun nonsenseRoutesNowhere() = assertTrue(OrionToolRouter.rank("zzqxv wwkkj", ToolRegistry.all, emptyMap()).isEmpty())
    @Test fun measuredFailureLowersAToolsRank() {
        val base = OrionToolRouter.rank("generate an image of a cat", ToolRegistry.all, emptyMap()).first { it.tool == "image_generate" }.score
        val hurt = OrionToolRouter.rank("generate an image of a cat", ToolRegistry.all, mapOf("image_generate" to Triple(0, 6, 0L))).firstOrNull { it.tool == "image_generate" }?.score ?: 0.0
        assertTrue(hurt < base)
    }
    @Test fun reliabilityRisesWithSuccessAndFallsWithFailure() {
        assertTrue(OrionToolRouter.reliability(9, 0) > OrionToolRouter.reliability(0, 0))
        assertTrue(OrionToolRouter.reliability(0, 9) < OrionToolRouter.reliability(0, 0))
    }
}
