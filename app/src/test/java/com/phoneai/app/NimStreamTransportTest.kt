package com.phoneai.app

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses loopback HTTP sockets so cancellation/interruption exercise the real HttpURLConnection reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NimStreamTransportTest {
    @Test fun stopDuringSlowStreamDisconnectsReaderAndKeepsPartialTextSingle() {
        val firstDelta = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val emitted = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = LoopbackSseServer(
            listOf("""data: {"choices":[{"delta":{"content":"partial"}}]}"""),
            pauseAfterFirstEvent = true,
            release = releaseServer
        )
        val executor = Executors.newSingleThreadExecutor()
        val nim = Nim(RuntimeEnvironment.getApplication() as Context)
        try {
            val future = executor.submit {
                runCatching {
                    nim.requestStream(testProvider(), server.url, "{}", "test-key", 12000) {
                        emitted.add(it)
                        firstDelta.countDown()
                    }
                }
            }
            assertTrue("server should deliver a first chunk", firstDelta.await(3, TimeUnit.SECONDS))
            nim.cancel()
            releaseServer.countDown()
            assertTrue("cancel should unblock the socket reader", runCatching {
                future.get(3, TimeUnit.SECONDS)
            }.isSuccess)
            assertEquals(listOf("partial"), emitted.toList())
            assertFalse("a partial response must never trigger provider fallback",
                StreamFallbackPolicy.mayTryAnotherProvider(emitted.isNotEmpty(), false))
        } finally {
            nim.cancel()
            releaseServer.countDown()
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun serverDisconnectAfterPartialTextDoesNotDuplicateOrAuthorizeFallback() {
        val emitted = mutableListOf<String>()
        val server = LoopbackSseServer(
            listOf("""data: {"choices":[{"delta":{"content":"before disconnect"}}]}"""),
            pauseAfterFirstEvent = false
        )
        val nim = Nim(RuntimeEnvironment.getApplication() as Context)
        try {
            runCatching {
                nim.requestStream(testProvider(), server.url, "{}", "test-key", 4000) { emitted.add(it) }
            }
            assertEquals(listOf("before disconnect"), emitted)
            assertFalse(StreamFallbackPolicy.mayTryAnotherProvider(emitted.isNotEmpty(), false))
        } finally {
            nim.cancel()
            server.close()
        }
    }

    private fun testProvider(): Provider =
        Providers.all.first { !it.anthropicStyle && it.id != "opencode-zen" }

    private class LoopbackSseServer(
        private val events: List<String>,
        private val pauseAfterFirstEvent: Boolean,
        private val release: CountDownLatch = CountDownLatch(0)
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val url: String = "http://127.0.0.1:${listener.localPort}/stream"
        private val worker = Thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                    }
                    repeat(contentLength) { if (reader.read() < 0) return@Thread }
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    events.forEach { event ->
                        out.write((event + "\n\n").toByteArray(Charsets.UTF_8))
                        out.flush()
                        if (pauseAfterFirstEvent) release.await(5, TimeUnit.SECONDS)
                    }
                    // Deliberately close without [DONE] to simulate an interrupted stream.
                }
            } catch (_: Exception) {
                // A client-side disconnect is the expected outcome in the cancellation case.
            } finally {
                try { listener.close() } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            try { listener.close() } catch (_: Exception) { }
            worker.join(1000)
        }
    }
}
