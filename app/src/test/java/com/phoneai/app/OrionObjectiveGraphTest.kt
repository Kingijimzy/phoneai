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

    @Test fun plannerCannotInjectCompletedStatusOrEvidence() {
        val result = OrionObjectiveGraph.parse(
            """{"objectives":[{"id":"a","title":"Pretend done","status":"COMPLETE","evidence":"model says so"}]}""",
            "Test trust boundary"
        ) as OrionObjectiveGraph.ParseResult.Valid
        assertEquals(OrionObjectiveGraph.Status.PENDING, result.graph.nodes.single().status)
        assertEquals("", result.graph.nodes.single().evidence)
    }

    @Test fun dependencyGateBlocksDependentUntilPrerequisiteComplete() {
        val graph = OrionObjectiveGraph.parse(
            """{"objectives":[{"id":"prepare","title":"Prepare"},{"id":"release","title":"Release","dependsOn":["prepare"]}]}""",
            "Release safely"
        ) as OrionObjectiveGraph.ParseResult.Valid
        assertNotNull(OrionObjectiveGraph.canExecute(graph.graph, "release"))
        val unlocked = OrionObjectiveGraph.updateStatus(graph.graph, "prepare", OrionObjectiveGraph.Status.COMPLETE, "local receipt id")
        assertNull(OrionObjectiveGraph.canExecute(unlocked, "release"))
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

    @Test fun resolvesModelGeneratedAliasToTheOnlyPersistedObjective() {
        val graph = OrionObjectiveGraph.Graph(
            objective = "Write and execute a calculator",
            nodes = listOf(OrionObjectiveGraph.Objective(id = "main", title = "Complete calculator workflow"))
        )
        assertEquals("main", OrionObjectiveGraph.resolveDispatchId(graph, "calc_write"))
        assertEquals("main", OrionObjectiveGraph.resolveDispatchId(graph, ""))
    }

    @Test fun multiObjectiveDispatchRequiresAnExactKnownId() {
        val graph = OrionObjectiveGraph.Graph(
            objective = "Research then summarize",
            nodes = listOf(
                OrionObjectiveGraph.Objective(id = "research", title = "Research"),
                OrionObjectiveGraph.Objective(id = "summarize", title = "Summarize", dependsOn = listOf("research"))
            )
        )
        assertEquals("research", OrionObjectiveGraph.resolveDispatchId(graph, "research"))
        assertEquals("research", OrionObjectiveGraph.resolveDispatchId(graph, "research_step"))
        assertEquals("research", OrionObjectiveGraph.resolveDispatchId(graph, ""))
    }
    @Test fun invalidPlannerAliasResolvesToTheOnlyReadyObjectiveInASequentialGraph() {
        val graph = OrionObjectiveGraph.Graph(
            objective = "Research, save report, create task, save memory, verify",
            nodes = listOf(
                OrionObjectiveGraph.Objective("research", "Research current AI developments"),
                OrionObjectiveGraph.Objective("save_report", "Save the report", listOf("research")),
                OrionObjectiveGraph.Objective("create_task", "Create a reminder", listOf("save_report")),
                OrionObjectiveGraph.Objective("save_memory", "Save project memory", listOf("create_task")),
                OrionObjectiveGraph.Objective("verify_all", "Verify all outcomes", listOf("save_memory"))
            )
        )
        assertEquals("research", OrionObjectiveGraph.resolveDispatchId(graph, "and"))
        val next = OrionObjectiveGraph.updateStatus(graph, "research", OrionObjectiveGraph.Status.COMPLETE, "receipt")
        assertEquals("save_report", OrionObjectiveGraph.resolveDispatchId(next, "report_step"))
    }

    @Test fun missingPlannerIdStaysOnTheOnlyRunningObjective() {
        val graph = OrionObjectiveGraph.Graph(
            objective = "Save and verify",
            nodes = listOf(
                OrionObjectiveGraph.Objective("save_report", "Save report", status = OrionObjectiveGraph.Status.RUNNING),
                OrionObjectiveGraph.Objective("verify_all", "Verify report", listOf("save_report"))
            )
        )
        assertEquals("save_report", OrionObjectiveGraph.resolveDispatchId(graph, "wrong_id"))
    }

    @Test fun ambiguousParallelReadyObjectivesDoNotGuess() {
        val graph = OrionObjectiveGraph.Graph(
            objective = "Independent actions",
            nodes = listOf(
                OrionObjectiveGraph.Objective("research", "Research"),
                OrionObjectiveGraph.Objective("memory", "Save memory")
            )
        )
        assertNull(OrionObjectiveGraph.resolveDispatchId(graph, "wrong_id"))
    }

}
