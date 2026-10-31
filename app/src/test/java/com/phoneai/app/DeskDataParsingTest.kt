package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskDataParsingTest {
    private val t0 = java.time.OffsetDateTime.parse("2026-10-30T12:30:00-04:00").toInstant().toEpochMilli()

    private val feed = """[
      {"title":"Non-Farm Employment Change","country":"USD","date":"2026-10-30T12:30:00-04:00","impact":"High","forecast":"150K","previous":"140K"},
      {"title":"Bank Holiday","country":"JPY","date":"2026-10-30T00:00:00-04:00","impact":"Holiday","forecast":"","previous":""},
      {"title":"German Ifo","country":"EUR","date":"2026-10-30T04:00:00-04:00","impact":"Medium","forecast":"","previous":""},
      {"title":"broken","country":"GBP","date":"not a date","impact":"High"}
    ]"""

    @Test fun calendarParsesAndSkipsBadRows() {
        val ev = CalendarFeed.parse(feed)
        assertEquals(3, ev.size)
        assertTrue(ev.zipWithNext().all { it.first.atMillis <= it.second.atMillis })
        assertEquals(MarketCalendar.Impact.HIGH, ev.first { it.currency == "USD" }.impact)
        assertTrue(CalendarFeed.parse("garbage").isEmpty())
    }

    @Test fun blackoutWindowAroundHighImpactOnly() {
        val ev = CalendarFeed.parse(feed)
        val usd = setOf("USD")
        assertTrue(MarketCalendar.inBlackout(ev, t0 - 20 * 60_000L, usd))
        assertTrue(MarketCalendar.inBlackout(ev, t0 + 10 * 60_000L, usd))
        assertFalse(MarketCalendar.inBlackout(ev, t0 - 45 * 60_000L, usd))
        assertFalse(MarketCalendar.inBlackout(ev, t0 + 30 * 60_000L, usd))
        assertFalse(MarketCalendar.inBlackout(ev, t0, setOf("EUR"))) // the EUR event is only medium impact
    }

    @Test fun upcomingFiltersByImpactAndCurrency() {
        val ev = CalendarFeed.parse(feed)
        val now = t0 - 9 * 3_600_000L
        assertEquals(2, MarketCalendar.upcoming(ev, now, 24).size)
        assertEquals(1, MarketCalendar.upcoming(ev, now, 24, MarketCalendar.Impact.HIGH).size)
        assertEquals(1, MarketCalendar.upcoming(ev, now, 24, currencies = setOf("EUR")).size)
    }

    @Test fun currenciesForPairsAndCrypto() {
        assertEquals(setOf("EUR", "USD"), MarketCalendar.currenciesFor("EURUSD"))
        assertEquals(setOf("USD"), MarketCalendar.currenciesFor("BTCUSDT"))
        assertEquals(setOf("USD"), MarketCalendar.currenciesFor("XAUUSD"))
        assertEquals(setOf("GBP", "JPY"), MarketCalendar.currenciesFor("GBP/JPY"))
    }

    @Test fun fxPairRecognition() {
        assertEquals("EURUSD", FxData.pairOf("eur/usd"))
        assertEquals("EURUSD", FxData.pairOf("EURUSD=X"))
        assertEquals("XAUUSD", FxData.pairOf("xau_usd"))
        assertNull(FxData.pairOf("BTCUSDT"))
        assertNull(FxData.pairOf("hello"))
    }

    @Test fun chartParsingSkipsNullGapsAndAggregates() {
        val json = """{"chart":{"result":[{"timestamp":[0,3600,7200,10800],"indicators":{"quote":[{
            "open":[1.0,1.1,null,1.3],"high":[1.2,1.3,null,1.5],"low":[0.9,1.0,null,1.2],"close":[1.1,1.2,null,1.4],"volume":[10,20,null,40]}]}}]}}"""
        val cs = FxData.parseChart(json)
        assertEquals(3, cs.size)
        assertEquals(0L, cs[0].t); assertEquals(1.1, cs[0].c, 1e-9)
        val agg = FxData.aggregate(cs, 4) // all three hourly candles fall in one 4h bucket
        assertEquals(1, agg.size)
        assertEquals(1.5, agg[0].h, 1e-9); assertEquals(0.9, agg[0].l, 1e-9); assertEquals(1.0, agg[0].o, 1e-9); assertEquals(1.4, agg[0].c, 1e-9)
        assertTrue(FxData.parseChart("nope").isEmpty())
    }

    @Test fun derivMessagesAndReplies() {
        assertEquals("frxEURUSD", DerivProtocol.symbolFor("eurusd"))
        assertNull(DerivProtocol.symbolFor("BTCUSDT"))
        val req = org.json.JSONObject(DerivProtocol.proposal("EURUSD", true, 20.0, 50, 4.5, 9.0, 7)!!)
        assertEquals("MULTUP", req.getString("contract_type"))
        assertEquals(50, req.getInt("multiplier"))
        assertEquals(4.5, req.getJSONObject("limit_order").getDouble("stop_loss"), 1e-9)
        assertEquals(9.0, req.getJSONObject("limit_order").getDouble("take_profit"), 1e-9)
        assertEquals("MULTDOWN", org.json.JSONObject(DerivProtocol.proposal("XAUUSD", false, 10.0, 20, 1.0, null, 1)!!).getString("contract_type"))
        assertNull(DerivProtocol.proposal("EURUSD", true, 0.0, 50, 1.0, null, 1))
        assertNull(DerivProtocol.proposal("BTCUSDT", true, 10.0, 50, 1.0, null, 1))

        val auth = DerivProtocol.parse("""{"msg_type":"authorize","authorize":{"loginid":"VRTC123","is_virtual":1,"balance":10000,"currency":"USD"}}""")
        assertTrue(auth is DerivProtocol.Reply.Authorized && auth.isVirtual && auth.balance == 10000.0)
        val err = DerivProtocol.parse("""{"error":{"code":"InvalidToken","message":"The token is invalid."}}""")
        assertTrue(err is DerivProtocol.Reply.Error && err.code == "InvalidToken")
        val prop = DerivProtocol.parse("""{"msg_type":"proposal","proposal":{"id":"abc","ask_price":20.0,"longcode":"Win payout if..."}}""")
        assertTrue(prop is DerivProtocol.Reply.Proposal && prop.id == "abc")
        val bought = DerivProtocol.parse("""{"msg_type":"buy","buy":{"contract_id":99,"buy_price":20.0,"longcode":"x"}}""")
        assertTrue(bought is DerivProtocol.Reply.Bought && bought.contractId == 99L)
        assertTrue(DerivProtocol.parse("{\"msg_type\":\"ping\"}") is DerivProtocol.Reply.Other)
        assertTrue(DerivProtocol.parse("not json") is DerivProtocol.Reply.Error)
    }
}
