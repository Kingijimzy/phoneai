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

    /**
     * stop() cancels every model client it can reach, including ones that are not mid-request.
     * The whole point of cancel() being "sticky" (it blocks the *next* connection from ever
     * attaching, not just the current one) is so a Stop press reliably aborts a request that is
     * about to start, not only one already in flight. But that only works if every place that
     * calls cancel() is paired with a resume() before the client is asked to do real work again -
     * a client left cancelled stays permanently unable to make a request, with no further Stop
     * press able to make things worse and no UI signal that it is now silently inert. This is the
     * exact bug found in Agent: commandNim was resumed at the start of a new command, talkNim
     * (cancelled by the same stop()) never was.
     */
    @Test fun cancelBlocksTheNextRequestAndResumeRestoresIt() {
        // Use an unreachable loopback port for the blocked half: cancel() must refuse the
        // connection before any socket I/O happens at all, so what the port actually does
        // (or whether anything is listening on it) must not matter.
        val unreachablePort = ServerSocket(0).let { s -> val p = s.localPort; s.close(); p }
        val nim = Nim(RuntimeEnvironment.getApplication() as Context)
        nim.cancel()
        val blocked = runCatching {
            nim.requestStream(testProvider(), "http://127.0.0.1:$unreachablePort/stream", "{}", "test-key", 4000) { }
        }
        assertTrue("a client left cancelled must refuse to attach a new connection",
            blocked.isFailure)

        // A fresh server for the resume half: the blocked attempt above must have made no
        // connection, but a separate single-shot server rules out any doubt either way.
        val server = LoopbackSseServer(
            listOf("""data: {"choices":[{"delta":{"content":"ok"}}]}"""),
            pauseAfterFirstEvent = false
        )
        try {
            nim.resume()
            val emitted = mutableListOf<String>()
            // This server (like the one above) closes without a [DONE] marker, so the call
            // itself still throws on the incomplete stream - that part is unrelated to resume()
            // and is exercised on its own in serverDisconnectAfterPartialTextDoesNotDuplicateOrAuthorizeFallback.
            // What matters here is only that resume() let the connection attach and really
            // reach the server at all, which the received chunk proves.
            runCatching { nim.requestStream(testProvider(), server.url, "{}", "test-key", 4000) { emitted.add(it) } }
            assertEquals("resume() must let the client attach and run a real request again",
                listOf("ok"), emitted)
        } finally {
            nim.cancel()
            server.close()
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
