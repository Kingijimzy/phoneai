package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoulSubagentCoordinatorTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SoulSubagentCoordinator.clearForTest(context)
    }

    @Test fun coordinatorRunsFourBoundedSpecialistsAndPersistsReport() {
        val coreBefore = AgentPolicy.enabled(context, "orion_core")
        val report = SoulSubagentCoordinator.run(context, force = true)
        assertEquals(4, report.findings.size)
        assertEquals(
            setOf(
                SoulSubagentCoordinator.Role.PLANNER,
                SoulSubagentCoordinator.Role.CAPABILITY_AUDITOR,
                SoulSubagentCoordinator.Role.SAFETY_REVIEWER,
                SoulSubagentCoordinator.Role.MEMORY_CURATOR
            ),
            report.findings.map { it.role }.toSet()
        )
        assertTrue(report.summary.contains("No external model calls"))
        assertTrue(SoulSubagentCoordinator.latestReport(context).contains("CAPABILITY_AUDITOR"))
        assertTrue(SoulSubagentCoordinator.latestReport(context).contains("SAFETY_REVIEWER"))
        assertEquals(coreBefore, AgentPolicy.enabled(context, "orion_core"))
    }

    @Test fun cooldownPreventsRepeatedAutomaticRuns() {
        val first = SoulSubagentCoordinator.run(context, force = true)
        assertEquals(4, first.findings.size)
        val second = SoulSubagentCoordinator.run(context)
        assertTrue(second.findings.isEmpty())
        assertTrue(second.summary.contains("cooldown"))
    }

    @Test fun specialistsDoNotEnableDisabledPacks() {
        AgentPolicy.setEnabled(context, "gaming_hobby", false)
        val report = SoulSubagentCoordinator.run(context, force = true)
        assertTrue(report.findings.any { it.role == SoulSubagentCoordinator.Role.CAPABILITY_AUDITOR })
        assertFalse(AgentPolicy.enabled(context, "gaming_hobby"))
        assertTrue(SoulSubagentCoordinator.latestReport(context).contains("Disabled packs"))
    }
}
