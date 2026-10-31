package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionCapabilityDiscoveryTest {
    private fun tool(name: String, access: ToolAccess = ToolAccess.READ) =
        ToolDefinition(name, "$name {}", "test", access)

    @Test fun everyRegisteredBuiltInToolHasExactlyOneCapabilityPackMapping() {
        val missing = ToolRegistry.all.map { it.name }.filter { AgentPolicy.packForTool(it) == null }
        assertTrue("Unmapped registered tools: $missing", missing.isEmpty())
        assertEquals(ToolRegistry.all.size, ToolRegistry.all.map { it.name }.distinct().size)
    }

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

    @Test fun grantedAndroidPermissionsMakeMatchingCapabilitiesAvailable() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(
                tool("resolve_contact"),
                tool("read_notifications"),
                tool("media_status"),
                tool("media_control", ToolAccess.WRITE),
                tool("device_location")
            ),
            setOf("messaging", "phone_control"),
            termuxReady = true,
            permissionStates = mapOf(
                "contacts" to OrionCapabilityDiscovery.PermissionState.GRANTED,
                "notification_access" to OrionCapabilityDiscovery.PermissionState.GRANTED,
                "location" to OrionCapabilityDiscovery.PermissionState.GRANTED
            )
        )
        assertEquals(5, snapshot.availableCount)
        assertTrue(snapshot.tools.all { it.available })
    }

    @Test fun screenControlIsNotEligibleUntilAccessibilityAndDisclosureAreVerified() {
        val pack = AgentPolicy.packForTool("tap") ?: "phone_control"
        val unknown = OrionCapabilityDiscovery.discover(
            listOf(tool("tap", ToolAccess.WRITE)),
            setOf(pack),
            termuxReady = true
        )
        assertFalse(unknown.tools.single().available)
        assertTrue(unknown.tools.single().reason.contains("accessibility"))

        val granted = OrionCapabilityDiscovery.discover(
            listOf(tool("tap", ToolAccess.WRITE)),
            setOf(pack),
            termuxReady = true,
            permissionStates = mapOf("accessibility" to OrionCapabilityDiscovery.PermissionState.GRANTED)
        )
        assertTrue(granted.tools.single().available)
    }

    @Test fun missingLocationPermissionNamesTheExactPrerequisite() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("device_location")),
            setOf("phone_control"),
            termuxReady = true,
            permissionStates = mapOf("location" to OrionCapabilityDiscovery.PermissionState.MISSING)
        )
        assertFalse(snapshot.tools.single().available)
        assertTrue(snapshot.tools.single().reason.contains("location"))
    }

    @Test fun availableToolRequiresRegistryAndEnabledPack() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("weather")),
            setOf("trading_research"),
            termuxReady = true
        )
        assertEquals(1, snapshot.availableCount)
        assertTrue(snapshot.promptLines().any { it.contains("weather(read,available_limited,not-live-tested)") })
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

    @Test fun enabledGrantedCapabilityTellsPlannerToUseItWithoutInventingExtraRestrictions() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("weather")),
            setOf("trading_research"),
            termuxReady = true
        )
        val prompt = snapshot.promptLines().joinToString("\n")
        assertTrue(snapshot.tools.single().available)
        assertTrue(prompt.contains("GRANTED CAPABILITY RULE"))
        assertTrue(prompt.contains("use it fully"))
        assertTrue(prompt.contains("Do not invent extra application-level restrictions"))
        assertTrue(prompt.contains("actual Android OS/runtime permissions"))
        assertTrue(prompt.contains("explicit confirmations required for consequential actions"))
    }

    @Test fun promptNeverConfusesEligibilityWithLiveExecutionVerification() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("weather")),
            setOf("trading_research"),
            termuxReady = true
        )
        val prompt = snapshot.promptLines().joinToString("\n")
        assertTrue(prompt.contains("eligible under current settings"))
        assertTrue(prompt.contains("runtime health verified=0"))
        assertTrue(prompt.contains("not proof of live execution"))
        assertTrue(prompt.contains("Never describe an eligible tool as tested"))
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
    @Test fun discoveryUsesCapabilityRegistryAsTheSingleStateAuthority() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("weather")),
            setOf("trading_research"),
            termuxReady = true
        )
        val fact = snapshot.tools.single()
        assertEquals(CapabilityRegistry.State.AVAILABLE_LIMITED, fact.state)
        assertTrue(fact.available)
        assertTrue(fact.reason.contains("not tested yet"))
    }

    @Test fun explicitToolDenyIsReflectedInTheLiveCapabilitySnapshot() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("shell", ToolAccess.WRITE)),
            setOf("workspace_shell"),
            termuxReady = false,
            deniedTools = setOf("shell")
        )
        val fact = snapshot.tools.single()
        assertFalse(fact.available)
        assertEquals(CapabilityRegistry.State.DISABLED, fact.state)
        assertTrue(fact.reason.contains("explicitly set"))
    }

    @Test fun duplicateToolDefinitionsAreReportedInsteadOfSilentlyHidden() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("weather"), tool("weather", ToolAccess.WRITE)),
            setOf("trading_research"),
            termuxReady = true
        )
        assertEquals(1, snapshot.registeredCount)
        assertTrue(snapshot.blockers.any { it.contains("duplicate tool registrations") })
    }

    @Test fun missingPackAndMissingPermissionResolveThroughRegistryPrecedence() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("resolve_contact")),
            emptySet(),
            termuxReady = true,
            permissionStates = mapOf("contacts" to OrionCapabilityDiscovery.PermissionState.MISSING)
        )
        assertEquals(CapabilityRegistry.State.DISABLED, snapshot.tools.single().state)
        assertTrue(snapshot.tools.single().reason.contains("disabled"))
    }

}
