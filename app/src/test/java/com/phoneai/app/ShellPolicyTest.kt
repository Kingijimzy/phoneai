package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ShellPolicyTest {
    private fun r(c: String) = ShellPolicy.classify(c)

    @Test fun ordinaryCommandsRemainSafe() {
        listOf("rm notes.txt", "rm -rf build", "python script.py", "pip install requests", "apk add nodejs npm",
            "npm install express", "git push", "curl https://example.com", "chmod +x run.sh",
            "echo x > /sdcard/a.txt", "find . -name '*.tmp' -delete", "settings put global x 1",
            "reboot", "shutdown").forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }

    @Test fun destructiveCommandsAreWarningsNotBlocks() {
        listOf("rm -rf /", "rm -rf /*", "rm -rf /system", "rm -rf /vendor", "find / -type f -delete",
            "dd if=/dev/zero of=/dev/sda", "dd if=/dev/zero of=/dev/block/by-name/system",
            "echo x > /dev/block/mmcblk0", "mkfs.ext4 /dev/block/by-name/vendor",
            ":(){ :|:& };:", "fork(){ fork|fork& };fork", "factory reset", "fastboot erase system",
            "settings put global device_provisioned 0").forEach { assertEquals(it, ShellRisk.WARNING, r(it)) }
    }

    @Test fun sensitiveStoragePathsWarnButDoNotBlock() {
        listOf("cat /data/data/com.phoneai.app/shared_prefs/phoneai.xml",
            "cat /data/misc/keystore/user_0/1000_USRPKEY_x",
            "grep -r orion_api_credentials /sdcard").forEach { assertEquals(it, ShellRisk.WARNING, r(it)) }
    }

    @Test fun warningsAreInformationalAndNeverRequireConfirmation() {
        val command = "rm -rf /system"
        assertNotNull(ShellPolicy.warning(command))
        assertTrue(ShellPolicy.warning(command)!!.contains("continuing"))
        assertNull(ShellPolicy.warning("ls -la"))
    }

    @Test fun linuxRoutingDoesNotAddASeparateRiskTier() {
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("linux"))
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("alpine"))
        assertEquals(ShellRisk.SAFE, r("apk add python3"))
        assertEquals(ShellRisk.WARNING, r("rm -rf /system"))
    }

    @Test fun clipKeepsHeadAndTail() {
        val s = "a".repeat(5000) + "b".repeat(5000)
        val c = ShellPolicy.clip(s, 1000)
        assertTrue(c.startsWith("a") && c.endsWith("b") && c.contains("omitted") && c.length < 1200)
    }
}
