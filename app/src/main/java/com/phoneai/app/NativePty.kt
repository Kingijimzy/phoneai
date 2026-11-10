package com.phoneai.app

/**
 * Linux pseudo-terminal bridge. The native side creates a controlling PTY and execs the
 * pinned PRoot binary; the returned master descriptor carries both input and output.
 */
internal object NativePty {
    init { System.loadLibrary("orionpty") }

    external fun spawn(argv: Array<String>, environment: Array<String>, rows: Int, columns: Int, pidOut: IntArray): Int
    external fun read(fd: Int, target: ByteArray, length: Int): Int
    external fun write(fd: Int, source: ByteArray, length: Int): Int
    external fun resize(fd: Int, rows: Int, columns: Int)
    external fun close(fd: Int): Int
}
