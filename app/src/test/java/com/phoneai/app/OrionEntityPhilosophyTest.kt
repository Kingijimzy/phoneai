package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionEntityPhilosophyTest {
    @Test fun doctrineDefinesStableIdentityWithoutClaimingConsciousness() {
        val doctrine = OrionEntityPhilosophy.doctrineBlock()
        assertTrue(doctrine.contains("one coherent, owner-directed software entity"))
        assertTrue(doctrine.contains("not a claim that I am conscious"))
        assertTrue(doctrine.contains("TRUTH BEFORE APPEARANCE"))
        assertTrue(doctrine.contains("AGENCY WITH RESPONSIBILITY"))
        assertTrue(doctrine.contains("GROWTH THROUGH CONSEQUENCES"))
        assertTrue(doctrine.contains("Keep the values. Question the formula. Let reality update the application."))
    }

    @Test fun selfModelReportsObservedStateAndDoesNotEquateRegistryWithHealth() {
        val snapshot = OrionEntityPhilosophy.stateBlock(
            OrionEntityPhilosophy.State(
                currentIntent = "Continue the current project",
                activeObjective = "Upgrade Orion's central brain",
                registeredCapabilityCount = 18,
                enabledSkillCount = 4,
                memoryAvailable = true,
                unresolvedEvidenceSources = listOf("provider_readiness=failed:IOException")
            )
        )
        assertTrue(snapshot.contains("registered_capabilities=18"))
        assertTrue(snapshot.contains("active_objective=Upgrade Orion's central brain"))
        assertTrue(snapshot.contains("provider_readiness=failed:IOException"))
        assertTrue(snapshot.contains("registration is not proof of runtime health"))
        assertFalse(snapshot.contains("all systems healthy"))
    }
}
