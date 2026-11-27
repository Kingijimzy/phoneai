package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ContextRelevanceTest {
    private val trading = "TRADING DESK\n" + "btc eth futures positions leverage funding ".repeat(40)
    private val sensors = "SENSOR TELEMETRY\n" + "battery gyroscope barometer thermal ".repeat(40)
    private val core = "ORION GRASP EXECUTIVE CYCLE\n" + "objective state ".repeat(60)
    private val small = "NOTE: tiny"

    private fun split(req: String) = ContextRelevance.split(listOf(core, trading, sensors, small), req)

    @Test fun unrelatedLargeSectionsAreDeferredNotDeleted() {
        val s = split("refactor the kotlin parser module please")
        assertTrue(s.deferred.any { it.startsWith("TRADING DESK") })
        assertTrue(s.deferred.any { it.startsWith("SENSOR TELEMETRY") })
        assertTrue(s.keep.contains(core)); assertTrue(s.deferred.contains(small))
    }

    @Test fun relevanceEmergesFromTheTaskNotAKeywordTable() {
        val s = split("check my leverage on the futures positions")
        assertTrue(s.keep.any { it.startsWith("TRADING DESK") })
        assertTrue(s.deferred.any { it.startsWith("SENSOR TELEMETRY") })
    }

    @Test fun shortReferenceKeepsEverythingBecauseNothingCanBeRuledOut() {
        val s = split("continue")
        assertTrue(s.deferred.isEmpty())
    }

    @Test fun deferredSectionsAreRetrievableOnDemand() {
        OrionBrain.rememberDeferred(listOf(trading, sensors))
        assertTrue(OrionBrain.contextOnDemand("trading").contains("futures"))
        assertTrue(OrionBrain.contextOnDemand("nope").startsWith("error"))
        assertTrue(OrionBrain.contextOnDemand("").contains("SENSOR TELEMETRY"))
    }

    @Test fun workflowEvalPromptShrinksAndKeepsTheTaskContext() {
        val sections = listOf(core, trading, sensors, small, "MEMORY: kotlin parser conventions\n" + "parser module tokens grammar ".repeat(40))
        val req = "fix the kotlin parser grammar bug in the module"
        val full = OrionSessionSnapshot(req, "s", 1L, sections, emptyList()).render(60_000)
        assertTrue(full.contains("MEMORY: kotlin parser conventions"))
        assertFalse(full.contains("btc eth futures"))
        assertTrue(full.contains("not_pushed_because_unrelated_to_this_request="))
        assertTrue(full.contains("brain {action:\"context\""))
    }
    @Test fun shortGreetingsDoNotMasqueradeAsFollowUps() {
        val greeting = split("Hey bro")
        assertTrue(greeting.keep.contains(core))
        assertTrue(greeting.deferred.contains(small))
        assertTrue(greeting.deferred.any { it.startsWith("TRADING DESK") })
        assertTrue(greeting.deferred.any { it.startsWith("SENSOR TELEMETRY") })

        val casual = split("How are you?")
        assertTrue(casual.deferred.any { it.startsWith("TRADING DESK") })
        assertTrue(casual.deferred.any { it.startsWith("SENSOR TELEMETRY") })
        assertTrue(casual.deferred.contains(small))
    }

}
