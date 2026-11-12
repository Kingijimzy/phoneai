package com.phoneai.app

import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSessionVaultPolicyTest {
    @Test fun codexUsesPRootGuestExecutionAndOnlyOrionDynamicToolsForExecution() {
        val config = CodexSessionVault.configToml()
        assertTrue(config.contains("approval_policy = \"never\""))
        assertTrue(config.contains("sandbox_mode = \"danger-full-access\""))
        assertTrue(config.contains("[features]"))
        assertTrue(config.contains("shell_tool = true"))
        assertTrue(config.contains("view_image = false"))
        assertTrue(config.contains("web_search = false"))
        assertTrue(config.contains("[agents]"))
        assertTrue(config.contains("enabled = false"))
        assertTrue(!config.contains("workspace-write"))
    }
}
