package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionContextualIntentInterpreterTest {
    @Test fun resolvesContinueAgainstThePriorObjective() {
        val result = OrionContextualIntentInterpreter.resolve(
            "continue",
            listOf("user" to "Audit PhoneAI executors and fix all failing tests."),
            ""
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.CONTINUE_OBJECTIVE, result.kind)
        assertEquals("Audit PhoneAI executors and fix all failing tests.", result.resolvedObjective)
        assertFalse(result.needsClarification)
        assertTrue(result.contextBlock.contains("not an unrelated new command"))
    }

    @Test fun finishRemainingWorkKeepsTheExistingObjective() {
        val result = OrionContextualIntentInterpreter.resolve(
            "finish the remaining work",
            listOf("user" to "Implement contextual intent understanding in Orion."),
            ""
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.COMPLETE_REMAINING, result.kind)
        assertEquals("Implement contextual intent understanding in Orion.", result.resolvedObjective)
    }

    @Test fun fixThatResolvesToTheActiveTaskRatherThanAKeywordTool() {
        val result = OrionContextualIntentInterpreter.resolve(
            "fix that",
            emptyList(),
            "Repair Orion's task recovery and preserve its existing behavior."
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.CORRECT_PREVIOUS, result.kind)
        assertTrue(result.contextBlock.contains("Repair Orion's task recovery"))
        assertTrue(result.contextBlock.contains("not, by themselves, authorization"))
    }

    @Test fun testEverythingInheritsThePriorSubject() {
        val result = OrionContextualIntentInterpreter.resolve(
            "test everything",
            listOf("user" to "Complete the Telegram delivery verification implementation."),
            ""
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.TEST_OBJECTIVE, result.kind)
        assertEquals("Complete the Telegram delivery verification implementation.", result.resolvedObjective)
    }

    @Test fun capabilityWordsInsideAnEngineeringRequestDoNotBecomeToolIntent() {
        val request = "Audit Python, weather, Telegram and GitHub integrations and fix their tests."
        val result = OrionContextualIntentInterpreter.resolve(request, emptyList(), "")
        assertEquals(OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE, result.kind)
        assertEquals(request, result.resolvedObjective)
        assertTrue(result.contextBlock.contains("not, by themselves, authorization"))
    }

    @Test fun currentFollowUpInTranscriptDoesNotBecomeItsOwnObjective() {
        val result = OrionContextualIntentInterpreter.resolve(
            "test everything",
            listOf(
                "user" to "Audit all PhoneAI executors and repair failures.",
                "assistant" to "I found dispatch gaps.",
                "user" to "test everything"
            ),
            ""
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.TEST_OBJECTIVE, result.kind)
        assertEquals("Audit all PhoneAI executors and repair failures.", result.resolvedObjective)
    }

    @Test fun ambiguousFollowUpDoesNotInventItsTarget() {
        val result = OrionContextualIntentInterpreter.resolve("continue", emptyList(), "")
        assertEquals(OrionContextualIntentInterpreter.Kind.AMBIGUOUS_REFERENCE, result.kind)
        assertTrue(result.needsClarification)
        assertNull(result.resolvedObjective)
    }

    @Test fun standaloneNewRequestRemainsNewEvenWithPriorContext() {
        val result = OrionContextualIntentInterpreter.resolve(
            "What is the weather in Lagos today?",
            listOf("user" to "Audit the PhoneAI repository."),
            ""
        )
        assertEquals(OrionContextualIntentInterpreter.Kind.NEW_OBJECTIVE, result.kind)
        assertEquals("What is the weather in Lagos today?", result.resolvedObjective)
    }
}
