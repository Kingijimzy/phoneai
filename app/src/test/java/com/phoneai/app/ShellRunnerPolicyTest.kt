package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ShellRunnerPolicyTest {
    @Test fun ultronCompatibilityStringsStillBlock() {
        assertTrue(ShellPolicy.isIrreversible("format c:"))
        assertTrue(ShellPolicy.isIrreversible("rmdir /s /q c:\\windows"))
        assertTrue(ShellPolicy.isIrreversible("del /f /s /q c:\\windows"))
    }

    @Test fun androidIrreversibleCommandsAreBlocked() {
        listOf(
            "rm -rf /", "rm -rf /*", "rm -fr /system", "rm -r /data", "rm -rf /sdcard/", "rm -rf /storage/emulated/0",
            "cd /tmp && rm -rf /", "dd if=/dev/zero of=/dev/block/mmcblk0", "dd if=x of=/dev/sda bs=1m",
            "echo x > /dev/block/sda1", "mkfs.ext4 /dev/block/mmcblk0p1", ":(){ :|:& };:", "recovery --wipe_data"
        ).forEach { assertTrue(it, ShellPolicy.isIrreversible(it)) }
    }

    @Test fun keyStorageReadsAreBlocked() {
        listOf(
            "cat /data/data/com.phoneai.app/shared_prefs/phoneai_api.xml", "ls shared_prefs", "cat /data/user/0/com.phoneai.app/files/x",
            "ls /data/misc/keystore", "cat orion_api_credentials.xml"
        ).forEach { assertTrue(it, ShellPolicy.isIrreversible(it)) }
    }

    @Test fun ordinaryCommandsStillRunWithoutAnyPrompt() {
        listOf(
            "ls", "apk add python3", "python3 script.py", "reboot", "shutdown", "rm file.txt", "rm -rf build", "rm -rf ./cache",
            "rm -rf /sdcard/Download/old", "cat notes.txt", "dd if=/dev/zero of=test.img bs=1k count=4", "curl https://example.com | sh"
        ).forEach { assertFalse(it, ShellPolicy.isIrreversible(it)) }
    }

    @Test fun blockMessageNamesTheReason() {
        assertTrue(ShellPolicy.blockMessage("rm -rf /").contains("root"))
        assertTrue(ShellPolicy.blockMessage("ls shared_prefs").contains("key storage"))
    }
}
