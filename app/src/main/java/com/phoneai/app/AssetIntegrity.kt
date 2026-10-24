package com.phoneai.app

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal object AssetIntegrity {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun matchesSha256(file: File, expected: String): Boolean =
        expected.matches(Regex("(?i)^[0-9a-f]{64}$")) &&
            sha256(file).equals(expected, ignoreCase = true)
}
