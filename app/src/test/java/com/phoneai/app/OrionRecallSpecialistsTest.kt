package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionRecallSpecialistsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MemoryStore.clear(context)
        ExperienceStore.clear(context)
    }

    @After fun tearDown() {
        MemoryStore.clear(context)
        ExperienceStore.clear(context)
    }

    @Test fun specialistLanesRetrieveRelevantApprovedMemoriesWithSourceIds() {
        MemoryStore.addOwnerConfirmed(context, "preference", "My preferred programming language is Kotlin")
        MemoryStore.addOwnerConfirmed(context, "project", "Orion Kotlin Android project uses a central brain")
        MemoryStore.addOwnerConfirmed(context, "decision", "Decision: keep Orion's local memory on device")
        MemoryStore.addOwnerConfirmed(context, "conversation", "We discussed Kotlin Android memory retrieval")
        val approved = MemoryStore.all(context)
        val findings = OrionRecallSpecialists.retrieve(context, "Orion Kotlin Android memory", approved)
        assertTrue(findings.any { it.lane == "profile" })
        assertTrue(findings.any { it.lane == "project" })
        assertTrue(findings.any { it.lane == "decisions" })
        assertTrue(findings.any { it.lane == "episodes" })
        assertTrue(findings.flatMap { it.hits }.all { it.memory.id > 0 })
    }

    @Test fun noRelevantSpecialistEvidenceProducesNoSpecialistBlock() {
        MemoryStore.addOwnerConfirmed(context, "preference", "I enjoy cooking jollof rice")
        assertEquals("", OrionRecallSpecialists.block(context, "quantum helicopter", MemoryStore.all(context)))
    }

    @Test fun pendingMemoriesAreNotIncludedEvenIfPassedByCaller() {
        val pending = MemoryStore.add(context, "project", "Orion private pending project recall")!!
        val findings = OrionRecallSpecialists.retrieve(context, "Orion project recall", listOf(pending))
        assertTrue(findings.isEmpty())
    }
}
