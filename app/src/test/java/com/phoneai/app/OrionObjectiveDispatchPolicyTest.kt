package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionObjectiveDispatchPolicyTest {
    @Test fun plannerBookkeepingDoesNotRequireAnExecutionObjective() {
        assertFalse(OrionObjectiveGraph.requiresObjectiveDispatch("task_add"))
    }

    @Test fun toolMismatchMapsToUniqueReadySemanticObjective() {
        val graph = OrionObjectiveGraph.Graph("acceptance", listOf(
            OrionObjectiveGraph.Objective("task_created", "Create task", status = OrionObjectiveGraph.Status.COMPLETE),
            OrionObjectiveGraph.Objective("capability_discovery", "Discover available capabilities", status = OrionObjectiveGraph.Status.COMPLETE),
            OrionObjectiveGraph.Objective("phone_capability", "Read approved phone device status"),
            OrionObjectiveGraph.Objective("controlled_failure", "Run controlled failure recovery")
        ))
        assertEquals("phone_capability", OrionObjectiveGraph.resolveDispatchId(graph, "wrong_model_id", "open_app"))
    }

    @Test fun ambiguousReadyObjectivesAreNotGuessed() {
        val graph = OrionObjectiveGraph.Graph("ambiguous", listOf(
            OrionObjectiveGraph.Objective("first", "First unrelated objective"),
            OrionObjectiveGraph.Objective("second", "Second unrelated objective")
        ))
        assertNull(OrionObjectiveGraph.resolveDispatchId(graph, "invented", "open_app"))
    }

    @Test fun realCapabilitiesRemainBehindObjectiveDependencyGate() {
        assertTrue(OrionObjectiveGraph.requiresObjectiveDispatch("python_exec"))
        assertTrue(OrionObjectiveGraph.requiresObjectiveDispatch("phone_battery"))
        assertTrue(OrionObjectiveGraph.requiresObjectiveDispatch("save_file"))
    }
}
