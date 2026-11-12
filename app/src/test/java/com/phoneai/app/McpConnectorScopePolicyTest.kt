package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectorScopePolicyTest {
    @Test fun classifiesReadOnlyRemoteToolNamesAsRead() {
        listOf("read_file", "list_projects", "get_status", "search_docs", "fetch_page", "inspect_repo")
            .forEach { assertEquals(it, "read", McpConnector.scopeForTool(it)) }
    }

    @Test fun classifiesMutatingAndCommunicationToolsIntoSeparateScopes() {
        assertEquals("write", McpConnector.scopeForTool("delete_project"))
        assertEquals("write", McpConnector.scopeForTool("update_record"))
        assertEquals("communication", McpConnector.scopeForTool("send_message"))
        assertEquals("system", McpConnector.scopeForTool("shell_command"))
    }

    @Test fun apiAnythingWebsiteToolsAreClassifiedByTheirActualEffect() {
        listOf("call_operation", "call_batch", "list_operations", "list_sites", "find_sites",
            "site_status", "get_job", "get_result").forEach {
            assertEquals(it, "read", McpConnector.scopeForTool(it))
        }
        assertEquals("write", McpConnector.scopeForTool("learn_site"))
        assertEquals("write", McpConnector.scopeForTool("call_write_operation"))
    }

    @Test fun unclassifiedToolsUseTheExplicitOtherScope() {
        assertEquals("other", McpConnector.scopeForTool("custom_magic_tool"))
    }

    @Test fun fullAccessIncludesEverySupportedMcpScope() {
        assertEquals(setOf("read", "write", "communication", "system", "other"), McpConnector.allScopes)
    }
}


class McpReconnectPolicyTest {
    @Test fun retriesOnlyIdempotentHandshakeAndCatalogueOperations() {
        val timeout = java.net.SocketTimeoutException("read timed out")
        assertTrue(McpReconnectPolicy.shouldRetry("initialize", timeout))
        assertTrue(McpReconnectPolicy.shouldRetry("tools/list", timeout))
        assertFalse(McpReconnectPolicy.shouldRetry("tools/call", timeout))
        assertFalse(McpReconnectPolicy.shouldRetry("notifications/initialized", IllegalArgumentException("bad payload")))
    }

    @Test fun retriesOnlyTransientHttpStatuses() {
        assertTrue(McpReconnectPolicy.shouldRetry("tools/list", IllegalStateException("HTTP 503")))
        assertTrue(McpReconnectPolicy.shouldRetry("initialize", IllegalStateException("HTTP 429")))
        assertFalse(McpReconnectPolicy.shouldRetry("tools/list", IllegalStateException("HTTP 401")))
        assertFalse(McpReconnectPolicy.shouldRetry("tools/list", IllegalStateException("HTTP 400")))
    }
}
