package com.phoneai.app

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** A single interactive Linux process attached to a real Android PTY master descriptor. */
internal class LinuxTerminalSession private constructor(
    private val masterFd: Int,
    val processId: Int,
    private val output: (String) -> Unit,
    private val onClosed: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val reader = Thread({
        val buffer = ByteArray(8192)
        try {
            while (!closed.get()) {
                val count = NativePty.read(masterFd, buffer, buffer.size)
                if (count > 0) output(String(buffer, 0, count, Charsets.UTF_8))
                else break
            }
        } catch (_: Throwable) {
            // Closing the PTY from another thread is an expected end-of-session condition.
        } finally {
            if (closed.compareAndSet(false, true)) {
                NativePty.close(masterFd)
                onClosed()
            }
        }
    }, "OrionLinuxPtyReader").apply { isDaemon = true }

    init { reader.start() }

    fun send(text: String): Boolean {
        if (closed.get()) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size && !closed.get()) {
            val count = NativePty.write(masterFd, bytes.copyOfRange(offset, bytes.size), bytes.size - offset)
            if (count <= 0) return false
            offset += count
        }
        return offset == bytes.size
    }

    fun resize(rows: Int, columns: Int) {
        if (!closed.get()) NativePty.resize(masterFd, rows.coerceAtLeast(2), columns.coerceAtLeast(20))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            NativePty.close(masterFd)
            onClosed()
        }
    }

    companion object {
        internal fun launch(argv: Array<String>, environment: Array<String>, rows: Int = 24, columns: Int = 80,
                            output: (String) -> Unit, onClosed: () -> Unit): LinuxTerminalSession {
            val pid = IntArray(1)
            val fd = NativePty.spawn(argv, environment, rows, columns, pid)
            check(fd >= 0 && pid[0] > 0) { "Could not create the Linux pseudo-terminal (errno from native PTY backend)." }
            return LinuxTerminalSession(fd, pid[0], output, onClosed)
        }
    }
}
