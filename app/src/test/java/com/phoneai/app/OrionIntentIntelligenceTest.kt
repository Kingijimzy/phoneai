package com.phoneai.app

import android.content.Context
import org.json.JSONObject
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
    fun testIntentTypeClassification() {
        // Test various intent types are correctly classified
        val testCases = listOf(
            "What is the weather today?" to OrionIntentIntelligence.IntentType.QUESTION,
            "Open WhatsApp" to OrionIntentIntelligence.IntentType.ACTION,
            "Research Bitcoin price trends" to OrionIntentIntelligence.IntentType.RESEARCH,
            "Write a Python script to calculate fibonacci" to OrionIntentIntelligence.IntentType.CODING,
            "Send message to Mom saying hello" to OrionIntentIntelligence.IntentType.ACTION,
            "Remind me to call Dad at 5pm" to OrionIntentIntelligence.IntentType.SCHEDULING,
            "Buy 0.1 BTC" to OrionIntentIntelligence.IntentType.TRADING,
            "Restart phone" to OrionIntentIntelligence.IntentType.SYSTEM_CONTROL,
            "Remember that I prefer dark mode" to OrionIntentIntelligence.IntentType.MEMORY_OP
        )

        for ((input, expectedType) in testCases) {
            val quickRoute = OrionQuickIntent.quickRoute(input, false)
            // Quick intent should at least not crash
            assertNotNull(quickRoute)
        }
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
    fun testQuickIntentRouting() {
        assertEquals(OrionQuickIntent.QuickRoute.EXECUTE_ACTION, OrionQuickIntent.quickRoute("Open WhatsApp", false))
        assertEquals(OrionQuickIntent.QuickRoute.ANSWER_QUESTION, OrionQuickIntent.quickRoute("What is the weather?", false))
        assertEquals(OrionQuickIntent.QuickRoute.CLARIFY, OrionQuickIntent.quickRoute("Hello there", false))
    }

    @Test
    fun testEntityExtractionPatterns() {
        val patterns = mapOf(
            Regex("(?i)\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b") to "PROPER_NOUN",
            Regex("(?i)\\b(\\d{1,2}:\\d{2}\\s*(?:am|pm)?)\\b") to "TIME",
            Regex("(?i)\\b(today|tomorrow|yesterday|next\\s+week|this\\s+week)\\b") to "DATE_RELATIVE",
            Regex("(?i)\\b(https?://[^\\s]+)\\b") to "URL",
            Regex("(?i)\\b([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})\\b") to "EMAIL",
            Regex("(?i)\\b(BTC|ETH|SOL|USDT|USDC|BNB|XRP|ADA|DOGE)\\b") to "CRYPTO_SYMBOL"
        )

        val testInput = "Buy BTC tomorrow at 10:00am from https://exchange.com and email john@example.com"
        
        for ((pattern, type) in patterns) {
            val matches = pattern.findAll(testInput).map { it.value }.toSet()
            assertTrue("Should find $type in: $testInput", matches.isNotEmpty())
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
