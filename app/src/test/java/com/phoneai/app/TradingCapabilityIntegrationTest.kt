package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingCapabilityIntegrationTest {
    private val deskTools = listOf(
        "positions_monitor", "bybit_sync", "habit_report", "crypto_derivatives",
        "memecoin_check", "memecoin_watch", "wallet_check", "wallet_watch", "chain_overview"
    )

    @Test fun everyAdoptedTradingToolHasOneRegisteredCentralRouteAndExecutor() {
        deskTools.forEach { tool ->
            assertNotNull("missing registry contract for $tool", ToolRegistry.find(tool))
            val route = OrionCapabilityAdapters.route(tool)
            assertNotNull("missing central adapter route for $tool", route)
            assertEquals(OrionCapabilityAdapters.Backend.TRADING_DESK, route!!.backend)
            assertTrue("executor does not own $tool", OrionRuntimeCapabilityExecutor.supports(route.backend, tool))
            assertEquals("trading_research", AgentPolicy.packForTool(tool))
        }
    }
}
