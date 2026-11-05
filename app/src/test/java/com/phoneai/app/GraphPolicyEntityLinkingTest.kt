package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphPolicyEntityLinkingTest {
    @Test fun entityLinksTraverseTwoHopsFromMentionedEntity() {
        val facts = listOf(
            "I support Arsenal",
            "Arsenal plays for Premier League",
            "Premier League is a competition"
        ).flatMap(GraphPolicy::extract)
            .fold(emptyList<GraphFact>()) { acc, triple ->
                GraphPolicy.merge(acc, triple.first, triple.second, triple.third, 1L)
            }

        val related = GraphPolicy.related(facts, "Tell me about Arsenal", limit = 10)
        assertTrue(related.any { it.a == "user" && it.b == "arsenal" })
        assertTrue(related.any { it.a == "arsenal" && it.b == "premier league" })
        assertTrue(related.any { it.a == "premier league" && it.b == "competition" })
    }

    @Test fun singleValuedEntityFactKeepsOnlyTheLatestCurrentValue() {
        var facts = GraphPolicy.merge(emptyList(), "user", "is", "a forex trader", 1L)
        facts = GraphPolicy.merge(facts, "user", "is", "a software developer", 2L)
        assertEquals(1, facts.count { it.a == "user" && it.rel == "is" })
        assertEquals(GraphPolicy.norm("a software developer"), facts.single().b)
    }

    @Test fun repeatedNonExclusivePreferencesRemainSeparateFacts() {
        var facts = GraphPolicy.merge(emptyList(), "user", "likes", "football", 1L)
        facts = GraphPolicy.merge(facts, "user", "likes", "music", 2L)
        assertEquals(2, facts.size)
    }
}
