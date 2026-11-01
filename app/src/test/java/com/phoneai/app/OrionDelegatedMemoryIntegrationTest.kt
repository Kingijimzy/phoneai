package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionDelegatedMemoryIntegrationTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MemoryStore.clear(context)
    }

    @After fun tearDown() {
        MemoryStore.clear(context)
    }

    @Test fun researchDelegationReceivesOnlyApprovedActiveNonSensitiveFacts() {
        val approved = MemoryStore.addOwnerConfirmed(
            context, "fact", "The user prefers concise technical explanations."
        )
        val pending = MemoryStore.add(
            context, "fact", "The user is considering a private investment."
        )
        val rejected = MemoryStore.addOwnerConfirmed(
            context, "fact", "A previously approved fact that the owner later rejected."
        )
        val sensitive = MemoryStore.addOwnerConfirmed(
            context, "fact", "The private API key is secret-value-123."
        )
        assertNotNull(approved)
        assertNotNull(pending)
        assertNotNull(rejected)
        assertNotNull(sensitive)
        assertTrue(MemoryStore.setReviewStatus(context, rejected!!.id, "rejected"))

        val research = requireNotNull(AgentProfiles.find("research"))
        val manifest = OrionAgentDelegation.create(research, "parent-task", "Research a technical question")
        val prompt = AgentRunner.systemPrompt(context, research, "technical question", manifest)

        assertTrue(prompt.contains("The user prefers concise technical explanations."))
        assertFalse(prompt.contains("The user is considering a private investment."))
        assertFalse(prompt.contains("previously approved fact"))
        assertFalse(prompt.contains("secret-value-123"))
    }

    @Test fun delegatedMemoryDoesNotExposeNonFactCategories() {
        MemoryStore.addOwnerConfirmed(context, "preference", "Use short checklists.")
        MemoryStore.addOwnerConfirmed(context, "fact", "The project uses Kotlin.")

        val research = requireNotNull(AgentProfiles.find("research"))
        val manifest = OrionAgentDelegation.create(research, "parent-task", "Research Kotlin")
        val prompt = AgentRunner.systemPrompt(context, research, "Kotlin", manifest)

        assertTrue(prompt.contains("The project uses Kotlin."))
        assertFalse(prompt.contains("Use short checklists."))
    }
    @Test fun plannerDelegationReceivesSanitizedTaskTitlesButNotFreeFormDetails() {
        val marker = "delegated-planner-private-marker"
        val task = TaskStore.add(
            context,
            "Review $marker",
            "private_detail_marker api_key=should-not-leak",
            "normal"
        )
        assertNotNull(task)

        val planner = requireNotNull(AgentProfiles.find("planner"))
        val manifest = OrionAgentDelegation.create(planner, "parent-task", "Plan the next work")
        val prompt = AgentRunner.systemPrompt(context, planner, "next work", manifest)

        assertTrue(prompt.contains("Review $marker"))
        assertFalse(prompt.contains("private_detail_marker"))
        assertFalse(prompt.contains("should-not-leak"))
    }

}
