package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionRecallTest {
    private lateinit var context: Context

    @Before fun setUp() { context = RuntimeEnvironment.getApplication(); MemoryStore.clear(context); ExperienceStore.clear(context) }
    @After fun tearDown() { MemoryStore.clear(context); ExperienceStore.clear(context) }

    private fun mem(id: Int, text: String, pinned: Boolean = false) =
        Memory(id, "fact", text, 1_700_000_000_000L, 1_700_000_000_000L, pinned, "owner", 1.0, "general", "approved", "")

    @Test fun aFactStoredOnceIsRecalledFromAParaphrasedQuestion() {
        MemoryStore.addOwnerConfirmed(context, "fact", "My favourite team is Real Madrid")
        MemoryStore.addOwnerConfirmed(context, "fact", "I love jollof rice for dinner")
        val block = OrionRecall.forTurn(context, "which club do I support?")
        assertTrue(block, block.contains("Real Madrid"))
        assertFalse(block, block.contains("jollof"))
        assertTrue(block.startsWith("RECALL FOR THIS MESSAGE"))
    }

    @Test fun returnsAtMostSixAndRanksTheBestFirst() {
        val memories = (1..10).map { mem(it, "My favourite team number $it is Real Madrid club support") } + mem(11, "Jollof rice")
        val hits = OrionRecall.top(memories, "which club do I support")
        assertEquals(6, hits.size)
        assertTrue(hits.none { it.memory.id == 11 })
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test fun entityLinksPullInRelatedMemories() {
        val memories = listOf(mem(1, "Planning a trip to Lagos in December"), mem(2, "My sister Amaka lives in Lagos"), mem(3, "I like jollof rice"))
        val hits = OrionRecall.top(memories, "status of the Lagos trip")
        assertEquals(setOf(1, 2), hits.map { it.memory.id }.toSet())
        assertTrue(hits.first { it.memory.id == 1 }.why.contains("linked"))
    }

    @Test fun provenanceGraphCanRecoverApprovedMemoryIntoAutomaticRecall() {
        val saved = MemoryStore.addOwnerConfirmed(context, "project", "Orion phone assistant uses a local knowledge graph for long term recall")
            ?: error("memory write failed")
        val approved = MemoryStore.all(context).first { it.id == saved.id }
        val hits = OrionRecall.top(context, listOf(approved), "Orion knowledge graph")
        assertTrue(hits.any { it.memory.id == saved.id && it.why.contains("knowledge-graph") })
    }

    @Test fun unreviewedAndSensitiveMemoryNeverReachesTheRecall() {
        MemoryStore.add(context, "fact", "My favourite team is Real Madrid")
        assertEquals("", OrionRecall.forTurn(context, "which club do I support?"))
    }

    @Test fun nothingRelevantMeansNoBlock() {
        MemoryStore.addOwnerConfirmed(context, "fact", "I love jollof rice for dinner")
        assertEquals("", OrionRecall.forTurn(context, "zebra quantum helicopter"))
        assertEquals("", OrionRecall.forTurn(context, "hi"))
    }
}
