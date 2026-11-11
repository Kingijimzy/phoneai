package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic regression set for the local recall path. This is a retrieval benchmark,
 * not a claim about human-level semantic understanding or a trained embedding model.
 */
class OrionRecallEvaluationTest {
    private data class Scenario(val name: String, val fact: String, val query: String)

    private val scenarios = listOf(
        Scenario("football club", "My favourite football club is Real Madrid", "which football club is my favourite"),
        Scenario("food preference", "I prefer jollof rice for dinner", "what food do I prefer at dinner"),
        Scenario("home city", "I live in Benin City", "which city do I live in"),
        Scenario("coding language", "I am learning Kotlin for Android development", "what programming language am I learning for Android"),
        Scenario("trading market", "I trade forex and focus on liquidity sweeps", "which market do I trade and what setups do I study"),
        Scenario("crypto platform", "I use Bybit for crypto perpetual trading", "which exchange do I use for crypto perps"),
        Scenario("device", "My phone is a POCO F3 with a Snapdragon 870", "what phone and processor do I use"),
        Scenario("project identity", "Orion is my mobile personal assistant project", "what is my mobile assistant project called"),
        Scenario("memory preference", "I want important memories to require owner approval", "what is my preference for approving memories"),
        Scenario("voice feature", "I want Orion to support hands free voice interaction", "how should I interact with Orion by voice"),
        Scenario("work routine", "I usually study programming in the evening", "when do I usually study coding"),
        Scenario("music preference", "I enjoy listening to Afrobeats", "what music genre do I enjoy"),
        Scenario("travel interest", "I want to explore Japan someday", "which country do I hope to visit"),
        Scenario("learning goal", "I am learning Python automation", "which language am I learning for automation"),
        Scenario("risk control", "I never want trading orders placed without my confirmation", "what is my rule for confirming trades"),
        Scenario("chart timeframe", "I use the four hour chart to establish market bias", "which timeframe do I use for market direction"),
        Scenario("entry timeframe", "I look for forex entries on the fifteen minute chart", "what chart timeframe do I use for entries"),
        Scenario("project repository", "PhoneAI is the Android repository for Orion", "which repository contains the Orion Android app"),
        Scenario("assistant style", "I prefer concise and evidence backed answers", "how do I prefer answers to be written"),
        Scenario("privacy", "I do not want private memories sent to cloud models by default", "what is my default preference for private memory and cloud models"),
        Scenario("career", "I want to become a professional software developer", "what career am I working toward"),
        Scenario("fitness", "I prefer walking for light daily exercise", "what light exercise do I prefer"),
        Scenario("sleep", "I try to sleep before midnight", "what time do I try to go to bed"),
        Scenario("calendar", "I review economic news before major forex sessions", "when do I review economic news"),
        Scenario("browser", "I want browser tasks to preserve authenticated sessions", "what should happen to browser login sessions"),
        Scenario("memory history", "I want changed memories to retain their previous versions", "what should happen when a saved memory changes"),
        Scenario("task continuity", "Orion should resume interrupted objectives using verified checkpoints", "how should Orion recover an interrupted task"),
        Scenario("evidence", "A completed task must include evidence of its actual result", "what evidence is required before a task is complete"),
        Scenario("background", "I want proactive monitoring to respect quiet hours", "what restriction should proactive monitoring obey"),
        Scenario("permissions", "Sensitive device actions must ask me before execution", "which device actions need my permission"),
        Scenario("build target", "The Android app must support ARM64 devices", "which CPU architecture must the Android build support"),
        Scenario("performance", "I prefer smooth performance over unnecessary animations", "what is my priority for app performance and animation"),
        Scenario("trading journal", "I record trading wins and losses in a journal", "where do I record my trading outcomes"),
        Scenario("news event", "I watch CPI and NFP releases for forex volatility", "which economic releases do I watch for forex"),
        Scenario("development workflow", "I want automated unit tests before publishing an APK", "what checks should run before an APK is published"),
        Scenario("assistant control", "Orion must have a master switch to stop its runtime", "how should I completely disable Orion's runtime"),
        Scenario("local inference", "I prefer on device inference when a suitable model is available", "where do I prefer AI inference to run"),
        Scenario("learning feedback", "Orion should learn from verified successes and failures", "what outcomes should improve Orion's future decisions"),
        Scenario("truthfulness", "Orion must never claim a tool ran without execution evidence", "what should Orion never claim without proof"),
        Scenario("design", "I prefer a graphite interface with restrained accents", "what visual style do I prefer for the app")
    )

    data class Score(val scenarios: Int, val hitAt1: Int, val hitAt5: Int, val reciprocalRank: Double) {
        val hitAt1Rate: Double get() = hitAt1.toDouble() / scenarios
        val hitAt5Rate: Double get() = hitAt5.toDouble() / scenarios
        val meanReciprocalRank: Double get() = reciprocalRank / scenarios
    }

    /** Score actual ranked results. A missing target contributes zero to every metric. */
    private fun runBenchmark(): Score {
        var hit1 = 0
        var hit5 = 0
        var reciprocal = 0.0
        scenarios.forEachIndexed { index, scenario ->
            val target = Memory(index + 1, "fact", scenario.fact, 1L, 1L, false, "owner", 1.0, "general", "approved", "benchmark")
            val distractors = listOf(
                Memory(1001, "fact", "The weather forecast mentions rain tomorrow", 1L, 1L),
                Memory(1002, "fact", "A recipe uses tomatoes onions and rice", 1L, 1L),
                Memory(1003, "fact", "A computer monitor displays a desktop wallpaper", 1L, 1L)
            )
            val ranked = OrionRecall.top(listOf(target) + distractors, scenario.query, 5)
            val rank = ranked.indexOfFirst { it.memory.id == target.id }
            if (rank == 0) hit1++
            if (rank in 0..4) {
                hit5++
                reciprocal += 1.0 / (rank + 1)
            }
        }
        return Score(scenarios.size, hit1, hit5, reciprocal)
    }

    @Test fun benchmarkContainsFortyNamedScenarios() {
        assertEquals(40, scenarios.size)
        assertEquals(40, scenarios.map { it.name }.toSet().size)
    }

    @Test fun baselineRecallQualityIsRecordedAndMeetsMinimum() {
        val score = runBenchmark()
        println("ORION_RECALL_BASELINE scenarios=${score.scenarios} hit@1=${score.hitAt1Rate} hit@5=${score.hitAt5Rate} mrr=${score.meanReciprocalRank}")
        assertTrue("Recall benchmark should retrieve at least 90% of target facts in top five: $score", score.hitAt5Rate >= 0.90)
        assertTrue("Recall benchmark should rank at least 75% of target facts first: $score", score.hitAt1Rate >= 0.75)
    }
}
