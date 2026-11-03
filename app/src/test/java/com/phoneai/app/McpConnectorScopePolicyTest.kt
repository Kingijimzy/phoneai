package com.phoneai.app

import org.junit.Assert.assertEquals
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

    @Test fun unclassifiedToolsDoNotInheritReadScope() {
        assertEquals("other", McpConnector.scopeForTool("custom_magic_tool"))
    }
}
