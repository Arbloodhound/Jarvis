package com.agent.jarvis.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The cloud call must end: by its total deadline (callTimeout) when the server never answers or
 * trickles a stream forever, and promptly when the caller's coroutine is cancelled (Stop).
 * Served by a raw [ServerSocket] on 127.0.0.1 (com.sun.net.httpserver is not on the Android
 * unit-test classpath), as agent/FakeChatServer is.
 */
class OpenAiClientDeadlineTest {

    /** A server that reads the request and then either never answers or trickles SSE bytes. */
    private class StallServer(private val trickle: Boolean) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val conns = CopyOnWriteArrayList<Socket>()
        /** Completed when a request has been read: the call is in flight. */
        val requested = CompletableDeferred<Unit>()
        /** Completed when the client closed the connection (the call really stopped). */
        val clientGone = CompletableDeferred<Unit>()

        init {
            Thread({
                while (!socket.isClosed) {
                    val c = try { socket.accept() } catch (_: Exception) { break }
                    conns += c
                    Thread({ serve(c) }, "stall-conn").apply { isDaemon = true }.start()
                }
            }, "stall-server").apply { isDaemon = true }.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}/v1"

        private fun serve(c: Socket) {
            try {
                val input = BufferedInputStream(c.getInputStream())
                var length = 0
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toInt()
                }
                repeat(length) { if (input.read() < 0) return }
                requested.complete(Unit)
                val out = c.getOutputStream()
                if (trickle) {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray())
                    out.flush()
                    while (true) {          // one byte of an SSE comment every 200 ms, forever
                        out.write(':'.code); out.flush()
                        Thread.sleep(200)
                    }
                } else {
                    // Never answer; notice when the client hangs up.
                    if (input.read() < 0) clientGone.complete(Unit)
                }
            } catch (_: Exception) {
                clientGone.complete(Unit)
            }
        }

        private fun readLine(input: InputStream): String? {
            val b = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch < 0) return if (b.isEmpty()) null else b.toString()
                if (ch == '\n'.code) return b.toString().trimEnd('\r')
                b.append(ch.toChar())
            }
        }

        override fun close() {
            socket.close()
            conns.forEach { runCatching { it.close() } }
        }
    }

    private val servers = mutableListOf<StallServer>()
    @After fun tearDown() = servers.forEach { it.close() }

    private fun server(trickle: Boolean) = StallServer(trickle).also { servers += it }
    private fun client(s: StallServer, callTimeoutMs: Long) =
        OpenAiClient(ProviderConfig(s.baseUrl, "test-key", "test-model"), callTimeoutMs)
    private val msgs = listOf(OpenAiClient.ChatMessage("user", "hi"))

    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000

    /** (a) Accepts, never answers: the plain call fails by the 1 s deadline. */
    @Test fun silentServerFailsByTheCallTimeout() = runBlocking {
        val s = server(trickle = false)
        val start = System.nanoTime()
        val r = client(s, 1_000).complete(msgs, 16, 0.0)
        val ms = elapsedMs(start)
        assertTrue("must fail, got $r", r.isFailure)
        assertTrue("ended after $ms ms", ms < 5_000)
    }

    /** (a) The same for the streaming call. */
    @Test fun silentServerFailsByTheCallTimeoutStreaming() = runBlocking {
        val s = server(trickle = false)
        val start = System.nanoTime()
        val r = client(s, 1_000).completeStreaming(msgs, 16, 0.0) {}
        val ms = elapsedMs(start)
        assertTrue("must fail, got $r", r.isFailure)
        assertTrue("ended after $ms ms", ms < 5_000)
    }

    /** (b) A byte every 200 ms keeps the read timeout from ever firing; the call deadline still ends it. */
    @Test fun tricklingStreamStillEndsByTheCallTimeout() = runBlocking {
        val s = server(trickle = true)
        val start = System.nanoTime()
        val r = client(s, 1_000).completeStreaming(msgs, 16, 0.0) {}
        val ms = elapsedMs(start)
        assertTrue("must fail, got $r", r.isFailure)
        assertTrue("ended after $ms ms", ms < 5_000)
    }

    /** (c) Stop: cancelling the caller ends the call well under its (60 s) deadline, and hangs up. */
    @Test fun cancellingTheCallerStopsTheCallPromptly() = runBlocking {
        for (trickle in listOf(false, true)) {
            val s = server(trickle)
            val c = client(s, 60_000)
            val job = async(Dispatchers.Default) { c.completeStreaming(msgs, 16, 0.0) {} }
            withTimeout(5_000) { s.requested.await() }
            val start = System.nanoTime()
            job.cancel()
            withTimeout(3_000) { job.join() }
            val ms = elapsedMs(start)
            assertTrue("trickle=$trickle: cancel took $ms ms", ms < 3_000)
            assertTrue(job.isCancelled)
            // The connection itself is let go, not just the coroutine abandoned.
            withTimeout(3_000) { s.clientGone.await() }
        }
    }
}
