package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionCapabilityDiscoveryTest {
    private fun tool(name: String, access: ToolAccess = ToolAccess.READ) =
        ToolDefinition(name, "$name {}", "test", access)

    @Test fun disabledPackBlocksRegisteredTool() {
        val snapshot = OrionCapabilityDiscovery.discover(listOf(tool("weather")), emptySet())
        assertEquals(0, snapshot.availableCount)
        assertTrue(snapshot.tools.single().reason.contains("disabled"))
    }

    @Test fun removedTermuxToolFailsClosedInsteadOfBeingAdvertised() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("termux_command", ToolAccess.WRITE)),
            setOf("workspace_shell")
        )
        assertFalse(snapshot.tools.single().available)
        assertTrue(snapshot.tools.single().reason.contains("fail closed"))
    }

    @Test fun accessibilityToolsRequireAcceptedScreenDisclosure() {
        val missing = OrionCapabilityDiscovery.discover(
            listOf(tool("read_screen")),
            setOf("phone_control"),
            permissionStates = mapOf("screen_access_disclosure" to OrionCapabilityDiscovery.PermissionState.MISSING)
        )
        assertFalse(missing.tools.single().available)
        assertTrue(missing.tools.single().reason.contains("disclosure has not been accepted"))

        val unknown = OrionCapabilityDiscovery.discover(listOf(tool("read_screen")), setOf("phone_control"))
        assertFalse(unknown.tools.single().available)
        assertTrue(unknown.tools.single().reason.contains("unverified"))
    }

    @Test fun builtInShellIsAvailableWhenItsPackIsEnabled() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("shell", ToolAccess.WRITE)),
            setOf("workspace_shell")
        )
        assertTrue(snapshot.tools.single().available)
        assertEquals(1, snapshot.availableCount)
    }

    @Test fun unmappedToolFailsClosed() {
        val snapshot = OrionCapabilityDiscovery.discover(listOf(tool("invented_tool")), setOf("orion_core"))
        assertFalse(snapshot.tools.single().available)
        assertTrue(snapshot.tools.single().reason.contains("fail closed"))
    }

    @Test fun missingAndroidPermissionBlocksSensitiveCapability() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("resolve_contact")),
            setOf("messaging"),
            permissionStates = mapOf("contacts" to OrionCapabilityDiscovery.PermissionState.MISSING)
        )
        assertFalse(snapshot.tools.single().available)
        assertTrue(snapshot.tools.single().reason.contains("permission"))
    }

    @Test fun unknownPermissionIsNotReportedAsGranted() {
        val snapshot = OrionCapabilityDiscovery.discover(listOf(tool("read_notifications")), setOf("phone_control"))
        assertFalse(snapshot.tools.single().available)
        assertTrue(snapshot.tools.single().reason.contains("unverified"))
    }

    @Test fun availableToolRequiresRegistryAndEnabledPack() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("weather")),
            setOf("trading_research")
        )
        assertEquals(1, snapshot.availableCount)
        assertTrue(snapshot.promptLines().any { it.contains("weather(read)") })
    }

    @Test fun integrationStateIsReportedAsSuppliedAndNotInvented() {
        val snapshot = OrionCapabilityDiscovery.discover(
            listOf(tool("weather")),
            setOf("trading_research"),
            integrationStates = mapOf("github" to "saved-unverified")
        )
        assertTrue(snapshot.promptLines().any { it.contains("github=saved-unverified") })
        assertFalse(snapshot.promptLines().any { it.contains("github=connected") })
    }
}
