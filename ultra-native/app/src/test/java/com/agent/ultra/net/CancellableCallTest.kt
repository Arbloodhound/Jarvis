package com.agent.ultra.net

import com.agent.ultra.agent.WebResearchRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Stop must stop a web fetch: a blocking OkHttp call ignores coroutine cancellation, so
 * [cancelOnStop] cancels the call itself, and the cancel comes out as a CancellationException
 * (Stop), never as a failed fetch or an "Error:" result. Served by a raw [ServerSocket] on
 * 127.0.0.1 that reads the request and never answers.
 */
class CancellableCallTest {

    private class SilentServer : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val conns = CopyOnWriteArrayList<Socket>()
        /** Completed when a request has been read: the call is in flight. */
        val requested = CompletableDeferred<Unit>()
        /** Completed when the client hung up (the call really stopped). */
        val clientGone = CompletableDeferred<Unit>()

        init {
            Thread({
                while (!socket.isClosed) {
                    val c = try { socket.accept() } catch (_: Exception) { break }
                    conns += c
                    Thread({ serve(c) }, "silent-conn").apply { isDaemon = true }.start()
                }
            }, "silent-server").apply { isDaemon = true }.start()
        }

        val url: String get() = "http://127.0.0.1:${socket.localPort}/page"

        private fun serve(c: Socket) {
            try {
                val input = c.getInputStream()
                while (true) { val h = readLine(input) ?: return; if (h.isEmpty()) break }
                requested.complete(Unit)
                if (input.read() < 0) clientGone.complete(Unit)   // never answers
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

    private val servers = mutableListOf<SilentServer>()
    @After fun tearDown() = servers.forEach { it.close() }
    private fun server() = SilentServer().also { servers += it }

    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000

    /** Cancelling the caller ends the blocked call promptly, as a cancellation, and hangs up. */
    @Test fun cancellingTheCallerStopsTheCallPromptly() = runBlocking {
        val s = server()
        var thrown: Throwable? = null
        val job = launch(Dispatchers.IO) {
            val call = http.newCall(Request.Builder().url(s.url).build())
            try { cancelOnStop(call) { call.execute().use { it.code } } }
            catch (t: Throwable) { thrown = t; throw t }
        }
        withTimeout(5_000) { s.requested.await() }
        val start = System.nanoTime()
        job.cancel()
        withTimeout(3_000) { job.join() }
        val ms = elapsedMs(start)
        assertTrue("cancel took $ms ms", ms < 3_000)
        assertTrue(job.isCancelled)
        assertTrue("Stop must surface as a cancellation, got $thrown", thrown is CancellationException)
        withTimeout(3_000) { s.clientGone.await() }
    }

    /** A call that fails by itself (nothing listening) still throws its IOException. */
    @Test fun aRealNetworkFailureIsNotTurnedIntoACancellation() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val call = http.newCall(Request.Builder().url("http://127.0.0.1:$port/").build())
        try {
            cancelOnStop(call) { call.execute().use { it.code } }
            fail("connection to a closed port must fail")
        } catch (e: CancellationException) {
            fail("not a Stop: $e")
        } catch (e: IOException) { /* expected */ }
    }

    /** A completed call is returned as is. */
    @Test fun aNormalCallReturnsItsResult() = runBlocking {
        val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread({
            socket.accept().use { c ->
                val input = c.getInputStream()
                val b = StringBuilder()
                while (!b.endsWith("\r\n\r\n")) { val ch = input.read(); if (ch < 0) break; b.append(ch.toChar()) }
                c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                c.getOutputStream().flush()
            }
        }, "ok-server").apply { isDaemon = true }.start()
        try {
            val call = http.newCall(Request.Builder().url("http://127.0.0.1:${socket.localPort}/").build())
            val body = cancelOnStop(call) { call.execute().use { it.body?.string() } }
            assertEquals("ok", body)
        } finally { socket.close() }
    }

    /**
     * The web search's page fetch (WebResearchRun, under web_search): Stop ends it promptly (its
     * read timeout is 10 s) and rethrows the cancellation instead of returning "no page".
     */
    @Test fun cancellingAWebSearchFetchEndsPromptly() = runBlocking {
        val s = server()
        var returned = false
        var result: String? = "unset"
        val job = launch(Dispatchers.IO) { result = WebResearchRun.get(s.url); returned = true }
        withTimeout(5_000) { s.requested.await() }
        val start = System.nanoTime()
        job.cancel()
        withTimeout(3_000) { job.join() }
        val ms = elapsedMs(start)
        assertTrue("cancel took $ms ms", ms < 3_000)
        assertTrue(job.isCancelled)
        assertTrue("Stop must not come back as a result (got $result)", !returned)
        withTimeout(3_000) { s.clientGone.await() }
    }

    /** Without a Stop, a page that fails is still just "no page" (null), as before. */
    @Test fun aFailedWebSearchFetchIsNull() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertNull(WebResearchRun.get("http://127.0.0.1:$port/"))
    }
}
