package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionSelfPatchTest {
    @Test fun refusesTruncatedOrEmptyPatches() {
        assertTrue(OrionSelfEdit.patchProblems("A.kt", "").isNotEmpty())
        assertTrue(OrionSelfEdit.patchProblems("A.kt", "package x\n// ... rest of file unchanged").isNotEmpty())
        assertTrue(OrionSelfEdit.patchProblems("A.kt", "class A").any { it.contains("package") })
        assertTrue(OrionSelfEdit.patchProblems("a.json", "{bad").isNotEmpty())
        assertTrue(OrionSelfEdit.patchProblems("A.kt", "package x\nclass A").isEmpty())
        assertTrue(OrionSelfEdit.patchProblems("a.json", "{\"a\":1}").isEmpty())
    }

    @Test fun mergeGateNeedsEveryCheckGreen() {
        fun runs(vararg r: Pair<String, String>) = "{\"check_runs\":[" + r.joinToString(",") { "{\"name\":\"n\",\"status\":\"${it.first}\",\"conclusion\":\"${it.second}\"}" } + "]}"
        assertEquals("OK", OrionSelfEdit.ciVerdict(runs("completed" to "success")))
        assertTrue(OrionSelfEdit.ciVerdict(runs("completed" to "success", "in_progress" to "")).contains("running"))
        assertTrue(OrionSelfEdit.ciVerdict(runs("completed" to "failure")).startsWith("CI failed"))
        assertTrue(OrionSelfEdit.ciVerdict("{\"check_runs\":[]}").contains("no checks"))
        assertTrue(OrionSelfEdit.ciVerdict("garbage").contains("readable"))
    }

    @Test fun turnSnapshotIsReusedWhateverTheWording() {
        val s = OrionTurnContext.Snapshot("open spotify", "ctx", 1_000L)
        assertTrue(OrionTurnContext.reusable(s, "open spotify", 2_000L))
    }
}
