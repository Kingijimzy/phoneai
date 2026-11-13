package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrreversibleActionConfirmationPolicyTest {
    @Test fun destructiveDataToolsRequireConfirmation() {
        listOf("workspace_delete", "task_delete", "trash_empty", "forget", "fact_forget", "macro_delete", "goal_remove", "project_delete", "wipe_data")
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

    @Test fun privateReadsWritesAndScriptExecutionRequireOwnerApproval() {
        listOf("workspace_read", "workspace_list", "find_files", "dev_read", "project_inspect",
            "github_account_read", "gmail_list_messages", "telegram_inbox", "read_notifications",
            "clipboard_read", "resolve_contact", "device_location")
            .forEach { tool ->
                assertNotNull("$tool must require owner approval",
                    IrreversibleActionConfirmationPolicy.ownerApprovalDescription(tool, JSONObject().put("path", "private/item.txt")))
            }
        listOf("workspace_write", "dev_write", "save_file", "project_create", "project_plan",
            "python_exec", "extension_run", "extension_test")
            .forEach { tool ->
                assertNotNull("$tool must require owner approval",
                    IrreversibleActionConfirmationPolicy.ownerApprovalDescription(tool, JSONObject().put("code", "print('test')")))
            }
        assertNotNull(IrreversibleActionConfirmationPolicy.ownerApprovalDescription(
            "shell", JSONObject().put("command", "printf hello > /workspace/note.txt")
        ))
        assertNotNull(IrreversibleActionConfirmationPolicy.ownerApprovalDescription(
            "shell", JSONObject().put("command", "python3 /workspace/task.py")
        ))
        assertNull(IrreversibleActionConfirmationPolicy.ownerApprovalDescription(
            "shell", JSONObject().put("command", "ls -la /workspace")
        ))
    }

    @Test fun connectedMcpReadRequiresOwnerApproval() {
        assertNotNull(IrreversibleActionConfirmationPolicy.ownerApprovalDescription(
            "mcp_read_resource", JSONObject().put("uri", "private://record")
        ))
    }

    @Test fun explicitDeleteAndWipeRequestsAreClassifiedButNegationsAreNot() {
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Delete the old report file"))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Clear app data for this app"))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Factory reset the phone"))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Remove all files in Downloads"))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveRequestDescription("Clear chat history"))
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
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "dev_shell", JSONObject().put("command", "rm -rf /workspace/cache")
        ))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "python_exec", JSONObject().put("code", """phone.shell("rm -rf /workspace/cache")""")
        ))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "standing_order", JSONObject().put("action", "remove").put("kind", "github_notifications")
        ))
        assertNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "shell", JSONObject().put("command", "ls -la /sdcard/Downloads")
        ))
    }

    @Test fun pythonPhoneShellChecksEveryEmbeddedCommandNotOnlyTheFirst() {
        val safeThenDelete = """phone.shell("ls /sdcard/Download")
phone.shell("rm -rf /sdcard/Download/old")"""
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "python_exec", JSONObject().put("code", safeThenDelete)
        ))
        val readsOnly = """phone.shell("ls /sdcard/Download")
phone.shell("cat /sdcard/Download/notes.txt")"""
        assertNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "python_exec", JSONObject().put("code", readsOnly)
        ))
    }

    @Test fun destructiveShellVariantsAreFlaggedWithoutFlaggingReadOnlyCommands() {
        listOf(
            "find /sdcard/Download -type f -delete",
            "unlink /sdcard/Download/old.txt",
            "rmdir /sdcard/Download/old-folder",
            "truncate -s 0 /sdcard/Download/log.txt",
            "blkdiscard /dev/block/sda",
            "fastboot erase userdata",
            "dd if=/dev/zero of=/dev/block/by-name/userdata"
        ).forEach { command ->
            assertNotNull(command, IrreversibleActionConfirmationPolicy.destructiveAction("shell", JSONObject().put("command", command)))
        }
        assertNull(IrreversibleActionConfirmationPolicy.destructiveAction("shell", JSONObject().put("command", "find /sdcard/Download -type f -print")))
        assertNull(IrreversibleActionConfirmationPolicy.destructiveAction("shell", JSONObject().put("command", "ls -la /sdcard/Download")))
        val remote = JSONObject().put("name", "run_command").put("arguments", JSONObject().put("command", "rm -rf /workspace/cache"))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction("mcp_call", remote))
    }

    @Test fun pythonAndRemoteHttpDeletesRequireConfirmation() {
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "python_exec", JSONObject().put("code", """import os
os.remove('/sdcard/old.txt')""")
        ))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "python_exec", JSONObject().put("code", """import shutil
shutil.rmtree('/sdcard/cache')""")
        ))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "web_api", JSONObject().put("method", "DELETE").put("url", "https://example.com/item/1")
        ))
        assertNull(IrreversibleActionConfirmationPolicy.destructiveAction(
            "web_api", JSONObject().put("method", "GET").put("url", "https://example.com/item/1")
        ))
    }

    @Test fun nestedMcpDestructiveOperationIsFlagged() {
        val args = JSONObject()
            .put("name", "workspace_delete")
            .put("arguments", JSONObject().put("path", "project/cache"))
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction("mcp_call", args))
    }
}
