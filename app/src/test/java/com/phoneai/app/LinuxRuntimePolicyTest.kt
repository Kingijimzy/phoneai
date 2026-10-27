package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class LinuxRuntimePolicyTest {
    private val good = LinuxRuntimePolicy.Manifest("3.20.3", "https://dl-cdn.alpinelinux.org/x.tar.gz", "a".repeat(64), 3_000_000L)

    @Test fun acceptsAPinnedHttpsManifest() {
        assertNull(LinuxRuntimePolicy.validate(good))
    }

    @Test fun rejectsUnsafeManifests() {
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsUrl = "http://example.com/a.tgz")))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsUrl = "https://example.com/a b.tgz")))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsSha256 = "ABC")))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsSha256 = "")))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(version = "../etc")))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsBytes = 0)))
        assertNotNull(LinuxRuntimePolicy.validate(good.copy(rootfsBytes = LinuxRuntimePolicy.MAX_ROOTFS_BYTES + 1)))
    }

    @Test fun sha256MatchesTheStandardTestVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LinuxRuntimePolicy.sha256Hex("abc".toByteArray()))
    }

    @Test fun downloadsMustMatchTheirChecksumExactly() {
        val bytes = "abc".toByteArray()
        assertTrue(LinuxRuntimePolicy.checksumMatches(LinuxRuntimePolicy.sha256Hex(bytes), bytes))
        assertFalse(LinuxRuntimePolicy.checksumMatches("0".repeat(64), bytes))
        assertFalse(LinuxRuntimePolicy.checksumMatches("not-a-hash", bytes))
    }

    @Test fun readyOnlyForTheInstalledVersion() {
        assertTrue(LinuxRuntimePolicy.isReady("3.20.3", good))
        assertFalse(LinuxRuntimePolicy.isReady("3.19.0", good))
        assertFalse(LinuxRuntimePolicy.isReady(null, good))
    }

    @Test fun prootRunsOnlyWithAFixedSafeShape() {
        val args = LinuxRuntimePolicy.prootArgs("/data/app/lib/libproot.so", "/data/files/linux/rootfs", "ls -la")!!
        assertEquals("-r", args[1])
        assertEquals("/data/files/linux/rootfs", args[2])
        assertEquals(listOf("/bin/sh", "-c", "ls -la"), args.takeLast(3))
        assertFalse(args.contains("/sdcard"))
        assertFalse(args.any { it.contains("/data/data") })
    }

    @Test fun prootRefusesUnsafeInput() {
        assertNull(LinuxRuntimePolicy.prootArgs("proot", "/rootfs", "ls"))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "rootfs", "ls"))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "/rootfs/../x", "ls"))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "/rootfs", "   "))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "/rootfs", "a".repeat(LinuxRuntimePolicy.MAX_COMMAND + 1)))
        assertNull(LinuxRuntimePolicy.prootArgs("/bin/proot", "/rootfs", "ls\u0000"))
    }
}
