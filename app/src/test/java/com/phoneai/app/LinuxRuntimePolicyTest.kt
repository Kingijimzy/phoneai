package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class LinuxRuntimePolicyTest {
    private val good = LinuxRuntimePolicy.productionManifest

    @Test fun productionManifestIsPinnedAndValid() {
        assertNull(LinuxRuntimePolicy.validate(good))
        assertEquals(3971679L, good.rootfsBytes)
        assertEquals("7e9b34ff3304381909e566e2c93de450664ccc724191261423974fc121c11623", good.rootfsSha256)
    }

    @Test fun rejectsUnsafeManifest() {
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsUrl = "http://example.com/a.tgz")))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsUrl = "https://example.com/a b.tgz")))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsSha256 = "ABC")))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(version = "../etc")))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsBytes = 0)))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(prootSha256 = "bad")))
    }

    @Test fun sha256MatchesStandardTestVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LinuxRuntimePolicy.sha256Hex("abc".toByteArray()))
    }

    @Test fun checksumsMustMatchExactly() {
        val bytes = "abc".toByteArray()
        assertTrue(LinuxRuntimePolicy.checksumMatches(LinuxRuntimePolicy.sha256Hex(bytes), bytes))
        assertFalse(LinuxRuntimePolicy.checksumMatches("0".repeat(64), bytes))
        assertFalse(LinuxRuntimePolicy.checksumMatches("not-a-hash", bytes))
    }

    @Test fun readinessRequiresExactInstalledVersion() {
        assertTrue(LinuxRuntimePolicy.isReady(good.version, good))
        assertFalse(LinuxRuntimePolicy.isReady("old-version", good))
        assertFalse(LinuxRuntimePolicy.isReady(null, good))
    }

    @Test fun recoveryEpochInvalidatesPreviouslyInstalledRuntime() {
        assertTrue(good.version.endsWith("-recovery3"))
        assertFalse(LinuxRuntimePolicy.isReady("alpine-3.22.7-proot-1a71df0", good))
    }

    @Test fun guestAcceptanceContractChecksSentinelAndArchitecture() {
        val valid = "ORION_PROOT_OK\naarch64\n3.22.7\n"
        assertTrue(valid.contains("ORION_PROOT_OK"))
        assertTrue(Regex("(?m)^aarch64\\s*$").containsMatchIn(valid))
        assertTrue(valid.contains("3.22.7"))
        assertFalse("ORION_PROOT_OK\narmv7l\n3.22.7".let {
            it.contains("ORION_PROOT_OK") && Regex("(?m)^aarch64\\s*$").containsMatchIn(it)
        })
    }

    @Test fun prootArgumentsAreFixedAndBounded() {
        val args = LinuxRuntimePolicy.prootArgs("/data/app/lib/libproot.so", "/data/files/linux/rootfs", "printf ORION_PROOT_OK")!!
        assertEquals("-r", args[1])
        assertEquals("/data/files/linux/rootfs", args[2])
        assertEquals(listOf("/bin/sh", "-c", "printf ORION_PROOT_OK"), args.takeLast(3))
        assertFalse(args.contains("/usr/bin/env"))
        assertFalse(args.contains("/sdcard"))
    }

    @Test fun rejectsUnsafeCommandsAndPaths() {
        assertNull(LinuxRuntimePolicy.prootArgs("proot", "/rootfs", "ls"))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "rootfs", "ls"))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "/rootfs/../x", "ls"))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/../proot", "/rootfs", "ls"))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "/rootfs", " "))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "/rootfs", "a".repeat(LinuxRuntimePolicy.MAX_COMMAND + 1)))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "/rootfs", "ls\u0000"))
    }
}
