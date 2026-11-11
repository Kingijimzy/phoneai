package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionGraspProtocolTest {
    @Test fun executiveCycleHasStableFivePhaseOrder() {
        assertEquals(
            listOf(
                OrionGraspProtocol.Phase.GROUND,
                OrionGraspProtocol.Phase.RETRIEVE,
                OrionGraspProtocol.Phase.ASSESS,
                OrionGraspProtocol.Phase.SELECT,
                OrionGraspProtocol.Phase.PERFORM
            ),
            OrionGraspProtocol.orderedPhases
        )
    }

    @Test fun protocolRequiresObservationVerificationAndDurableReceipts() {
        val contract = OrionGraspProtocol.contextBlock()
        assertTrue(contract.contains("full current message"))
        assertTrue(contract.contains("MMAG layers"))
        assertTrue(contract.contains("live capability/connection/permission state"))
        assertTrue(contract.contains("observe its actual result"))
        assertTrue(contract.contains("persist a sanitized receipt"))
        assertTrue(contract.contains("Never equate a plan with an invocation"))
    }
}
