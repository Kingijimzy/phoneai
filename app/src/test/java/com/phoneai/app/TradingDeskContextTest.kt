package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TradingDeskContextTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("trading_desk", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun unrelatedConversationDoesNotReceiveTradingContext() {
        assertNull(TradingDeskContext.promptBlock(context, "Tell me a joke about the moon"))
    }

    @Test fun tradingConversationReceivesSavedDeskStateButNotFabricatedLiveData() {
        val block = TradingDeskContext.promptBlock(context, "Analyse BTCUSDT liquidity and my trading setup")
        assertNotNull(block)
        assertTrue(block!!.contains("TRADING DESK CONTEXT"))
        assertTrue(block.contains("Watchlist:"))
        assertTrue(block.contains("not live market evidence"))
        assertTrue(block.contains("Freshness rule"))
        assertTrue(block.contains("explicit-confirmation gates"))
        assertFalse(block.contains("live price is"))
    }

    @Test fun performanceReviewIncludesBoundedSavedEvidence() {
        val block = TradingDeskContext.promptBlock(context, "Review my trading journal and mistakes")
        assertNotNull(block)
        assertTrue(block!!.contains("Saved performance evidence"))
        assertTrue(block.length <= 1900)
    }
}
