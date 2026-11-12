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

    @Test fun olderHistoricalValueCannotReplaceNewerCurrentValue() {
        var facts = GraphPolicy.merge(emptyList(), "user", "lives in", "Lagos", 20L)
        facts = GraphPolicy.merge(facts, "user", "lives in", "Abuja", 10L)
        assertEquals(1, facts.count { it.a == "user" && it.rel == "lives in" })
        assertEquals("lagos", facts.single().b)
        assertEquals(20L, facts.single().lastAt)
    }

    @Test fun autoIndexedOwnerMemoryPrefixesBecomeQueryableRelationships() {
        val facts = listOf(
            "Owner said: my name is Kingi Jimzy",
            "User preference: forex and crypto trading",
            "Ongoing project: Orion mobile assistant",
            "User goal: build a reliable coding agent"
        ).flatMap(GraphPolicy::extract)
        assertTrue(facts.contains(Triple("user", "name", "kingi jimzy")))
        assertTrue(facts.contains(Triple("user", "prefers", "forex and crypto trading")))
        assertTrue(facts.contains(Triple("user", "works on", "orion mobile assistant")))
        assertTrue(facts.contains(Triple("user", "wants", "build a reliable coding agent")))
    }

    @Test fun explicitTripletsUseTheSameGraphFactParser() {
        assertEquals(
            listOf(Triple("kingi jimzy", "favourite sport", "football")),
            GraphPolicy.extract("Kingi Jimzy — favourite_sport — football")
        )
    }

    @Test fun repeatedNonExclusivePreferencesRemainSeparateFacts() {
        var facts = GraphPolicy.merge(emptyList(), "user", "likes", "football", 1L)
        facts = GraphPolicy.merge(facts, "user", "likes", "music", 2L)
        assertEquals(2, facts.size)
    }
}
