package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskAnalysisTest {
    private fun ev(title: String, ccy: String, at: Long, impact: MarketCalendar.Impact, f: String = "", p: String = "", a: String = "") =
        MarketCalendar.Event(title, ccy, at, impact, f, p, a)

    private val now = 1_000_000_000_000L
    private val H = 3_600_000L

    @Test fun numbersParseWithSuffixes() {
        assertEquals(150_000.0, CurrencyConditions.number("150K")!!, 0.0)
        assertEquals(3.2, CurrencyConditions.number("3.2%")!!, 1e-9)
        assertEquals(-0.1, CurrencyConditions.number("-0.1%")!!, 1e-9)
        assertEquals(1.25e6, CurrencyConditions.number("1.25M")!!, 1e-3)
        assertNull(CurrencyConditions.number(""))
        assertNull(CurrencyConditions.number("n/a"))
    }

    @Test fun directionUsesForecastVsPreviousAndInvertsUnemployment() {
        assertEquals(1, CurrencyConditions.direction(ev("CPI y/y", "USD", now, MarketCalendar.Impact.HIGH, "3.4%", "3.1%")))
        assertEquals(-1, CurrencyConditions.direction(ev("Unemployment Rate", "USD", now, MarketCalendar.Impact.HIGH, "4.3%", "4.1%")))
        assertEquals(0, CurrencyConditions.direction(ev("Speech", "USD", now, MarketCalendar.Impact.HIGH)))
        // a published actual beats the forecast comparison
        assertEquals(-1, CurrencyConditions.direction(ev("NFP", "USD", now, MarketCalendar.Impact.HIGH, "150K", "140K", "90K")))
    }

    @Test fun assessCountsHighImpactAheadAndTilt() {
        val events = listOf(
            ev("CPI", "EUR", now + 5 * H, MarketCalendar.Impact.HIGH, "2.5%", "2.2%"),
            ev("Retail", "EUR", now + 30 * H, MarketCalendar.Impact.MEDIUM, "0.4%", "0.2%"),
            ev("Jobless Claims", "USD", now + 6 * H, MarketCalendar.Impact.HIGH, "230K", "220K")
        )
        val eur = CurrencyConditions.assess(events, now, "EUR")
        assertEquals(1, eur.highAhead)
        assertTrue(eur.tilt > 0)
        assertEquals("CPI", eur.next!!.title)
        val usd = CurrencyConditions.assess(events, now, "USD")
        assertTrue(usd.tilt < 0) // more jobless claims expected is bad for USD
        assertTrue(CurrencyConditions.pairTilt(events, now, "EURUSD") > 0)
        assertTrue(CurrencyConditions.pairText(events, now, "EURUSD").contains("favour EURUSD up"))
    }

    @Test fun emptyCalendarIsNeutral() {
        val c = CurrencyConditions.assess(emptyList(), now, "GBP")
        assertEquals(0.0, c.tilt, 0.0); assertEquals(0, c.highAhead); assertNull(c.next)
    }

    @Test fun headlineSentimentCountsTone() {
        val pos = HeadlineSentiment.score(listOf("Bitcoin ETF approval sparks rally", "Record high inflows", "Exchange hack drains funds"))
        assertEquals(2, pos.bullish); assertEquals(1, pos.bearish); assertEquals("mixed", pos.label)
        val neg = HeadlineSentiment.score(listOf("Crash fears", "Lawsuit filed", "Sell-off deepens"))
        assertEquals("negative", neg.label); assertTrue(neg.tilt < 0)
        assertEquals("no headlines", HeadlineSentiment.score(emptyList()).label)
    }

    @Test fun forecastIsCompressedAndWeightsEvidence() {
        val strong = MarketForecast.forecast(MarketForecast.Input("X", 1, 75, 1, 0.8, 60, 1.0))
        assertEquals(1, strong.lean); assertTrue(strong.probabilityUp in 51..65)
        val thin = MarketForecast.forecast(MarketForecast.Input("X", 0, 30, 1, 0.9, 3, 0.0))
        val proven = MarketForecast.forecast(MarketForecast.Input("X", 0, 30, 1, 0.9, 40, 0.0))
        assertTrue(proven.composite > thin.composite)
        val bear = MarketForecast.forecast(MarketForecast.Input("X", -1, 75, -1, 0.5, 40, -1.0))
        assertEquals(-1, bear.lean); assertTrue(bear.probabilityUp in 35..49)
        val flat = MarketForecast.forecast(MarketForecast.Input("X", 0, 30, 0, null, 0, 0.0))
        assertEquals(0, flat.lean); assertEquals(50, flat.probabilityUp)
        assertTrue(flat.text("X").contains("no clear lean"))
    }

    /** A drifting, oscillating market: enough structure for pullback and breakout setups to appear. */
    private fun market(n: Int): List<Candle> {
        var px = 100.0; val out = ArrayList<Candle>()
        var seed = 12345L
        for (i in 0 until n) {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            val noise = ((seed % 1000) / 1000.0 - 0.5) * 1.2
            val drift = if ((i / 80) % 2 == 0) 0.12 else -0.10
            val o = px; px = (px + drift + noise).coerceAtLeast(5.0)
            val h = maxOf(o, px) + 0.3 * ((seed / 7) % 100) / 100.0
            val l = minOf(o, px) - 0.3 * ((seed / 11) % 100) / 100.0
            out += Candle(i * 3_600_000L, o, h, l, px, 100.0 + (seed % 50))
        }
        return out
    }

    @Test fun backtestIsDeterministicAndConsistent() {
        val cs = market(900)
        val st = StrategySpec("any", minRewardToRisk = 1.5)
        val a = StrategyBacktest.run("TEST", "1h", cs, st)
        val b = StrategyBacktest.run("TEST", "1h", cs, st)
        assertTrue("synthetic market should yield trades, got ${a.trades.size}", a.trades.size >= 5)
        assertEquals(a.trades.size, b.trades.size)
        assertEquals(a.rs, b.rs)
        a.trades.forEach {
            assertTrue("exit after entry", it.exitIndex > it.entryIndex)
            assertTrue(it.reason in setOf("stop", "target", "timeout"))
            assertTrue(it.r > -3.0 && it.r < 12.0)
        }
        // trades never overlap: one position at a time
        a.trades.zipWithNext().forEach { assertTrue(it.second.entryIndex > it.first.exitIndex) }
        assertEquals(a.trades.size, a.curve.size)
        assertNotNull(a.text())
    }

    @Test fun costsReduceResults() {
        val cs = market(900)
        val st = StrategySpec("any")
        val free = StrategyBacktest.run("TEST", "1h", cs, st, costPct = 0.0)
        val costly = StrategyBacktest.run("TEST", "1h", cs, st, costPct = 0.3)
        assertEquals(free.trades.size, costly.trades.size)
        if (free.trades.isNotEmpty()) assertTrue(free.rs.sum() > costly.rs.sum())
    }

    @Test fun strategyFiltersRestrictTrades() {
        val cs = market(900)
        val none = StrategyBacktest.run("TEST", "1h", cs, StrategySpec("x", setupTypes = listOf("nonexistent-type")))
        assertTrue(none.trades.isEmpty())
        assertTrue(none.text().contains("no trades matched"))
        val hi = StrategyBacktest.run("TEST", "1h", cs, StrategySpec("x", minRewardToRisk = 50.0))
        assertTrue(hi.trades.isEmpty())
    }

    @Test fun forexFactoryHeadlinesAreExtractedFromNewsLinks() {
        val html = """<div><a href="/news/123-dollar-slips">Dollar slips as traders weigh Fed path</a>
            <a href="/calendar">Calendar</a><a href="/news/9"><span>Yen jumps after surprise</span> intervention talk</a>
            <a href="/news/1">short</a><a href="/news/123-dollar-slips">Dollar slips as traders weigh Fed path</a></div>"""
        val h = NewsDesk.parseForexFactory(html)
        assertEquals(2, h.size)
        assertEquals("Dollar slips as traders weigh Fed path", h[0])
        assertTrue(h[1].startsWith("Yen jumps"))
        assertTrue(NewsDesk.parseForexFactory("<html></html>").isEmpty())
    }
}
