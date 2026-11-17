package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionMeshTest {
    private val secret = OrionMesh.deriveSecret("123456", "phone", "pc")

    @Test fun bothSidesDeriveTheSameSecret() {
        assertTrue(secret.contentEquals(OrionMesh.deriveSecret("123456", "pc", "phone")))
        assertTrue(!secret.contentEquals(OrionMesh.deriveSecret("654321", "pc", "phone")))
    }

    @Test fun validMessageIsAcceptedOnceOnly() {
        val g = OrionMesh.Guard(secret)
        val e = OrionMesh.sign(secret, "pc", "task.submit", "summarise inbox", 1_000L, "n1")
        assertEquals(OrionMesh.Verdict.OK, g.check(e, 1_500L))
        assertEquals(OrionMesh.Verdict.REPLAY, g.check(e, 1_600L))
    }

    @Test fun forgedStaleAndUngrantedAreRejected() {
        val g = OrionMesh.Guard(secret)
        val e = OrionMesh.sign(secret, "pc", "task.submit", "x", 1_000L, "n2")
        assertEquals(OrionMesh.Verdict.BAD_SIGNATURE, g.check(e.copy(body = "rm -rf"), 1_000L))
        assertEquals(OrionMesh.Verdict.STALE, g.check(e, 1_000L + OrionMesh.MAX_SKEW_MS + 1))
        val shell = OrionMesh.sign(secret, "pc", "shell.run", "ls", 1_000L, "n3")
        assertEquals(OrionMesh.Verdict.FORBIDDEN, g.check(shell, 1_000L))
        assertEquals(OrionMesh.Verdict.OK, OrionMesh.Guard(secret, OrionMesh.ALL_SCOPES).check(shell, 1_000L))
    }

    @Test fun pairingCodeIsSixDigits() { assertTrue(Regex("\\d{6}").matches(OrionMesh.newPairingCode())) }
}
