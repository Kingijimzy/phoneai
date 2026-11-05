package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutonomyGoalStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteFile("orion_autonomy_goals.json")
        context.deleteFile("orion_autonomy_goals.json.bak")
    }

    @After fun tearDown() {
        context.deleteFile("orion_autonomy_goals.json")
        context.deleteFile("orion_autonomy_goals.json.bak")
    }

    @Test fun addPersistsGoalAndEnforcesOwnerControlledInterval() {
        val added = AutonomyGoalStore.add(context, "Summarise my unread email", 60)
        assertTrue(added.startsWith("Scheduled g1"))
        val loaded = AutonomyGoalStore.all(context).single()
        assertEquals("Summarise my unread email", loaded.prompt)
        assertEquals(60, loaded.everyMinutes)
        assertTrue(loaded.enabled)
    }

    @Test fun claimIsDurableAndPreventsDuplicateExecution() {
        AutonomyGoalStore.add(context, "Summarise my unread email", 60)
        val now = 1_800_000L
        val claimed = AutonomyGoalStore.claimNextDue(context, now)
        assertNotNull(claimed)
        assertEquals("started", AutonomyGoalStore.all(context).single().lastResult)
        assertNull("A second tick must not claim the same goal", AutonomyGoalStore.claimNextDue(context, now + 1L))
        assertTrue(AutonomyGoalStore.recordResult(context, claimed!!.id, "submitted_to_agent"))
        val reloaded = AutonomyGoalStore.all(context).single()
        assertEquals(now, reloaded.lastRunAt)
        assertEquals("submitted_to_agent", reloaded.lastResult)
        assertNull("The interval must begin at the durable claim time", AutonomyGoalStore.claimNextDue(context, now + 59 * 60_000L))
        assertNotNull(AutonomyGoalStore.claimNextDue(context, now + 60 * 60_000L))
    }

    @Test fun pausedGoalsAreNotClaimed() {
        AutonomyGoalStore.add(context, "Summarise my unread email", 60)
        assertTrue(AutonomyGoalStore.setEnabled(context, "g1", false).startsWith("Paused"))
        assertNull(AutonomyGoalStore.claimNextDue(context, 1_800_000L))
    }

    @Test fun goalLimitIsEnforcedAndRemovalPersists() {
        repeat(AutonomyGoalPolicy.MAX_GOALS) {
            assertTrue(AutonomyGoalStore.add(context, "Goal number $it", 60).startsWith("Scheduled"))
        }
        assertTrue(AutonomyGoalStore.add(context, "One extra goal", 60).startsWith("error:"))
        assertTrue(AutonomyGoalStore.remove(context, "g3").startsWith("Removed"))
        assertEquals(4, AutonomyGoalStore.all(context).size)
    }

    @Test fun corruptScheduleIsNotMistakenForEmptyAndIsNotOverwritten() {
        val original = "{not valid json"
        context.openFileOutput("orion_autonomy_goals.json", Context.MODE_PRIVATE).use {
            it.write(original.toByteArray(Charsets.UTF_8))
        }
        val result = AutonomyGoalStore.add(context, "Summarise my unread email", 60)
        assertTrue(result.startsWith("error: scheduled goal storage is unreadable"))
        val preserved = context.openFileInput("orion_autonomy_goals.json").bufferedReader().use { it.readText() }
        assertEquals(original, preserved)
    }
}
