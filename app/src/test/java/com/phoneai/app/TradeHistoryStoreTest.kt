package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeHistoryStoreTest {
    @Test fun parsesQuotedCommasAndEscapedQuotes() {
        val rows = TradeHistoryStore.parseCsv(
            """Symbol,Side,Closed P&L,Notes
BTCUSDT,Buy,12.50,"closed, partial"
ETHUSDT,Sell,-3.25,"said ""done""""
        )
        assertEquals(3, rows.size)
        assertEquals("BTCUSDT", rows[1][0])
        assertEquals("closed, partial", rows[1][3])
        assertEquals("said \"done\"", rows[2][3])
    }

    @Test fun parsesSemicolonSeparatedExports() {
        val rows = TradeHistoryStore.parseCsv(
            """Symbol;Side;Profit
BTCUSD;Sell;-1.5"""
        )
        assertEquals(2, rows.size)
        assertEquals("BTCUSD", rows[1][0])
        assertEquals("-1.5", rows[1][2])
    }

    @Test fun preservesNewlinesInsideQuotedFields() {
        val rows = TradeHistoryStore.parseCsv(
            """Symbol,Notes
BTCUSDT,"first line
second line" """
        )
        assertEquals(2, rows.size)
        assertTrue(rows[1][1].contains("first line\nsecond line"))
    }
}
