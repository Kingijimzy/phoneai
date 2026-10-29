package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionCapabilityDiscoveryTest {
    private fun tool(name: String, access: ToolAccess = ToolAccess.READ) =
        ToolDefinition(name, "$name {}", "test", access)

    @Test fun disabledPackBlocksRegisteredTool() {
        val snapshot = OrionCapabilityDiscovery.discover(listOf(tool("weather")), emptySet(), termuxReady = true)
        assertEquals(0, snapshot.availableCount)
        assertTrue(snapshot.tools.single().reason.contains("disabled"))
    }

    @Test fun enabledPackDoesNotMakeTermuxReadyByItself() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("termux_command", ToolAccess.WRITE)),
            setOf("termux_bridge"),
            termuxReady = false
        )
        assertFalse(snapshot.tools.single().available)
        assertTrue(snapshot.tools.single().reason.contains("not ready"))
    }

    @Test fun builtInShellDoesNotDependOnTermuxReadiness() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("shell", ToolAccess.WRITE)),
            setOf("workspace_shell"),
            termuxReady = false
        )
        assertTrue(snapshot.tools.single().available)
        assertEquals(1, snapshot.availableCount)
    }

    @Test fun unmappedToolFailsClosed() {
        val snapshot = OrionCapabilityDiscovery.discover(listOf(tool("invented_tool")), setOf("orion_core"), true)
        assertFalse(snapshot.tools.single().available)
        assertTrue(snapshot.tools.single().reason.contains("fail closed"))
    }

    @Test fun missingAndroidPermissionBlocksSensitiveCapability() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("resolve_contact")),
            setOf("messaging"),
            termuxReady = true,
            permissionStates = mapOf("contacts" to OrionCapabilityDiscovery.PermissionState.MISSING)
        )
        assertFalse(snapshot.tools.single().available)
        assertTrue(snapshot.tools.single().reason.contains("permission"))
    }

    @Test fun unknownPermissionIsNotReportedAsGranted() {
        val snapshot = OrionCapabilityDiscovery.discover(listOf(tool("read_notifications")), setOf("phone_control"), true)
        assertFalse(snapshot.tools.single().available)
        assertTrue(snapshot.tools.single().reason.contains("unverified"))
    }

    @Test fun availableToolRequiresRegistryAndEnabledPack() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("weather")),
            setOf("trading_research"),
            termuxReady = true
        )
        assertEquals(1, snapshot.availableCount)
        assertTrue(snapshot.promptLines().any { it.contains("weather(read)") })
    }

    @Test fun mcpCallIsBlockedUntilEndpointIsConfiguredAndPaired() {
        val pack = AgentPolicy.packForTool("mcp_call") ?: "connectors"
        val unconfigured = OrionCapabilityDiscovery.discover(
            listOf(tool("mcp_call", ToolAccess.WRITE)),
            setOf(pack),
            termuxReady = true,
            integrationStates = mapOf("mcp_endpoint" to "not-configured", "mcp_pairing" to "not-paired")
        )
        assertFalse(unconfigured.tools.single().available)
        assertTrue(unconfigured.tools.single().reason.contains("endpoint"))
        val configuredButUnpaired = OrionCapabilityDiscovery.discover(
            listOf(tool("mcp_call", ToolAccess.WRITE)),
            setOf(pack),
            termuxReady = true,
            integrationStates = mapOf("mcp_endpoint" to "configured", "mcp_pairing" to "not-paired")
        )
        assertFalse(configuredButUnpaired.tools.single().available)
        assertTrue(configuredButUnpaired.tools.single().reason.contains("paired"))
    }

    @Test fun mcpCallCanBeEligibleOnlyWhenEndpointIsConfiguredAndPaired() {
        val pack = AgentPolicy.packForTool("mcp_call") ?: "connectors"
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("mcp_call", ToolAccess.WRITE)),
            setOf(pack),
            termuxReady = true,
            integrationStates = mapOf("mcp_endpoint" to "configured", "mcp_pairing" to "paired")
        )
        assertTrue(snapshot.tools.single().available)
    }

    @Test fun integrationStateIsReportedAsSuppliedAndNotInvented() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("weather")),
            setOf("trading_research"),
            termuxReady = true,
            integrationStates = mapOf("github" to "saved-unverified")
        )
        assertTrue(snapshot.promptLines().any { it.contains("github=saved-unverified") })
        assertFalse(snapshot.promptLines().any { it.contains("github=connected") })
    }
}
