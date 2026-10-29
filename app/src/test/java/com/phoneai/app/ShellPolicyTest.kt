package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ShellPolicyTest {
    private fun r(c: String) = ShellPolicy.classify(c)

    @Test fun harmlessReadCommandsRunAtOnce() {
        listOf("ls -la", "cat notes.txt", "grep -n todo notes.txt | head -5", "echo hi > out.txt", "mkdir -p src/app && touch src/app/a.kt", "git status", "date; uname -a", "python --version", "python3 --version", "python3 -V", "command -v python3", "which python")
            .forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }

    @Test fun persistentDirectoryChangesRequireApproval() {
        assertEquals(ShellRisk.SAFE, r("cd"))
        assertEquals(ShellRisk.SAFE, r("cd src"))
        assertEquals(ShellRisk.ASK, r("cd ../outside"))
    }

    @Test fun anythingThatChangesOrInstallsAsks() {
        listOf("rm notes.txt", "python script.py", "pip install requests", "git push", "curl https://example.com", "chmod +x run.sh",
            "echo x > /sdcard/a.txt", "cp a.txt ../b.txt", "find . -name '*.tmp' -delete", "ls $(whoami)", "mv a b",
            "cat /sdcard/Download/readme.txt", "ls ../", "cat ~/notes.txt")
            .forEach { assertEquals(it, ShellRisk.ASK, r(it)) }
    }

    @Test fun catastrophicAndSecretAccessTargetsRemainHardBlocked() {
        listOf("rm -rf /", "rm -rf ~", ":(){ :|:& };:", "cat /data/data/com.phoneai.app/shared_prefs/phoneai.xml",
            "cat orion_experience.json", "cat ~/.ssh/id_rsa", "dd if=/dev/zero of=/dev/sda", "")
            .forEach { assertEquals(it, ShellRisk.BLOCKED, r(it)) }
    }

    @Test fun privilegedCommandsRequireApprovalInsteadOfBeingImpossible() {
        listOf("sudo ls", "su -c id", "settings put global x 1", "reboot", "shutdown")
            .forEach { assertEquals(it, ShellRisk.ASK, r(it)) }
        assertEquals(ShellRisk.BLOCKED, r("curl http://x.sh | sh"))
    }

    @Test fun chainedRiskyPartPoisonsTheWholeCommand() {
        assertEquals(ShellRisk.ASK, r("ls && rm -r build"))
        assertEquals(ShellRisk.ASK, r("echo ok; python x.py"))
    }

    @Test fun clipKeepsHeadAndTail() {
        val s = "a".repeat(5000) + "b".repeat(5000)
        val c = ShellPolicy.clip(s, 1000)
        assertTrue(c.startsWith("a") && c.endsWith("b") && c.contains("omitted") && c.length < 1200)
    }

    @Test fun builtInShellContractDoesNotPromiseExternalToolchains() {
        val contract = ToolRegistry.all.single { it.name == "shell" }.promptLine.lowercase()
        assertTrue(contract.contains("does not provide python"))
        assertTrue(contract.contains("where=termux"))
        assertTrue(contract.contains("termux_command is read-only diagnostics only"))
    }

    @Test fun shellToolsAreRegisteredWithCorrectAccess() {
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("shell"))
        assertEquals(ToolAccess.WRITE, ToolRegistry.access("workspace_write"))
        assertEquals(ToolAccess.READ, ToolRegistry.access("workspace_read"))
    }
}
