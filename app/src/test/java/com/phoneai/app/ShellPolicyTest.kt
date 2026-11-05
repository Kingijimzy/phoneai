package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ShellPolicyTest {
    private fun r(c: String) = ShellPolicy.classify(c)
    @Test fun ordinaryMutationsInstallsScriptsAndDeletesRunWithoutApproval() {
        listOf("rm notes.txt", "rm -rf build", "python script.py", "pip install requests", "apk add nodejs npm",
            "npm install express", "git push", "curl https://example.com", "chmod +x run.sh",
            "echo x > /sdcard/a.txt", "cp a.txt ../b.txt", "find . -name '*.tmp' -delete",
            "ls \$(whoami)", "mv a b", "cat /sdcard/Download/readme.txt", "ls ../", "cat ~/notes.txt",
            "sudo ls", "su -c id", "settings put global x 1", "reboot", "shutdown")
            .forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }
    @Test fun onlyRootSystemWipesRawBlockWritesAndForkBombsAreHardBlocked() {
        listOf("rm -rf /", "rm -rf /*", "rm -rf /system", "rm -rf /vendor", "find / -type f -delete",
            "dd if=/dev/zero of=/dev/sda", "dd if=/dev/zero of=/dev/block/by-name/system",
            "echo x > /dev/block/mmcblk0", "mkfs.ext4 /dev/block/by-name/vendor",
            ":(){ :|:& };:", "fork(){ fork|fork& };fork").forEach { assertEquals(it, ShellRisk.BLOCKED, r(it)) }
    }
    @Test fun normalUserDataPathsAreNotClassifiedAsCatastrophic() {
        listOf("rm -rf ~/Downloads/old", "rm -rf /sdcard/Download/old", "dd if=file.img of=backup.img")
            .forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }
    @Test fun emptyCommandIsNotADeviceDestructionBlock() { assertEquals(ShellRisk.SAFE, r("")) }
    @Test fun clipKeepsHeadAndTail() {
        val s = "a".repeat(5000) + "b".repeat(5000); val c = ShellPolicy.clip(s, 1000)
        assertTrue(c.startsWith("a") && c.endsWith("b") && c.contains("omitted") && c.length < 1200)
    }
    @Test fun shellRoutingNeverRequestsApplicationApproval() {
        assertFalse(ShellExecutionRouting.requiresApproval("linux", "apk add python3"))
        assertFalse(ShellExecutionRouting.requiresApproval("termux", "rm file.txt"))
        assertFalse(ShellExecutionRouting.requiresApproval("builtin", "rm file.txt"))
    }
}
