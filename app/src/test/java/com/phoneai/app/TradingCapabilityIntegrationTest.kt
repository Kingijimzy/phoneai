package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class TradingCapabilityIntegrationTest {
    private val deskTools = listOf(
        "positions_monitor", "bybit_sync", "habit_report", "crypto_derivatives",
        "memecoin_check", "memecoin_watch", "wallet_check", "wallet_watch", "chain_overview"
    )

    @Test fun paperTradeOpenStatusAndCloseExecuteThroughTheRegisteredCentralRoute() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("trading_desk", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        fun invoke(args: JSONObject): String = OrionCapabilityAdapters.dispatch("paper_trade", args) { route, payload ->
            assertEquals(OrionCapabilityAdapters.Backend.TRADING_DESK, route.backend)
            OrionTradingDeskCapabilityExecutor.execute(route.tool, payload, context)
                ?: "error: no Trading Desk implementation"
        }

        val opened = invoke(JSONObject()
            .put("action", "open")
            .put("symbol", "EURUSD")
            .put("side", "buy")
            .put("usd", 100.0)
            .put("entry", 1.1000)
            .put("stop", 1.0950)
            .put("target", 1.1100)
            .put("setup_type", "integration-test"))
        assertTrue(opened, opened.contains("Opened paper long EURUSD"))

        val status = invoke(JSONObject().put("action", "status"))
        assertTrue(status, status.contains("EURUSD"))

        val closed = invoke(JSONObject()
            .put("action", "close")
            .put("id", 1)
            .put("price", 1.1050))
        assertTrue(closed, closed.contains("Closed paper #1"))
    }

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
