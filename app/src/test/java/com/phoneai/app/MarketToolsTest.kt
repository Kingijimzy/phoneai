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

class TradeProposalsTest {
    private val resolve: (String) -> String? = { it.uppercase().let { s -> if (s.endsWith("USDT")) s else s + "USDT" } }

    @Test fun parsesTradeAndAlertLinesAndHidesThem() {
        val reply = "Setup looks fine.\nTRADE: long btc 200 stop=60000 target=65000 | breakout retest\nALERT: ETH below 3000\nMEMORY: x"
        val p = TradeProposals.parse(reply, resolve)
        assertEquals(1, p.trades.size)
        assertEquals("BTCUSDT", p.trades[0].symbol)
        assertEquals(60000.0, p.trades[0].stop, 0.0)
        assertEquals(65000.0, p.trades[0].target!!, 0.0)
        assertEquals(1, p.alerts.size)
        assertEquals(false, p.alerts[0].above)
        assertTrue(!p.visible.contains("TRADE:") && !p.visible.contains("ALERT:") && p.visible.contains("MEMORY"))
    }

    @Test fun tradeWithoutStopIsIgnored() {
        assertEquals(0, TradeProposals.parse("TRADE: long BTC 200 target=65000", resolve).trades.size)
    }

    @Test fun sessionsOverlap() {
        assertTrue(TradeProposals.sessionAt(13).contains("London + New York"))
        assertTrue(TradeProposals.sessionAt(22).contains("quiet"))
    }
}
