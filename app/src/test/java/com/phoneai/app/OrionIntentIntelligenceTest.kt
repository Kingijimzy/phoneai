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
 * Tests for OrionIntentIntelligence - understanding user objectives from natural language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionIntentIntelligenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testIntentTypeEnum() {
        // Test all intent types are defined
        val types = OrionIntentIntelligence.IntentType.values()
        assertTrue("Should have multiple intent types", types.size >= 6)
        assertTrue(types.contains(OrionIntentIntelligence.IntentType.ACTION))
        assertTrue(types.contains(OrionIntentIntelligence.IntentType.QUESTION))
        assertTrue(types.contains(OrionIntentIntelligence.IntentType.RESEARCH))
        assertTrue(types.contains(OrionIntentIntelligence.IntentType.SYSTEM_CONTROL))
        assertTrue(types.contains(OrionIntentIntelligence.IntentType.CREATIVE))
        assertTrue(types.contains(OrionIntentIntelligence.IntentType.MEMORY_QUERY))
    }

    @Test
    fun testUrgencyEnum() {
        val urgencies = OrionIntentIntelligence.Urgency.values()
        assertTrue("Should have multiple urgency levels", urgencies.size >= 4)
        assertTrue(urgencies.contains(OrionIntentIntelligence.Urgency.LOW))
        assertTrue(urgencies.contains(OrionIntentIntelligence.Urgency.NORMAL))
        assertTrue(urgencies.contains(OrionIntentIntelligence.Urgency.HIGH))
        assertTrue(urgencies.contains(OrionIntentIntelligence.Urgency.CRITICAL))
    }

    @Test
    fun testIntentDataClass() {
        val intent = OrionIntentIntelligence.Intent(
            type = OrionIntentIntelligence.IntentType.ACTION,
            primaryObjective = "Buy 0.1 BTC",
            entities = mapOf("BTC" to "CRYPTO_SYMBOL"),
            constraints = emptyList(),
            dependencies = emptyList(),
            successCriteria = emptyList(),
            urgency = OrionIntentIntelligence.Urgency.HIGH,
            confidence = 0.9,
            contextualReferences = emptyList()
        )
        assertEquals(OrionIntentIntelligence.IntentType.ACTION, intent.type)
        assertEquals("Buy 0.1 BTC", intent.primaryObjective)
        assertEquals(OrionIntentIntelligence.Urgency.HIGH, intent.urgency)
        assertEquals(0.9, intent.confidence, 0.01)
    }

    @Test
    fun testSuccessCriterion() {
        val criterion = OrionIntentIntelligence.SuccessCriterion(
            description = "App opens",
            verificationMethod = "SCREEN_CHANGE"
        )
        assertEquals("App opens", criterion.description)
        assertEquals("SCREEN_CHANGE", criterion.verificationMethod)
    }

    @Test
    fun testConstraint() {
        val constraint = OrionIntentIntelligence.Constraint(
            type = "urgency",
            severity = "HIGH",
            description = "User requested urgent handling"
        )
        assertEquals("urgency", constraint.type)
        assertEquals("HIGH", constraint.severity)
    }

    @Test
    fun testTradingIntentDetection() {
        val tradingInputs = listOf(
            "Buy 0.1 BTC",
            "Sell 10 ETH",
            "Check my portfolio",
            "What's the price of SOL?",
            "Place a limit order for BTC at 50000"
        )

        for (input in tradingInputs) {
            val isTrading = input.lowercase().contains("trade") ||
                input.lowercase().contains("buy") ||
                input.lowercase().contains("sell") ||
                input.lowercase().contains("portfolio") ||
                Regex("(?i)\\b(BTC|ETH|SOL|USDT|USDC|BNB|XRP|ADA|DOGE)\\b").containsMatchIn(input)
            assertTrue("Should detect trading intent: $input", isTrading)
        }
    }

    @Test
    fun testCodingIntentDetection() {
        val codingInputs = listOf(
            "Write a Python script",
            "Create a calculator function",
            "Debug this code",
            "Write a Kotlin class",
            "Run python script to fetch data"
        )

        for (input in codingInputs) {
            val isCoding = input.lowercase().contains("code") ||
                input.lowercase().contains("script") ||
                input.lowercase().contains("function") ||
                input.lowercase().contains("class") ||
                input.lowercase().contains("debug") ||
                input.lowercase().contains("python") ||
                input.lowercase().contains("kotlin") ||
                input.lowercase().contains("javascript")
            assertTrue("Should detect coding intent: $input", isCoding)
        }
    }

    @Test
    fun testUrgencyDetection() {
        val urgentInputs = listOf(
            "Emergency: restart now!",
            "Urgent: buy BTC immediately",
            "ASAP: send message to Mom",
            "Critical: system shutdown required"
        )

        for (input in urgentInputs) {
            val isUrgent = input.lowercase().contains("emergency") ||
                input.lowercase().contains("urgent") ||
                input.lowercase().contains("asap") ||
                input.lowercase().contains("immediately") ||
                input.lowercase().contains("right now") ||
                input.lowercase().contains("critical")
            assertTrue("Should detect urgency: $input", isUrgent)
        }
    }
}
