package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class TradingDeskTest {
    @Test fun candidateFromChartNoteNeedsAccept() {
        val note = TradingPlaybook.StrategyNote(
            instrument = "BTCUSDT",
            direction = TradingPlaybook.Direction.LONG,
            levels = listOf("64000", "62000"),
            invalidation = "61500",
            session = "NewYork",
            rawSummary = "Breakout continuation",
            fromImage = true
        )
        val c = TradingPlaybook.candidateFromNote(note)
        assertTrue(c.needsUserAccept)
        assertEquals("BTCUSDT", c.instrument)
        assertEquals(TradingPlaybook.Direction.LONG, c.direction)
        assertTrue(c.conditions.any { it.startsWith("level:") })
    }

    @Test fun matcherScoresEnabledRuleOnSameInstrument() {
        val rule = TradingPlaybook.Rule(
            id = "r1",
            name = "BTC long NY",
            assetClass = TradingPlaybook.AssetClass.CRYPTO,
            instrument = "BTCUSDT",
            direction = TradingPlaybook.Direction.LONG,
            conditions = listOf("session:newyork"),
            enabled = true
        )
        val snap = TradingMatcher.MarketSnapshot(
            instrument = "BTCUSDT",
            assetClass = TradingPlaybook.AssetClass.CRYPTO,
            lastPrice = 65000.0,
            changePct = 1.2,
            session = "NewYork"
        )
        val cards = TradingMatcher.match(listOf(rule), snap)
        assertEquals(1, cards.size)
        assertTrue(cards[0].score >= 0.5)
    }

    @Test fun matcherIgnoresDisabledRules() {
        val rule = TradingPlaybook.Rule(
            id = "r2",
            name = "off",
            assetClass = TradingPlaybook.AssetClass.CRYPTO,
            instrument = "ETHUSDT",
            direction = TradingPlaybook.Direction.SHORT,
            enabled = false
        )
        val snap = TradingMatcher.MarketSnapshot(
            instrument = "ETHUSDT",
            assetClass = TradingPlaybook.AssetClass.CRYPTO,
            lastPrice = 3000.0
        )
        assertTrue(TradingMatcher.match(listOf(rule), snap).isEmpty())
    }

    @Test fun riskPolicyBlocksKillSwitchAndPaper() {
        val intent = TradingRiskPolicy.OrderIntent("BTCUSDT", notionalUsd = 10.0)
        assertFalse(TradingRiskPolicy.evaluate(TradingRiskPolicy.Limits(killSwitch = true, paperMode = false), intent).allowed)
        assertFalse(TradingRiskPolicy.evaluate(TradingRiskPolicy.Limits(paperMode = true), intent).allowed)
    }

    @Test fun riskPolicyEnforcesNotionalAndAllowlist() {
        val limits = TradingRiskPolicy.Limits(
            paperMode = false,
            maxNotionalUsd = 20.0,
            symbolAllowlist = setOf("BTCUSDT")
        )
        assertFalse(TradingRiskPolicy.evaluate(limits, TradingRiskPolicy.OrderIntent("ETHUSDT", 10.0)).allowed)
        assertFalse(TradingRiskPolicy.evaluate(limits, TradingRiskPolicy.OrderIntent("BTCUSDT", 50.0)).allowed)
        assertTrue(TradingRiskPolicy.evaluate(limits, TradingRiskPolicy.OrderIntent("BTCUSDT", 10.0)).allowed)
    }

    @Test fun packForToolMapsResearchAndExecution() {
        assertEquals("trading_research", AgentPolicy.packForTool("bybit_public_ticker"))
        assertEquals("trading_research", AgentPolicy.packForTool("trading_match_setups"))
        assertEquals("trading_execution", AgentPolicy.packForTool("bybit_place_order"))
        assertFalse(AgentPolicy.defaultOn("trading_execution"))
        assertTrue(AgentPolicy.defaultOn("trading_research"))
    }

    @Test fun tradingExecutionPackExistsButNotImplementedUntilOrderPath() {
        val pack = AgentPolicy.packs.first { it.id == "trading_execution" }
        assertFalse(pack.implemented)
        assertFalse(AgentPolicy.defaultOn(pack.id))
    }
}
