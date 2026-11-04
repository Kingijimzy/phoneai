package com.phoneai.app

/** Byte-for-byte verification for files written through Android document providers. */
internal object SavedFileContentVerifier {
    fun matches(expected: ByteArray, actual: ByteArray): Boolean = expected.contentEquals(actual)
}
