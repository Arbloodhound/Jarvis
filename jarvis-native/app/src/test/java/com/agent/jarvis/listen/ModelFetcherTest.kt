package com.agent.jarvis.listen

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** Real HTTP against a local server: resume with Range, a server that ignores Range, exact size.
 * The server is a minimal ServerSocket loop (com.sun.net.httpserver isn't on the unit-test
 * classpath, which is android.jar's). One request per connection. */
class ModelFetcherTest {
    private val body = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    @Volatile private var honourRange = true
    @Volatile private var sendContentRange = true
    private val ranges = CopyOnWriteArrayList<String?>()
    private val port get() = server.localPort
    private val url get() = "http://127.0.0.1:$port/m.onnx"

    @Before fun start() {
        dir = Files.createTempDirectory("fetch").toFile()
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                s.use { serve(it) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(s: java.net.Socket) {
        val inp = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = inp.readLine()?.split(' ')?.getOrNull(1) ?: return
        var range: String? = null
        while (true) {
            val line = inp.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val out = s.getOutputStream()
        fun head(status: String, len: Int, extra: String = "") =
            out.write("HTTP/1.1 $status\r\nContent-Length: $len\r\nConnection: close\r\n$extra\r\n".toByteArray())
        if (path != "/m.onnx") { head("404 Not Found", 0); out.flush(); return }
        ranges += range
        val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
        if (honourRange && from != null) {
            if (from >= body.size) { head("416 Range Not Satisfiable", 0); out.flush(); return }
            val cr = if (sendContentRange) "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n" else ""
            head("206 Partial Content", body.size - from, cr)
            out.write(body, from, body.size - from)
        } else {
            head("200 OK", body.size)
            out.write(body)
        }
        out.flush()
    }

    @After fun stop() { server.close(); dir.deleteRecursively() }

    @Test fun freshDownloadChecksExactSize() {
        assertTrue(ModelFetcher.ensure(dir, "m.onnx", url, exactBytes = body.size.toLong()))
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
        assertFalse(File(dir, "m.onnx.part").exists())
        // Present and right size: no second request.
        assertTrue(ModelFetcher.ensure(dir, "m.onnx", url, exactBytes = body.size.toLong()))
        assertEquals(1, ranges.size)
    }

    @Test fun resumesAPartialDownload() {
        File(dir, "m.onnx.part").writeBytes(body.copyOf(70_000))
        assertTrue(ModelFetcher.ensure(dir, "m.onnx", url, exactBytes = body.size.toLong()))
        assertEquals("bytes=70000-", ranges.single())
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test fun serverIgnoringRangeStartsOver() {
        honourRange = false
        File(dir, "m.onnx.part").writeBytes(ByteArray(70_000) { 9 })
        assertTrue(ModelFetcher.ensure(dir, "m.onnx", url, exactBytes = body.size.toLong()))
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test fun completePartIsFinishedWithoutRedownloading() {
        File(dir, "m.onnx.part").writeBytes(body)
        assertTrue(ModelFetcher.ensure(dir, "m.onnx", url, exactBytes = body.size.toLong()))
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test fun wrongSizeIsRejected() {
        assertFalse(ModelFetcher.ensure(dir, "m.onnx", url, exactBytes = body.size + 5L))
        assertFalse(File(dir, "m.onnx").exists())
        // Kept as a .part (it is shorter than expected), so a later call can resume it.
        assertEquals(body.size.toLong(), File(dir, "m.onnx.part").length())
    }

    @Test fun minBytesModeStillWorks() {
        assertTrue(ModelFetcher.ensure(dir, "m.onnx", url, 1_000L))
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
        assertFalse(ModelFetcher.ensure(dir, "big.onnx", "http://127.0.0.1:$port/missing", 1_000L))
    }

    @Test fun a206WithoutTheRightContentRangeIsNotAppended() {
        sendContentRange = false
        File(dir, "m.onnx.part").writeBytes(body.copyOf(70_000))
        assertFalse(ModelFetcher.ensure(dir, "m.onnx", url, exactBytes = body.size.toLong()))
        assertFalse(File(dir, "m.onnx").exists())
        assertFalse("bad .part dropped, next call starts over", File(dir, "m.onnx.part").exists())
        sendContentRange = true
        assertTrue(ModelFetcher.ensure(dir, "m.onnx", url, exactBytes = body.size.toLong()))
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }

    @Test fun contentRangeParsing() {
        assertTrue(ModelFetcher.resumesAt("bytes 70000-199999/200000", 70_000))
        assertTrue(ModelFetcher.resumesAt("bytes 5-9/*", 5))
        assertFalse(ModelFetcher.resumesAt("bytes 0-199999/200000", 70_000))
        assertFalse(ModelFetcher.resumesAt(null, 70_000))
        assertFalse(ModelFetcher.resumesAt("garbage", 0))
        assertTrue(ModelFetcher.resumesAt("bytes 70000-199999/200000", 70_000, 200_000))
        assertFalse("different file upstream", ModelFetcher.resumesAt("bytes 70000-199999/200000", 70_000, 300_000))
        assertTrue(ModelFetcher.resumesAt("bytes 5-9/*", 5, 300_000))
    }

    @Test fun minBytesCallersNeverResumeAStalePart() {
        // Old behaviour kept for whisper/VAD/wake: no exact size, so a leftover .part is not trusted.
        File(dir, "m.onnx.part").writeBytes(ByteArray(70_000) { 9 })
        assertTrue(ModelFetcher.ensure(dir, "m.onnx", url, 1_000L))
        assertEquals(null, ranges.single())
        assertArrayEquals(body, File(dir, "m.onnx").readBytes())
    }
}
