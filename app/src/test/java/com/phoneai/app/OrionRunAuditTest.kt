package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRunAuditTest {
    private fun step(id: Long, tool: String, failed: Boolean, out: String = "", ms: Long = 500) =
        OrionRunLog.Step(id, tool, tool, "", null, 1000L, 1000L + ms,
            if (failed) OrionRunLog.Status.FAILED else OrionRunLog.Status.DONE, out)

    @Test fun replayOfTheGithubWanderingRunIsFlagged() {
        val run = listOf(
            step(1, "github_account_read", true, "error: github 404 Not Found"),
            step(2, "live_web_search", false),
            step(3, "browser_navigate", false))
        val f = OrionRunAudit.findings(run)
        assertTrue(f.toString(), f.any { it.startsWith("WANDER") })
    }

    @Test fun threeFailuresInARowIsALoopAndACleanRunHasNoProblems() {
        val loop = (1L..3L).map { step(it, "shell", true, "error: nope") }
        assertTrue(OrionRunAudit.findings(loop).any { it.startsWith("LOOP") })
        assertEquals(emptyList<String>(), OrionRunAudit.findings(listOf(step(1, "shell", false), step(2, "recall", false))))
    }

    @Test fun slowStepsAreNamed() {
        assertTrue(OrionRunAudit.findings(listOf(step(1, "python_exec", false, ms = 45_000))).any { it.startsWith("SLOW") })
    }

    @Test fun diagnosticsCarryTheAuditAndEvolutionSections() {
        val r = OrionDiagnostics.report(listOf(step(1, "shell", false)), mapOf("shell" to Triple(1, 0, 10L)), 5000L)
        assertTrue(r.contains("PROBLEMS FOUND") && r.contains("EVOLUTION REVIEW"))
    }
}

class OrionEvolutionTest {
    @Test fun neverWorkingToolsAreReplacedAndProvenOnesKept() {
        val v = OrionEvolution.review(mapOf(
            "weather" to Triple(0, 4, 100L),
            "ext_calc" to Triple(0, 3, 100L),
            "recall" to Triple(8, 0, 800L),
            "slow" to Triple(6, 0, 6 * 30_000L),
            "new" to Triple(1, 0, 10L))).associateBy { it.tool }
        assertTrue(v.getValue("weather").advice.contains("forge a replacement"))
        assertTrue(v.getValue("ext_calc").advice.contains("retire or rebuild"))
        assertTrue(v.getValue("recall").advice.contains("proven"))
        assertTrue(v.getValue("slow").advice.contains("slow"))
        assertTrue("new" !in v)
    }

    @Test fun emptyRecordSaysSoInsteadOfInventing() {
        assertTrue(OrionEvolution.report(emptyMap()).contains("not enough measured use"))
    }
}

class OrionNudgeAndSceneTest {
    @Test fun failingToolsGetASelfFixNudgeAndHealthyOnesGetNone() {
        assertTrue(OrionEvolution.nudge(mapOf("weather" to Triple(0, 4, 10L))).contains("extension_forge"))
        assertEquals("", OrionEvolution.nudge(mapOf("recall" to Triple(9, 0, 10L))))
    }

    @Test fun codingSceneBringsMusicAndFileDisplay() {
        val t = OrionScenes.toolsFor("I feel like coding")
        assertTrue("play_media" in t && "media_control" in t && "show_file" in t)
        assertTrue(OrionScenes.brief("I feel like coding").contains("coding music"))
    }

    @Test fun personaTellsOrionToShowWhatHeBuilds() {
        assertTrue(OrionPersona.OPERATING.contains("show it"))
    }
}
