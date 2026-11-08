package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for permission architecture - Trading Desk and Coach retain explicit approval,
 * all other capabilities run freely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionArchitectureTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testTradingPlaceOrderRegistered() {
        val tool = "trading_place_order"
        val toolDef = ToolRegistry.all.firstOrNull { it.name == tool }
        assertNotNull("trading_place_order should be registered", toolDef)
        assertEquals("Trading orders should be WRITE access", ToolAccess.WRITE, ToolRegistry.access(tool))
    }

    @Test
    fun testTradingReadOnlyToolsRegistered() {
        val readOnlyTools = listOf("trading_portfolio", "trading_positions", "trading_balance", "trading_ticker", "trading_market_info")

        for (tool in readOnlyTools) {
            val toolDef = ToolRegistry.all.firstOrNull { it.name == tool }
            if (toolDef != null) {
                assertEquals("Trading read-only tools should be READ access", ToolAccess.READ, ToolRegistry.access(tool))
            }
        }
    }

    @Test
    fun testCoachPackExists() {
        val coachPack = AgentPolicy.packs.firstOrNull { it.id == "coach" }
        assertNotNull("Coach pack should exist", coachPack)
    }

    @Test
    fun testGeneralCapabilitiesDefaultAllow() {
        val generalTools = listOf("open_app", "shell", "web_search", "memory", "task_add", "python_exec", "browser_navigate")

        for (tool in generalTools) {
            val toolDef = ToolRegistry.all.firstOrNull { it.name == tool }
            if (toolDef != null) {
                val mode = ToolPermissions.mode(context, tool)
                assertNotEquals("General tools should not default to DENY", ToolMode.DENY, mode)
            }
        }
    }

    @Test
    fun testIrreversibleActionsRequireConfirmation() {
        val irreversibleTools = listOf("computer_settings_restart", "computer_settings_shutdown", "computer_settings_wifi_toggle")

        for (tool in irreversibleTools) {
            val requiresConfirmation = IrreversibleActionConfirmationPolicy.requiresConfirmation(tool)
            assertTrue("Irreversible action $tool should require confirmation", requiresConfirmation)
        }
    }

    @Test
    fun testShellDestructiveCommandsBlocked() {
        val destructiveCommands = listOf(
            "rm -rf /",
            "dd if=/dev/zero of=/dev/sda",
            ":(){ :|:& };:",  // fork bomb
            "reboot -f",
            "factory_reset"
        )

        for (cmd in destructiveCommands) {
            val risk = ShellPolicy.classify(cmd)
            assertEquals("Destructive command should be BLOCKED: $cmd", ShellRisk.BLOCKED, risk)
        }
    }

    @Test
    fun testShellNormalCommandsAllowed() {
        val normalCommands = listOf("ls", "pwd", "echo hello", "cat file.txt", "python3 --version")

        for (cmd in normalCommands) {
            val risk = ShellPolicy.classify(cmd)
            assertEquals("Normal command should be SAFE: $cmd", ShellRisk.SAFE, risk)
        }
    }

    @Test
    fun testAndroidPermissionsEnforced() {
        assertTrue(ToolRegistry.requiresAccessibility("click"))
        assertTrue(ToolRegistry.requiresAccessibility("open_app"))
    }
}