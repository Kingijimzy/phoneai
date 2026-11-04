package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GithubConnectorSummaryTest {
    @Test fun authenticatedRepositoryListCanIncludeMoreThanTheOldFifteenItemLimit() {
        val repositories = JSONArray()
        for (i in 1..40) {
            val name = if (i == 37) "owner/PhoneAI" else "owner/project$i"
            repositories.put(
                JSONObject()
                    .put("full_name", name)
                    .put("private", true)
                    .put("default_branch", "main")
                    .put("html_url", "https://github.com/$name")
            )
        }

        val summary = GithubConnector.summarize(repositories.toString(), max = 16_000, itemLimit = 100)
        assertTrue(summary.contains("owner/PhoneAI"))
        assertTrue(summary.contains("private=true"))
        assertTrue(summary.contains("default_branch=main"))
    }

    @Test fun ordinaryGitHubSummariesRemainBoundedByDefault() {
        val repositories = JSONArray()
        for (i in 1..30) repositories.put(JSONObject().put("full_name", "owner/project$i"))
        val summary = GithubConnector.summarize(repositories.toString())
        assertTrue(summary.contains("owner/project15"))
        assertFalse(summary.contains("owner/project16"))
    }

    @Test fun authenticatedAccountEndpointsRemainExplicitlyAllowlisted() {
        assertTrue(GithubConnector.pathAllowed("/user"))
        assertTrue(GithubConnector.pathAllowed("/user/repos?per_page=100&sort=updated"))
        assertFalse(GithubConnector.pathAllowed("/user/repos/secret"))
    }
}
