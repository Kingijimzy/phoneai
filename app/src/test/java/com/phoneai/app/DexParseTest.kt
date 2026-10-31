package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexParseTest {
    private val search = """{"schemaVersion":"1.0.0","pairs":[
      {"chainId":"solana","dexId":"raydium","pairAddress":"PA1","baseToken":{"address":"MintAAA","name":"Doge Two","symbol":"DOGE2"},"quoteToken":{"symbol":"SOL"},
       "priceUsd":"0.0123","txns":{"h1":{"buys":30,"sells":12}},"volume":{"h24":350000},"priceChange":{"h1":4.5,"h24":-12.0},"liquidity":{"usd":250000},"fdv":3000000,"pairCreatedAt":1800000000000,"url":"https://dexscreener.com/solana/PA1"},
      {"chainId":"solana","dexId":"orca","pairAddress":"PA2","baseToken":{"address":"MintAAA","name":"Doge Two","symbol":"DOGE2"},"priceUsd":"0.0124","liquidity":{"usd":900000}},
      {"chainId":"solana","pairAddress":"BAD","baseToken":{"address":"X","symbol":"X"},"priceUsd":"0"},
      {"chainId":"solana","pairAddress":"BAD2"}]}"""

    @Test fun parsesSearchAndSkipsBadRows() {
        val l = DexParse.pairs(search)
        assertEquals(2, l.size)
        val p = l[0]
        assertEquals("DOGE2", p.symbol); assertEquals(0.0123, p.priceUsd, 1e-12); assertEquals(4.5, p.change1h, 0.0)
        assertEquals(-12.0, p.change24h, 0.0); assertEquals(250000.0, p.liquidityUsd, 0.0); assertEquals(30, p.buys1h); assertEquals(12, p.sells1h)
        assertEquals(1_800_000_000_000L, p.createdAtMillis)
        assertTrue(DexParse.pairs("nope").isEmpty()); assertTrue(DexParse.pairs("""{"pairs":null}""").isEmpty())
    }

    @Test fun bareArrayAndBestPerToken() {
        val l = DexParse.pairs(search.substringAfter("\"pairs\":").dropLast(1))
        val best = DexParse.bestPerToken(l)
        assertEquals(1, best.size)
        assertEquals("PA2", best["minta" + "aa"]!!.pairAddress)
    }

    @Test fun solanaTokenAccountsAreSummedAndZeroDropped() {
        val j = """{"result":{"value":[
          {"account":{"data":{"parsed":{"info":{"mint":"M1","tokenAmount":{"uiAmount":10.5}}}}}},
          {"account":{"data":{"parsed":{"info":{"mint":"M1","tokenAmount":{"uiAmount":1.5}}}}}},
          {"account":{"data":{"parsed":{"info":{"mint":"M2","tokenAmount":{"uiAmount":0.0}}}}}}]}}"""
        val t = DexParse.solanaTokens(j)
        assertEquals(1, t.size); assertEquals(12.0, t["M1"]!!, 1e-9)
        assertTrue(DexParse.solanaTokens("garbage").isEmpty())
    }

    @Test fun nativeBalances() {
        assertEquals(1.5, DexParse.solBalance("""{"result":{"context":{},"value":1500000000}}""")!!, 1e-9)
        assertNull(DexParse.solBalance("""{"error":{}}"""))
        assertEquals(2.0, DexParse.evmBalance("""{"result":"0x1bc16d674ec80000"}""")!!, 1e-9)
        assertEquals(0.0, DexParse.evmBalance("""{"result":"0x0"}""")!!, 0.0)
        assertNull(DexParse.evmBalance("""{"error":"x"}"""))
        assertNotNull(DexParse.evmBalance("""{"result":"0xde0b6b3a7640000"}"""))
    }
}
