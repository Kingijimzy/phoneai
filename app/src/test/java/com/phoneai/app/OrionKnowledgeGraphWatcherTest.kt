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
class OrionKnowledgeGraphWatcherTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MemoryStore.clear(context)
        OrionKnowledgeGraph.clear(context)
        OrionKnowledgeGraphWatcher.clearForTest(context)
    }

    @After fun tearDown() {
        MemoryStore.clear(context)
        OrionKnowledgeGraph.clear(context)
        OrionKnowledgeGraphWatcher.clearForTest(context)
    }

    @Test fun watcherCooldownIsSixHoursAndRetriesAfterFailedWindow() {
        assertTrue(OrionKnowledgeGraphWatcher.shouldRun(100L, 0L))
        assertFalse(OrionKnowledgeGraphWatcher.shouldRun(100L, 99L))
        assertFalse(OrionKnowledgeGraphWatcher.shouldRun(100L, 100L))
        assertTrue(OrionKnowledgeGraphWatcher.shouldRun(
            100L + OrionKnowledgeGraphWatcher.COOLDOWN_MS,
            100L
        ))
    }

    @Test fun watcherRebuildsGraphOnlyFromApprovedActiveMemories() {
        val approved = MemoryStore.addOwnerConfirmed(
            context, "project", "Orion local graph watcher repairs approved memory recall"
        ) ?: error("approved memory write failed")
        val pending = MemoryStore.add(
            context, "project", "Unreviewed secret project graph memory should not be indexed"
        ) ?: error("pending memory write failed")

        // Simulate a missing derived graph index; the watcher must rebuild it from source memory.
        OrionKnowledgeGraph.clear(context)
        val report = OrionKnowledgeGraphWatcher.run(context, force = true)
        assertEquals(0, report.failures)
        assertTrue(report.indexedMemories >= 1)

        val hits = OrionKnowledgeGraph.relatedMemoryIds(context, "Orion graph watcher", 12)
        assertTrue(hits.contains(approved.id))
        assertFalse(hits.contains(pending.id))
    }

    @Test fun successfulRunAdvancesCooldownCursor() {
        OrionKnowledgeGraphWatcher.run(context, force = true)
        val next = OrionKnowledgeGraphWatcher.run(context)
        assertTrue(next.summary.contains("six-hour"))
        assertEquals(0, next.indexedMemories)
    }
}
