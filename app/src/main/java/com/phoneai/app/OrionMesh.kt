package com.phoneai.app

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Trust layer for Orion-to-Orion links (phone, PC, other builds). Nodes pair once with a short code; every later
 * message is HMAC-signed with the shared secret, carries a nonce and timestamp, and is rejected when forged, stale,
 * replayed or asking for an action the pairing does not allow. Transport (LAN / Tailscale) plugs in on top.
 */
internal object OrionMesh {
    const val MAX_SKEW_MS = 60_000L
    /** Remote nodes may ask for these by default; running commands or editing code needs an explicit grant. */
    val DEFAULT_SCOPES = setOf("ping", "memory.read", "task.submit")
    val ALL_SCOPES = DEFAULT_SCOPES + setOf("shell.run", "code.edit")

    data class Envelope(val from: String, val action: String, val body: String, val nonce: String, val ts: Long, val sig: String)

    fun newPairingCode(rng: SecureRandom = SecureRandom()): String = (0 until 6).joinToString("") { rng.nextInt(10).toString() }

    /** Both devices derive the same secret from the code typed/scanned on each side plus their node ids. */
    fun deriveSecret(code: String, idA: String, idB: String): ByteArray {
        val ids = listOf(idA, idB).sorted().joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest("orion-mesh|$code|$ids".toByteArray())
    }

    private fun mac(secret: ByteArray, e: Envelope): String {
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        val data = listOf(e.from, e.action, e.body, e.nonce, e.ts.toString()).joinToString("\n")
        return m.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun sign(secret: ByteArray, from: String, action: String, body: String, now: Long, nonce: String): Envelope =
        Envelope(from, action, body, nonce, now, "").let { it.copy(sig = mac(secret, it)) }

    enum class Verdict { OK, BAD_SIGNATURE, STALE, REPLAY, FORBIDDEN }

    class Guard(private val secret: ByteArray, private val scopes: Set<String> = DEFAULT_SCOPES) {
        private val seen = LinkedHashSet<String>()
        fun check(e: Envelope, now: Long): Verdict {
            val expected = mac(secret, e.copy(sig = ""))
            if (!MessageDigest.isEqual(expected.toByteArray(), e.sig.toByteArray())) return Verdict.BAD_SIGNATURE
            if (kotlin.math.abs(now - e.ts) > MAX_SKEW_MS) return Verdict.STALE
            if (e.action !in scopes) return Verdict.FORBIDDEN
            if (!seen.add(e.nonce)) return Verdict.REPLAY
            if (seen.size > 2000) seen.remove(seen.first())
            return Verdict.OK
        }
    }
}
