package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectorScopeTest {
    @Test fun classifiedReadToolsDoNotRequirePerCallApproval() {
        listOf("get_user", "list_repositories", "read_file", "search_issues", "fetch_status", "inspect_project")
            .forEach { name ->
                assertEquals("read", McpConnector.scopeForTool(name))
                assertFalse("$name should not prompt for read-only access", McpConnector.requiresUserApproval(name))
            }
    }

    @Test fun writeCommunicationSystemAndUnknownToolsRequireApproval() {
        val expected = mapOf(
            "create_issue" to "write",
            "delete_branch" to "write",
            "send_message" to "communication",
            "publish_post" to "communication",
            "device_status" to "system",
            "unknown_capability" to "other"
        )
        expected.forEach { (name, scope) ->
            assertEquals(name, scope, McpConnector.scopeForTool(name))
            assertTrue("$name must require approval", McpConnector.requiresUserApproval(name))
        }
    }
}
