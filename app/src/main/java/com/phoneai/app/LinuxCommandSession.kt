package com.phoneai.app

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Long-lived stdio bridge for tools running inside Orion's already-installed PRoot guest.
 *
 * The Android process owns the child process and pipes; no TCP listener is opened. stdout
 * and stderr remain separate so JSON-RPC framing is not corrupted by diagnostics. The
 * caller supplies an argv vector (never a shell command string); arguments are quoted
 * before being sent to the guest's interactive shell.
 */
internal class LinuxCommandSession private constructor(
    private val process: Process,
    private val stdin: BufferedWriter,
    private val stdout: LinkedBlockingQueue<String>,
    private val stderr: LinkedBlockingQueue<String>,
    private val closed: AtomicBoolean
) : Closeable {

    fun writeLine(value: String) {
        check(!closed.get() && process.isAlive) { "Linux command session is not running" }
        require(value.isNotEmpty() && value.length <= 1_000_000 && !value.contains('\u0000'))
        synchronized(stdin) {
            stdin.write(value)
            stdin.newLine()
            stdin.flush()
        }
    }

    /** Returns the next stdout line, or null when the timeout expires/process exits. */
    fun readLine(timeoutMs: Long = 30_000L): String? {
        require(timeoutMs >= 0)
        stdout.poll(timeoutMs, TimeUnit.MILLISECONDS)?.let { return it }
        if (!process.isAlive) return stdout.poll()
        return null
    }

    fun readStderrLine(timeoutMs: Long = 0L): String? = stderr.poll(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)

    fun isAlive(): Boolean = !closed.get() && process.isAlive

    fun hasPendingOutput(): Boolean = stdout.isNotEmpty() || stderr.isNotEmpty()
    fun awaitExit(timeoutMs: Long): Boolean = process.waitFor(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)

    fun exitCodeOrNull(): Int? = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()

    fun stderrSnapshot(maxLines: Int = 40): List<String> {
        val result = ArrayList<String>()
        stderr.drainTo(result, maxLines.coerceIn(1, 200))
        return result
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stdin.close() }
        process.destroy()
        if (process.isAlive) {
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
    }

    companion object {
        fun start(config: LinuxTerminalLaunchConfig, argv: List<String>, extraEnvironment: Map<String, String> = emptyMap()): LinuxCommandSession {
            require(argv.isNotEmpty() && argv.size <= 64) { "Invalid guest command arguments" }
            require(argv.all { it.isNotEmpty() && it.length <= 16_384 && !it.contains('\u0000') }) {
                "Invalid guest command argument"
            }
            val builder = ProcessBuilder(config.executable, *config.arguments)
                .directory(java.io.File(config.workingDirectory))
            builder.environment().clear()
            config.environment.forEach { entry ->
                val split = entry.indexOf('=')
                if (split > 0) builder.environment()[entry.substring(0, split)] = entry.substring(split + 1)
            }
            extraEnvironment.forEach { (key, value) ->
                require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !value.contains('\u0000'))
                builder.environment()[key] = value
            }
            val process = builder.start()
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
            val stdout = LinkedBlockingQueue<String>()
            val stderr = LinkedBlockingQueue<String>()
            val closed = AtomicBoolean(false)
            pump(process.inputStream, stdout, closed, "orion-linux-stdout")
            pump(process.errorStream, stderr, closed, "orion-linux-stderr")
            val session = LinuxCommandSession(process, stdin, stdout, stderr, closed)
            // The launch config starts an interactive guest shell. Replace it with the
            // requested executable so its stdin/stdout become the protocol transport.
            session.writeLine("exec " + argv.joinToString(" ") { shellQuote(it) })
            return session
        }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        private fun pump(
            stream: java.io.InputStream,
            target: LinkedBlockingQueue<String>,
            closed: AtomicBoolean,
            threadName: String
        ) {
            Thread({
                try {
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        while (!closed.get()) {
                            val line = reader.readLine() ?: break
                            // Bound memory if a child floods output while the consumer is idle.
                            if (target.size >= 2_000) target.poll()
                            target.offer(line.take(256_000))
                        }
                    }
                } catch (_: Exception) { }
            }, threadName).apply { isDaemon = true; start() }
        }
    }
}
