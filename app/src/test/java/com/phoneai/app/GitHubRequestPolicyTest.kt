package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRequestPolicyTest {
    @Test fun exactRepositoryUrlWinsOverGenericWordRepoCode() {
        val targets = GitHubRequestPolicy.resolveAll(
            "Check my GitHub account repo code and inspect https://github.com/Kingijimzy/phoneai/"
        )
        assertEquals(1, targets.size)
        assertEquals("Kingijimzy", targets.single().owner)
        assertEquals("phoneai", targets.single().repository)
        assertEquals(GitHubRequestPolicy.Section.REPOSITORY, targets.single().section)
    }

    @Test fun actionsLandingPageResolvesToLatestWorkflowRuns() {
        val target = GitHubRequestPolicy.resolveAll(
            "Inspect https://github.com/Kingijimzy/phoneai/actions"
        ).single()
        assertEquals("Kingijimzy", target.owner)
        assertEquals("phoneai", target.repository)
        assertEquals(GitHubRequestPolicy.Section.RUNS, target.section)
        assertNull(target.runId)
    }

    @Test fun exactActionsRunUrlPreservesRunId() {
        val target = GitHubRequestPolicy.resolveAll(
            "Inspect https://github.com/Kingijimzy/phoneai/actions/runs/123456789"
        ).single()
        assertEquals(GitHubRequestPolicy.Section.RUN, target.section)
        assertEquals("123456789", target.runId)
    }

    @Test fun multipleUrlsForSameRepositoryKeepRepositoryAndActionsTargets() {
        val targets = GitHubRequestPolicy.resolveAll(
            "Audit https://github.com/Kingijimzy/phoneai/ and https://github.com/Kingijimzy/phoneai/actions"
        )
        assertEquals(2, targets.size)
        assertTrue(targets.any { it.section == GitHubRequestPolicy.Section.REPOSITORY })
        assertTrue(targets.any { it.section == GitHubRequestPolicy.Section.RUNS })
    }

    @Test fun trailingPunctuationDoesNotBreakUrlParsing() {
        val target = GitHubRequestPolicy.resolveAll(
            "Please inspect https://github.com/Kingijimzy/phoneai/actions."
        ).single()
        assertEquals("phoneai", target.repository)
        assertEquals(GitHubRequestPolicy.Section.RUNS, target.section)
    }

    @Test fun genericWordsAreNotTreatedAsRepositoryNames() {
        assertTrue(GitHubRequestPolicy.resolveAll("Check my GitHub account repo code and inspect everything").isEmpty())
        assertTrue(GitHubRequestPolicy.resolveAll("Review the entire source code").isEmpty())
    }

    @Test fun explicitRepositoryNameStillWorks() {
        val target = GitHubRequestPolicy.resolveAll("Inspect my GitHub repository named PhoneAI").single()
        assertEquals("PhoneAI", target.repository)
        assertEquals(GitHubRequestPolicy.Section.REPOSITORY, target.section)
    }

    @Test fun standaloneProjectInspectionRoutesDirectlyEvenWithConjunctions() {
        assertTrue(GitHubRequestPolicy.shouldRouteDirectly(
            "Investigate my PhoneAI project and identify its weaknesses."
        ))
    }

    @Test fun repositoryInspectionWithMemoryAndReminderRemainsCompound() {
        assertTrue(!GitHubRequestPolicy.shouldRouteDirectly(
            "Investigate my PhoneAI project, save important lessons to memory and remind me tomorrow."
        ))
    }

    @Test fun nonGithubAndUnsafeSchemesAreIgnored() {
        assertTrue(GitHubRequestPolicy.resolveAll("https://example.com/owner/repo").isEmpty())
        assertTrue(GitHubRequestPolicy.resolveAll("javascript://github.com/owner/repo").isEmpty())
    }
}
