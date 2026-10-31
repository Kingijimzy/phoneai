package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainRiskTest {
    private val now = 1_800_000_000_000L
    private val H = 3_600_000L
    private fun pair(liq: Double = 500_000.0, ageH: Double = 100.0, c1: Double = 2.0, c24: Double = 5.0, fdv: Double = 2_000_000.0,
                     buys: Int = 40, sells: Int = 38, vol: Double = 400_000.0) =
        DexPair("solana", "raydium", "P", "T", "DOGE2", "Doge2", 0.0123, c1, c24, liq, vol, buys, sells, fdv, now - (ageH * H).toLong(), "u")

    @Test fun healthyPairHasNoFlags() {
        val a = ChainRisk.assess(pair(), now)
        assertEquals(ChainRisk.Level.OK, a.level); assertTrue(a.flags.isEmpty())
        assertTrue(ChainRisk.summary(pair(), now).contains("cannot see the contract"))
    }

    @Test fun thinLiquidityAndNewPairsEscalate() {
        assertEquals(ChainRisk.Level.HIGH, ChainRisk.assess(pair(liq = 10_000.0), now).level)
        assertEquals(ChainRisk.Level.CAUTION, ChainRisk.assess(pair(liq = 60_000.0, fdv = 100_000.0, vol = 10_000.0), now).level)
        assertEquals(ChainRisk.Level.HIGH, ChainRisk.assess(pair(ageH = 0.5), now).level)
        assertEquals(ChainRisk.Level.CAUTION, ChainRisk.assess(pair(ageH = 10.0), now).level)
    }

    @Test fun dumpsPumpsAndSellPressure() {
        assertEquals(ChainRisk.Level.HIGH, ChainRisk.assess(pair(c1 = -35.0), now).level)
        assertTrue(ChainRisk.assess(pair(c1 = 150.0), now).flags.any { it.contains("parabolic") })
        assertTrue(ChainRisk.assess(pair(buys = 10, sells = 40), now).flags.any { it.contains("sellers outnumber") })
        assertTrue(ChainRisk.assess(pair(fdv = 900_000_000.0), now).flags.any { it.contains("easy to manipulate") })
        assertTrue(ChainRisk.assess(pair(vol = 40_000_000.0), now).flags.any { it.contains("wash trading") })
    }

    @Test fun alertsFirePumpDumpAndLiquidityPull() {
        assertTrue(ChainRisk.alerts(null, pair(c1 = 20.0)).any { it.kind == "pump" })
        assertTrue(ChainRisk.alerts(null, pair(c1 = -20.0)).any { it.kind == "dump" })
        assertTrue(ChainRisk.alerts(0.01 to 500_000.0, pair(liq = 200_000.0)).any { it.kind == "liquidity" })
        assertTrue(ChainRisk.alerts(0.01 to 500_000.0, pair(liq = 480_000.0)).isEmpty())
    }

    @Test fun addressDetectionAndSecretGuard() {
        assertEquals(WalletAddress.Kind.EVM, WalletAddress.detect("0x52908400098527886E0F7030069857D2E4169EE7"))
        assertEquals(WalletAddress.Kind.SOLANA, WalletAddress.detect("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"))
        assertNull(WalletAddress.detect("hello")); assertNull(WalletAddress.detect("0x123"))
        assertTrue(WalletAddress.looksLikeSecret("a".repeat(64)))
        assertTrue(WalletAddress.looksLikeSecret("0x" + "ab12".repeat(16)))
        assertTrue(WalletAddress.looksLikeSecret("legal winner thank year wave sausage worth useful legal winner thank yellow"))
        assertTrue(WalletAddress.looksLikeSecret("4NMwxzmYj2uvHuq8xoqhY8RXg63KSVJM1DXkpbmkUY7YQWuoyQgFnnzn6yo3CMnqZasnNPNuAT7TGGMQNnGUMhdf"))
        assertFalse(WalletAddress.looksLikeSecret("0x52908400098527886E0F7030069857D2E4169EE7"))
        assertFalse(WalletAddress.looksLikeSecret("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"))
    }

    private fun h(k: String, usd: Double, amt: Double = 1.0) = WalletDiff.Holding(k, k.uppercase(), amt, usd)

    @Test fun walletDiffReportsValueMovesNewTokensAndExits() {
        val prev = WalletDiff.Snapshot(1000.0, listOf(h("sol", 800.0), h("bonk", 200.0)))
        val big = WalletDiff.compare(prev, WalletDiff.Snapshot(700.0, listOf(h("sol", 600.0), h("bonk", 100.0), h("scam", 0.0))))
        assertTrue(big.any { it.contains("-30.0%") }); assertTrue(big.any { it.contains("New token SCAM") })
        assertTrue(WalletDiff.compare(prev, WalletDiff.Snapshot(1020.0, prev.holdings)).isEmpty())
        assertTrue(WalletDiff.compare(null, prev).isEmpty())
        val exit = WalletDiff.compare(prev, WalletDiff.Snapshot(800.0, listOf(h("sol", 800.0))))
        assertTrue(exit.any { it.contains("BONK balance dropped") })
        assertTrue(WalletDiff.text("Main", prev, 800.0).contains("+25.0%"))
    }
}
