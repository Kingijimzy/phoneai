package com.phoneai.app

import com.phoneai.app.CapabilityRegistry.Facts
import com.phoneai.app.CapabilityRegistry.Health
import com.phoneai.app.CapabilityRegistry.Source
import com.phoneai.app.CapabilityRegistry.State
import org.junit.Assert.*
import org.junit.Test

class CapabilityRegistryTest {
    private fun base(id: String = "gmail", health: Health = Health.OK) =
        Facts(id = id, title = "Gmail", source = Source.EXTERNAL_CONNECTED, health = health)

    @Test fun healthyEnabledCapabilityIsAvailable() {
        val e = CapabilityRegistry.evaluate(base())
        assertEquals(State.AVAILABLE, e.state)
        assertTrue(e.usable)
    }

    @Test fun eachBlockingStateIsReportedAndNotUsable() {
        val cases = mapOf(
            State.UNSUPPORTED to base().copy(supported = false),
            State.DISABLED to base().copy(enabledByUser = false),
            State.PERMISSION_REQUIRED to base().copy(permissionNeeded = "grant notification access"),
            State.SETUP_REQUIRED to base().copy(setupNeeded = "connect a token"),
            State.MISSING_DEPENDENCY to base().copy(missingDependencies = listOf("python3")),
            State.TEMPORARILY_UNAVAILABLE to base().copy(temporarilyDown = "device offline"),
            State.FAILED_HEALTH to base(health = Health.FAILED)
        )
        cases.forEach { (expected, facts) ->
            val e = CapabilityRegistry.evaluate(facts)
            assertEquals(expected, e.state)
            assertFalse("$expected must not be usable", e.usable)
            assertTrue("$expected needs a reason", e.reason.isNotBlank())
        }
    }

    @Test fun precedenceFollowsTheDocumentedOrder() {
        val e = CapabilityRegistry.evaluate(base().copy(enabledByUser = false, setupNeeded = "x", health = Health.FAILED))
        assertEquals("a disabled capability reports disabled, not its other problems", State.DISABLED, e.state)
    }

    @Test fun untestedCapabilityIsLimitedNotPlainAvailable() {
        val e = CapabilityRegistry.evaluate(base(health = Health.UNKNOWN))
        assertEquals(State.AVAILABLE_LIMITED, e.state)
        assertTrue(e.usable)
        assertEquals("not tested yet on this device", e.reason)
    }

    @Test fun degradedHealthAndLimitationsStayUsableWithTheirReason() {
        val e = CapabilityRegistry.evaluate(base(health = Health.DEGRADED).copy(limitation = "slow on mobile data"))
        assertEquals(State.AVAILABLE_LIMITED, e.state)
        assertEquals("slow on mobile data", e.reason)
    }

    @Test fun enablingMakesACapabilityOfferableWithoutAnyOtherChange() {
        val off = base().copy(enabledByUser = false)
        assertTrue(CapabilityRegistry.offerable(listOf(CapabilityRegistry.evaluate(off))).isEmpty())
        val on = off.copy(enabledByUser = true)
        assertEquals(listOf("gmail"), CapabilityRegistry.offerable(listOf(CapabilityRegistry.evaluate(on))).map { it.facts.id })
    }

    @Test fun disconnectingRemovesTheCapabilityFromOffers() {
        val connected = CapabilityRegistry.evaluate(base())
        val disconnected = CapabilityRegistry.evaluate(base().copy(setupNeeded = "connect a token"))
        assertEquals(1, CapabilityRegistry.offerable(listOf(connected)).size)
        assertTrue(CapabilityRegistry.offerable(listOf(disconnected)).isEmpty())
    }

    @Test fun blockedLinesExplainEveryNonUsableCapabilityAndNothingUsable() {
        val entries = listOf(
            CapabilityRegistry.evaluate(base("ok")),
            CapabilityRegistry.evaluate(base("github").copy(setupNeeded = "add a token in Connected services"))
        )
        val text = CapabilityRegistry.blockedLines(entries)
        assertFalse(text.contains("(ok)"))
        assertTrue(text.contains("(github): setup_required — add a token in Connected services"))
    }
}
