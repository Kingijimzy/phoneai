package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrreversibleActionConfirmationPolicyTest {
    @Test fun destructiveDataToolsRequireConfirmation() {
        listOf("workspace_delete", "task_delete", "trash_empty", "forget", "macro_delete", "goal_remove", "project_delete", "wipe_data")
            .forEach { tool ->
                assertNotNull("$tool must require confirmation",
                    IrreversibleActionConfirmationPolicy.destructiveAction(tool, JSONObject().put("path", "Downloads/report.txt")))
            }
    }

    @Test fun ordinaryToolsAndComputerSettingsRemainUnblocked() {
        listOf("telegram_send", "notification_action", "mcp_call", "sms", "open_app", "computer_settings", "python_exec")
            .forEach { tool ->
                assertNull("$tool must not be treated as destructive by default",
                    IrreversibleActionConfirmationPolicy.destructiveAction(tool, JSONObject().put("action", "restart")))
            }
        assertFalse(IrreversibleActionConfirmationPolicy.requiresConfirmation("Restart computer or change Wi-Fi"))
    }

    @Test fun explicitDeleteAndWipeRequestsAreClassifiedButNegationsAreNot() {
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Delete the old report file"))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Clear app data for this app"))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Factory reset the phone"))
        assertNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Do not delete the report"))
        assertNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Keep the files without removing anything"))
    }

    @Test fun destructiveShellCommandsAreFlaggedButNormalCommandsAreNot() {
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "shell", JSONObject().put("command", "rm -rf /sdcard/Downloads/old")
        ))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "termux_command", JSONObject().put("command", "sudo wipefs /dev/block/sda")
        ))
        assertNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "shell", JSONObject().put("command", "ls -la /sdcard/Downloads")
        ))
    }

    @Test fun nestedMcpDestructiveOperationIsFlagged() {
        val args = JSONObject()
            .put("name", "workspace_delete")
            .put("arguments", JSONObject().put("path", "project/cache"))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction("mcp_call", args))
    }
}
