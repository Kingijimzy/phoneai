package com.phoneai.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetIntegrityTest {
    @Test fun sha256MatchesKnownFixture() {
        val file = File.createTempFile("orion-integrity", ".txt")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AssetIntegrity.sha256(file))
            assertTrue(AssetIntegrity.matchesSha256(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
            assertFalse(AssetIntegrity.matchesSha256(file, "0000000000000000000000000000000000000000000000000000000000000000"))
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsMalformedExpectedDigest() {
        val file = File.createTempFile("orion-integrity", ".txt")
        try {
            file.writeText("abc")
            assertFalse(AssetIntegrity.matchesSha256(file, "not-a-sha256"))
        } finally {
            file.delete()
        }
    }
}
