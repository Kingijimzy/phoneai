package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionMeshLinkTest {
    private val secret = OrionMesh.deriveSecret("123456", "phone", "pc")

    private fun server(scopes: Set<String> = OrionMesh.DEFAULT_SCOPES) =
        OrionMeshLink.Server(0, { id -> if (id == "pc") "pc" to OrionMesh.Guard(secret, scopes) else null }) { peer, action, body -> "$peer ran $action:$body" }.start()

    @Test fun signedRequestRoundTripsOverARealSocket() {
        val s = server()
        try {
            assertEquals("pc ran task.submit:hello", OrionMeshLink.request("127.0.0.1", s.port, secret, "pc", "task.submit", "hello", 5000))
        } finally { s.stop() }
    }

    @Test fun wrongSecretUnknownNodeAndUngrantedActionAreRefused() {
        val s = server()
        try {
            val bad = OrionMesh.deriveSecret("000000", "phone", "pc")
            assertTrue(OrionMeshLink.request("127.0.0.1", s.port, bad, "pc", "ping", "", 5000).contains("BAD_SIGNATURE"))
            assertTrue(OrionMeshLink.request("127.0.0.1", s.port, secret, "stranger", "ping", "", 5000).contains("unknown node"))
            assertTrue(OrionMeshLink.request("127.0.0.1", s.port, secret, "pc", "shell.run", "ls", 5000).contains("FORBIDDEN"))
        } finally { s.stop() }
    }

    @Test fun envelopeSurvivesTheWireFormat() {
        val e = OrionMesh.sign(secret, "pc", "ping", "x\ny", 5L, "n")
        assertEquals(e, OrionMeshLink.decode(OrionMeshLink.encode(e)))
    }
}
