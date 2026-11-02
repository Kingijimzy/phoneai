package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraderToolsTest {
    private fun pos(long: Boolean = true, entry: Double = 100.0, mark: Double = 100.0, stop: Double? = 95.0, target: Double? = 115.0, pnl: Double = 0.0, liq: Double? = null) =
        PositionMonitor.Pos("BTCUSDT", long, 1.0, entry, mark, pnl, stop, target, liq)

    private fun keys(l: List<PositionMonitor.Alert>) = l.map { it.key.substringAfter('|').substringBefore('|') }.toSet()

    @Test fun missingStopIsUrgent() {
        val a = PositionMonitor.assess(listOf(pos(stop = null)), 1000.0, 2.0) { null }
        assertTrue("nostop" in keys(a)); assertTrue(a.first { it.key.endsWith("nostop") }.urgent)
        assertTrue("nostop" in keys(PositionMonitor.assess(listOf(pos(stop = 0.0)), 1000.0, 2.0) { null }))
    }

    @Test fun nearStopAndNearTargetForLongAndShort() {
        assertTrue("nearstop" in keys(PositionMonitor.assess(listOf(pos(mark = 96.0)), 1000.0, 2.0) { null }))
        assertFalse("nearstop" in keys(PositionMonitor.assess(listOf(pos(mark = 99.0)), 1000.0, 2.0) { null }))
        assertTrue("neartarget" in keys(PositionMonitor.assess(listOf(pos(mark = 113.0)), 1000.0, 2.0) { null }))
        assertTrue("nearstop" in keys(PositionMonitor.assess(listOf(pos(long = false, entry = 100.0, mark = 104.0, stop = 105.0, target = 90.0)), 1000.0, 2.0) { null }))
        assertTrue("neartarget" in keys(PositionMonitor.assess(listOf(pos(long = false, entry = 100.0, mark = 91.5, stop = 105.0, target = 90.0)), 1000.0, 2.0) { null }))
    }

    @Test fun protectWinnerWhenStopStillBelowEntry() {
        assertTrue("protect" in keys(PositionMonitor.assess(listOf(pos(mark = 106.0)), 1000.0, 2.0) { null }))
        assertFalse("protect" in keys(PositionMonitor.assess(listOf(pos(mark = 106.0, stop = 100.0)), 1000.0, 2.0) { null }))
    }

    @Test fun liquidationBigLossAndNews() {
        assertTrue("liq" in keys(PositionMonitor.assess(listOf(pos(mark = 100.0, liq = 97.0)), 1000.0, 2.0) { null }))
        assertTrue("bigloss" in keys(PositionMonitor.assess(listOf(pos(pnl = -40.0)), 1000.0, 2.0) { null }))
        assertFalse("bigloss" in keys(PositionMonitor.assess(listOf(pos(pnl = -20.0)), 1000.0, 2.0) { null }))
        val n = PositionMonitor.assess(listOf(pos()), 1000.0, 2.0) { "USD CPI in 20m" }
        assertTrue("news" in keys(n))
        assertTrue(PositionMonitor.assess(listOf(pos()), 1000.0, 2.0) { null }.isEmpty())
    }

    @Test fun summaryMentionsMissingStop() {
        assertTrue(PositionMonitor.summary(listOf(pos(stop = null, pnl = 5.0)), 1000.0).contains("no stop"))
        assertEquals("No open positions.", PositionMonitor.summary(emptyList(), 0.0))
    }

    // ---- habits
    private val t0 = 1_700_000_000_000L - (1_700_000_000_000L % 86_400_000L) // a UTC midnight
    private val MIN = 60_000L
    private fun fill(minuteOfRun: Long, pnl: Double, notional: Double = 100.0) = HabitDetector.Fill("X", true, notional, pnl, t0 + minuteOfRun * MIN)

    @Test fun tooFewTradesClaimsNothing() {
        val r = HabitDetector.analyse((1..5).map { fill(it * 60L, -1.0) })
        assertTrue(r.findings.isEmpty()); assertTrue(r.text().contains("at least"))
    }

    @Test fun detectsRevengeTrades() {
        val fills = ArrayList<HabitDetector.Fill>()
        var m = 0L
        repeat(6) { // loss then bigger, quicker, losing trade
            fills += fill(m, -10.0, 100.0); fills += fill(m + 10, -15.0, 200.0); m += 24 * 60
        }
        repeat(4) { fills += fill(m, 8.0, 100.0); m += 24 * 60 }
        val r = HabitDetector.analyse(fills)
        assertTrue(r.findings.any { it.kind == "revenge" })
    }

    @Test fun detectsOvertradingDays() {
        val fills = ArrayList<HabitDetector.Fill>()
        repeat(8) { d -> fills += fill(d * 1440L + 600, 6.0) } // calm days: one winner each
        repeat(2) { d -> repeat(8) { k -> fills += fill((10 + d) * 1440L + 300 + k * 90, -4.0) } } // two frantic losing days
        val r = HabitDetector.analyse(fills)
        assertTrue(r.findings.any { it.kind == "overtrading" })
    }

    @Test fun detectsCostlyHours() {
        val fills = ArrayList<HabitDetector.Fill>()
        repeat(8) { d -> fills += fill(d * 1440L + 9 * 60, 5.0) }       // 08-12 block wins
        repeat(8) { d -> fills += fill(d * 1440L + 22 * 60, -6.0) }     // 20-24 block loses
        val r = HabitDetector.analyse(fills)
        assertTrue(r.findings.any { it.kind == "hours" && it.text.contains("20:00") })
    }

    @Test fun cleanHistoryReportsNoPattern() {
        val fills = (0 until 20).map { fill(it * 1440L + 600, if (it % 2 == 0) 5.0 else -3.0) }
        assertTrue(HabitDetector.analyse(fills).findings.isEmpty())
    }

    // ---- derivatives
    @Test fun crowdedLongsAndSqueezeSetups() {
        val hot = DerivativesRead.read(DerivativesRead.Input("BTCUSDT", 0.0006, 120.0, 100.0, 2.0, 0.7))
        assertTrue(hot.extremeFunding); assertTrue(hot.tilt < 0); assertTrue(hot.text.contains("crowded"))
        val squeeze = DerivativesRead.read(DerivativesRead.Input("BTCUSDT", -0.0004, 100.0, 100.0, -0.5, 0.3))
        assertTrue(squeeze.extremeFunding); assertTrue(squeeze.tilt > 0)
        val calm = DerivativesRead.read(DerivativesRead.Input("BTCUSDT", 0.0001, 100.0, 100.0, 0.2, null))
        assertFalse(calm.extremeFunding); assertEquals(0.0, calm.tilt, 1e-9)
    }

    @Test fun openInterestConfirmation() {
        assertTrue(DerivativesRead.read(DerivativesRead.Input("X", 0.0001, 110.0, 100.0, 3.0, null)).text.contains("trend confirmation"))
        assertTrue(DerivativesRead.read(DerivativesRead.Input("X", 0.0001, 110.0, 100.0, -3.0, null)).tilt < 0)
    }

    @Test fun parseTimeAcceptsCommonFormats() {
        assertEquals(1_700_000_000_000L, HabitDetector.parseTime("1700000000000"))
        assertEquals(1_700_000_000_000L, HabitDetector.parseTime("1700000000"))
        assertEquals(1_700_000_000_000L, HabitDetector.parseTime("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000_000L, HabitDetector.parseTime("2023-11-14 22:13:20"))
        org.junit.Assert.assertNull(HabitDetector.parseTime("yesterday"))
        org.junit.Assert.assertNull(HabitDetector.parseTime(""))
    }

    @Test fun bybitRowsParse() {
        val p = BybitParse.position(org.json.JSONObject("""{"symbol":"BTCUSDT","side":"Buy","size":"0.01","avgPrice":"60000","markPrice":"61000","unrealisedPnl":"10","stopLoss":"59000","takeProfit":"0","liqPrice":"50000"}"""))!!
        assertTrue(p.long); assertEquals(59000.0, p.stop!!, 0.0); assertEquals(null, p.target); assertEquals(50000.0, p.liquidation!!, 0.0)
        org.junit.Assert.assertNull(BybitParse.position(org.json.JSONObject("""{"symbol":"X","size":"0","avgPrice":"1"}""")))
        val row = org.json.JSONObject("""{"symbol":"ETHUSDT","side":"Sell","qty":"2","avgEntryPrice":"3000","avgExitPrice":"3100","closedPnl":"200","createdTime":"1700000000000","updatedTime":"1700000100000","orderId":"abc"}""")
        val f = BybitParse.fill(row)!!
        assertTrue(f.long); assertEquals(6000.0, f.notional, 0.0); assertEquals(200.0, f.pnl, 0.0); assertEquals(1_700_000_100_000L, f.closedAt)
        val a = BybitParse.imported(row, 1L)!!; val b = BybitParse.imported(row, 2L)!!
        assertEquals(a.fingerprint, b.fingerprint); assertEquals("long", a.side)
    }
}
