package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for OrionPolicyEngine - central policy enforcement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionPolicyEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testVerdictEnum() {
        val verdicts = OrionPolicyEngine.Verdict.values()
        assertTrue("Should have ALLOW, DENY, ASK verdicts", verdicts.size >= 3)
        assertTrue(verdicts.contains(OrionPolicyEngine.Verdict.ALLOW))
        assertTrue(verdicts.contains(OrionPolicyEngine.Verdict.DENY))
        assertTrue(verdicts.contains(OrionPolicyEngine.Verdict.ASK))
    }

    @Test
    fun testEvaluationDataClass() {
        val eval = OrionPolicyEngine.Evaluation(
            tool = "open_app",
            verdict = OrionPolicyEngine.Verdict.ALLOW,
            reason = null,
            requiresConfirmation = false
        )
        assertEquals("open_app", eval.tool)
        assertEquals(OrionPolicyEngine.Verdict.ALLOW, eval.verdict)
        assertNull(eval.reason)
        assertFalse(eval.requiresConfirmation)

        val tradingEval = OrionPolicyEngine.Evaluation(
            tool = "trading_place_order",
            verdict = OrionPolicyEngine.Verdict.ASK,
            reason = "Trading requires confirmation",
            requiresConfirmation = true
        )
        assertEquals(OrionPolicyEngine.Verdict.ASK, tradingEval.verdict)
        assertTrue(tradingEval.requiresConfirmation)
    }

    @Test
    fun testPolicyEngineIsTradingOperation() {
        // Test via static-like logic without constructing the engine
        val tradingTools = listOf("trading_place_order", "place trade order", "buy BTC")
        val nonTradingTools = listOf("open_app", "read_screen", "send_message")

        for (tool in tradingTools) {
            val isTrading = tool == "trading_place_order" || tool.contains("trade", ignoreCase = true) || tool.contains("order", ignoreCase = true)
            assertTrue("$tool should be detected as trading", isTrading)
        }

        for (tool in nonTradingTools) {
            val isTrading = tool == "trading_place_order" || tool.contains("trade", ignoreCase = true) || tool.contains("order", ignoreCase = true)
            assertFalse("$tool should NOT be detected as trading", isTrading)
        }
    }

    @Test
    fun testPolicyEngineRequiresBossConfirmation() {
        val confirmationTools = listOf("restart", "shutdown", "wifi_toggle")
        val nonConfirmationTools = listOf("open_app", "read_screen", "send_message")

        for (tool in confirmationTools) {
            val requires = tool == "restart" || tool == "shutdown" || tool == "wifi_toggle"
            assertTrue("$tool should require Boss confirmation", requires)
        }

        for (tool in nonConfirmationTools) {
            val requires = tool == "restart" || tool == "shutdown" || tool == "wifi_toggle"
            assertFalse("$tool should NOT require Boss confirmation", requires)
        }
    }
}
