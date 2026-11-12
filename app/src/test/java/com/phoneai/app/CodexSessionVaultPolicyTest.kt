package com.phoneai.app

import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSessionVaultPolicyTest {
    @Test fun codexUsesWorkspaceSandboxAndOnlyOrionDynamicToolsForExecution() {
        val config = CodexSessionVault.configToml()
        assertTrue(config.contains("approval_policy = \"on-request\""))
        assertTrue(config.contains("sandbox_mode = \"workspace-write\""))
        assertTrue(config.contains("[features]"))
        assertTrue(config.contains("shell_tool = false"))
        assertTrue(config.contains("view_image = false"))
        assertTrue(config.contains("web_search = false"))
        assertTrue(config.contains("[agents]"))
        assertTrue(config.contains("enabled = false"))
        assertTrue(!config.contains("danger-full-access"))
    }
}
