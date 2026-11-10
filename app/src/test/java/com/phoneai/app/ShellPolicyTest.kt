package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ShellPolicyTest {
    private fun r(c: String) = ShellPolicy.classify(c)
    @Test fun ordinaryMutationsAndDeletesAreNotHardBlocked() {
        listOf("rm notes.txt", "rm -rf build", "python script.py", "pip install requests", "apk add nodejs npm",
            "npm install express", "git push", "curl https://example.com", "chmod +x run.sh",
            "echo x > /sdcard/a.txt", "cp a.txt ../b.txt", "find . -name '*.tmp' -delete",
            "ls \$(whoami)", "mv a b", "cat /sdcard/Download/readme.txt", "ls ../", "cat ~/notes.txt",
            "sudo ls", "su -c id", "settings put global x 1", "reboot", "shutdown")
            .forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }
    @Test fun destructiveWipesAreConfirmationGatedButForkBombsAreHardBlocked() {
        listOf("rm -rf /", "rm -rf /*", "rm -rf /system", "rm -rf /vendor", "find / -type f -delete",
            "dd if=/dev/zero of=/dev/sda", "dd if=/dev/zero of=/dev/block/by-name/system",
            "echo x > /dev/block/mmcblk0", "mkfs.ext4 /dev/block/by-name/vendor")
            .forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
        listOf(":(){ :|:& };:", "fork(){ fork|fork& };fork")
            .forEach { assertEquals(it, ShellRisk.BLOCKED, r(it)) }
    }
    @Test fun normalUserDataPathsAreNotClassifiedAsCatastrophic() {
        listOf("rm -rf ~/Downloads/old", "rm -rf /sdcard/Download/old", "dd if=file.img of=backup.img")
            .forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }

    @Test fun quotedAndEscapedDestructiveCommandsRemainConfirmationGated() {
        listOf(
            "rm -rf '/system'",
            "rm -rf \"/vendor\"",
            "r\\m -rf /system",
            "find '/' -type f -delete",
            "dd if=image.img of='/dev/block/by-name/system'",
            "cp image.img /dev/mmcblk0",
            "truncate -s 0 /dev/block/by-name/vendor",
            "echo x > '/dev/block/mmcblk0'",
            """python3 -c "open('/dev/block/mmcblk0','wb').write(b'x')" """,
            ":(){ :|:& };:"
        ).forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }

    @Test fun readingBlockDeviceIsNotMistakenForAWrite() {
        listOf("cat /dev/block/by-name/system", "dd if=/dev/mmcblk0 of=backup.img")
            .forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }

    @Test fun emptyCommandIsNotADeviceDestructionBlock() { assertEquals(ShellRisk.SAFE, r("")) }
    @Test fun clipKeepsHeadAndTail() {
        val s = "a".repeat(5000) + "b".repeat(5000); val c = ShellPolicy.clip(s, 1000)
        assertTrue(c.startsWith("a") && c.endsWith("b") && c.contains("omitted") && c.length < 1200)
    }
    @Test fun linuxRoutingUsesTheSameDeleteConfirmationPolicy() {
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("linux"))
        assertEquals(ShellExecutionRouting.Backend.LINUX, ShellExecutionRouting.backend("alpine"))
        assertEquals(ShellRisk.SAFE, ShellPolicy.classify("apk add python3"))
        assertEquals(ShellRisk.SAFE, ShellPolicy.classify("python3 run_script.py"))
        assertEquals(ShellRisk.SAFE, ShellPolicy.classify("rm -rf /system"))
        assertNotNull(IrreversibleActionConfirmationPolicy.approvalReason(
            "shell", org.json.JSONObject().put("command", "rm -rf /system"), "workspace_shell"))
    }

    @Test fun factoryResetAttemptsRequireConfirmation() {
        listOf("factory reset", "factory_reset", "wipe data", "wipe.cache", "format userdata",
            "erase all", "recovery --wipe data", "recovery --wipe cache", "recovery --wipe all",
            "fastboot erase system", "fastboot format userdata", "fastboot wipe data",
            "settings put global device_provisioned 0",
            "settings put secure setup_wizard_finished 0").forEach {
                assertEquals(it, ShellRisk.SAFE, r(it))
                assertNotNull(IrreversibleActionConfirmationPolicy.approvalReason("shell", org.json.JSONObject().put("command", it), "workspace_shell"))
            }
    }

    @Test fun normalSettingsChangesAreNotFactoryReset() {
        listOf("settings put global wifi_on 1", "settings put secure location_mode 3",
            "getprop ro.build.version.sdk").forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }

    @Test fun quotedFactoryResetCommandsRequireConfirmation() {
        listOf("factory 'reset'", "wipe \"data\"", "format '/data'").forEach {
            assertEquals(it, ShellRisk.SAFE, r(it))
            assertNotNull(IrreversibleActionConfirmationPolicy.approvalReason("shell", org.json.JSONObject().put("command", it), "workspace_shell"))
        }
    }

    @Test fun keyStorageReadsAreBlockedButOrdinaryCommandsAreNot() {
        listOf(
            "cat /data/data/com.phoneai.app/shared_prefs/phoneai.xml",
            "ls /data/user/0/com.phoneai.app/files",
            "cat /data/misc/keystore/user_0/1000_USRPKEY_x",
            "grep -r orion_api_credentials /sdcard"
        ).forEach { assertEquals(it, ShellRisk.BLOCKED, r(it)) }
        listOf("ls /sdcard", "keytool -list -keystore app.jks", "python3 script.py").forEach { assertEquals(it, ShellRisk.SAFE, r(it)) }
    }
}
