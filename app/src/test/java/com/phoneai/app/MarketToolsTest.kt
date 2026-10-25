package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketToolsTest {
    @Test fun riskCalcSizesPosition() {
        val r = RiskCalc.compute(1000.0, 1.0, 100.0, 95.0, 115.0)!!
        assertEquals(2.0, r.units, 1e-9)
        assertEquals(10.0, r.riskAmount, 1e-9)
        assertEquals(3.0, r.rrAtTarget!!, 1e-9)
        assertEquals(5.0, r.stopPct, 1e-9)
    }

    @Test fun riskCalcRejectsBadInput() {
        assertNull(RiskCalc.compute(1000.0, 1.0, 100.0, 100.0))
        assertNull(RiskCalc.compute(0.0, 1.0, 100.0, 95.0))
    }

    @Test fun shortTradeRewardRisk() {
        assertEquals(2.0, RiskCalc.compute(1000.0, 1.0, 100.0, 105.0, 90.0)!!.rrAtTarget!!, 1e-9)
    }

    @Test fun resolvesForexAndNamedInstruments() {
        assertEquals("USDJPY=X", MarketQuotes.resolve("USDJPY")!!.first)
        assertEquals("EURUSD=X", MarketQuotes.resolve("eur/usd")!!.first)
        assertNotNull(MarketQuotes.resolve("gold"))
        assertNull(MarketQuotes.resolve("BTCUSDT"))
        assertEquals(2, MarketQuotes.symbolsIn("compare USDJPY and gold today").size)
    }

    @Test fun parsesGoogleNewsRss() {
        val xml = "<rss><channel><item><title>Yen slides &amp; BoJ holds - Reuters</title><link>x</link><pubDate>Sun, 25 Oct 2026 09:00:00 GMT</pubDate><source url=\"u\">Reuters</source></item></channel></rss>"
        val items = LiveNews.parseRss(xml, 5)
        assertEquals(1, items.size)
        assertTrue(items[0].startsWith("Yen slides & BoJ holds"))
        assertTrue(items[0].contains("— Reuters"))
    }

    @Test fun markdownBulletsNormalize() {
        assertEquals("• one\n• two", Markdown.normalizeLines("- one\n* two"))
    }
}
