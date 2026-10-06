package com.agent.ultra.listen

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** Real HTTP against a local server: resume with Range, a server that ignores Range, exact size. */
class ModelFetcherTest {
    private val body = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private var honourRange = true
    private val ranges = ArrayList<String?>()
    private val url get() = "http://127.0.0.1:${server.address.port}/m.onnx"

    @Before fun start() {
        dir = Files.createTempDirectory("fetch").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/m.onnx") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            ranges += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            if (honourRange && from != null) {
                if (from >= body.size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                ex.sendResponseHeaders(206, (body.size - from).toLong())
                ex.responseBody.use { it.write(body, from, body.size - from) }
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        server.start()
    }

    @After fun stop() { server.stop(0); dir.deleteRecursively() }

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
        assertFalse(ModelFetcher.ensure(dir, "big.onnx", "http://127.0.0.1:${server.address.port}/missing", 1_000L))
    }
}
