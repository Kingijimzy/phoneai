package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionProactiveGateTest {
    private fun signals(
        hours: Double = 8.0,
        hour: Int = 12,
        active: Double = 30.0,
        battery: Int = 80,
        charging: Boolean = false,
        broken: Int = 0,
        open: Int = 0,
        due: Int = 0,
        added: Int = 0,
        untested: Int = 0,
        count: Int = 0
    ) = OrionProactiveGate.Signals(hours, hour, active, battery, charging, broken, open, due, added, untested, count)

    @Test fun gateOpensForMaterialActionableSignals() {
        assertTrue(OrionProactiveGate.decide(signals(due = 1)).open)
        assertTrue(OrionProactiveGate.decide(signals(broken = 2)).open)
        assertTrue(OrionProactiveGate.decide(signals(added = 2)).open)
    }

    @Test fun gateStaysClosedWhenOwnerIsActiveOrDailyBudgetIsSpent() {
        assertFalse(OrionProactiveGate.decide(signals(active = 1.0, due = 3)).open)
        assertFalse(OrionProactiveGate.decide(signals(count = OrionProactiveGate.DAILY_BUDGET, due = 3)).open)
    }

    @Test fun gateRespectsCooldownAndLowBattery() {
        assertFalse(OrionProactiveGate.decide(signals(hours = 0.5, due = 3)).open)
        assertFalse(OrionProactiveGate.decide(signals(battery = 10, due = 3)).open)
    }

    @Test fun quietHoursRequireHigherValue() {
        assertFalse(OrionProactiveGate.decide(signals(hour = 2, due = 1)).open)
        assertTrue(OrionProactiveGate.decide(signals(hour = 2, due = 2)).open)
    }
}
