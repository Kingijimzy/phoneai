package com.phoneai.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSearchQueryPolicyTest {
    @Test fun rejectsPlannerFillerWordsBeforeNetworkSearch() {
        assertTrue(GitHubSearchQueryPolicy.rejection("and").orEmpty().contains("filler word"))
        assertTrue(GitHubSearchQueryPolicy.rejection("project").orEmpty().contains("filler word"))
    }

    @Test fun acceptsMeaningfulRepositoryQueries() {
        assertNull(GitHubSearchQueryPolicy.rejection("Kingijimzy phoneai Android assistant"))
        assertNull(GitHubSearchQueryPolicy.rejection("phoneai"))
    }
}
