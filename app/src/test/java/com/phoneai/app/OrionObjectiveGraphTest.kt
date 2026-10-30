package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionObjectiveGraphTest {
    @Test fun parsesAndOrdersDependenciesBeforeDependents() {
        val result = OrionObjectiveGraph.parse(
            """{"objectives":[{"id":"deploy","title":"Deploy after validation","dependsOn":["test"]},{"id":"test","title":"Run tests"},{"id":"audit","title":"Audit source"}]}""",
            "Finish the release"
        )
        assertTrue(result is OrionObjectiveGraph.ParseResult.Valid)
        val graph = (result as OrionObjectiveGraph.ParseResult.Valid).graph
        assertEquals(listOf("test", "deploy", "audit"), graph.ordered().map { it.id })
        assertEquals(setOf("test", "audit"), graph.ready().map { it.id }.toSet())
    }

    @Test fun rejectsMissingDependency() {
        val result = OrionObjectiveGraph.parse(
            """{"objectives":[{"id":"build","title":"Build","dependsOn":["compile"]}]}""",
            "Build app"
        )
        assertTrue(result is OrionObjectiveGraph.ParseResult.Invalid)
        assertTrue((result as OrionObjectiveGraph.ParseResult.Invalid).reason.contains("missing objective"))
    }

    @Test fun rejectsDependencyCycles() {
        val result = OrionObjectiveGraph.parse(
            """{"objectives":[{"id":"a","title":"A","dependsOn":["b"]},{"id":"b","title":"B","dependsOn":["a"]}]}""",
            "Cycle"
        )
        assertTrue(result is OrionObjectiveGraph.ParseResult.Invalid)
        assertTrue((result as OrionObjectiveGraph.ParseResult.Invalid).reason.contains("cycle"))
    }

    @Test fun rejectsDuplicateIdsAndOversizedPlans() {
        val duplicate = OrionObjectiveGraph.parse(
            """{"objectives":[{"id":"a","title":"A"},{"id":"a","title":"Again"}]}""", "Duplicate"
        )
        assertTrue(duplicate is OrionObjectiveGraph.ParseResult.Invalid)
        val many = (1..25).joinToString(",") { """{"id":"n$it","title":"Node $it"}""" }
        assertTrue(OrionObjectiveGraph.parse("""{"objectives":[$many]}""", "Too many") is OrionObjectiveGraph.ParseResult.Invalid)
    }

    @Test fun cannotMarkDependentCompleteBeforePrerequisite() {
        val parsed = OrionObjectiveGraph.parse(
            """{"objectives":[{"id":"a","title":"Prerequisite"},{"id":"b","title":"Dependent","dependsOn":["a"]}]}""",
            "Ordered work"
        ) as OrionObjectiveGraph.ParseResult.Valid
        val error = runCatching {
            OrionObjectiveGraph.updateStatus(parsed.graph, "b", OrionObjectiveGraph.Status.COMPLETE, "claimed")
        }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test fun readyNodesUnlockOnlyAfterDependenciesComplete() {
        val parsed = OrionObjectiveGraph.parse(
            """{"objectives":[{"id":"a","title":"First"},{"id":"b","title":"Second","dependsOn":["a"]}]}""",
            "Ordered work"
        ) as OrionObjectiveGraph.ParseResult.Valid
        val updated = OrionObjectiveGraph.updateStatus(parsed.graph, "a", OrionObjectiveGraph.Status.COMPLETE, "test output confirms")
        assertEquals(listOf("b"), updated.ready().map { it.id })
    }
}
