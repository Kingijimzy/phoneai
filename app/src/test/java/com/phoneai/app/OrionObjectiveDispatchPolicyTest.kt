package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionObjectiveDispatchPolicyTest {
    @Test fun plannerBookkeepingDoesNotRequireAnExecutionObjective() {
        assertFalse(OrionObjectiveGraph.requiresObjectiveDispatch("task_add"))
    }

    @Test fun realCapabilitiesRemainBehindObjectiveDependencyGate() {
        assertTrue(OrionObjectiveGraph.requiresObjectiveDispatch("python_exec"))
        assertTrue(OrionObjectiveGraph.requiresObjectiveDispatch("phone_battery"))
        assertTrue(OrionObjectiveGraph.requiresObjectiveDispatch("save_file"))
    }
}
