package com.agent.ultra.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** A model that refuses "temperature" (Pixel cloud check, 3.0.73) gets the request again without it. */
class OpenAiClientTemperatureTest {

    /** Answers 400 "temperature is deprecated" to any body that has it, else a normal reply. */
    private class Server : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val bodies = CopyOnWriteArrayList<String>()

        init {
            Thread({
                while (!socket.isClosed) {
                    val c = try { socket.accept() } catch (_: Exception) { break }
                    c.use {
                        val input = BufferedInputStream(it.getInputStream())
                        var length = 0
                        while (true) {
                            val h = readLine(input) ?: break
                            if (h.isEmpty()) break
                            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toInt()
                        }
                        val body = String(ByteArray(length) { input.read().toByte() })
                        bodies += body
                        val (status, json) = if ("\"temperature\"" in body)
                            "400 Bad Request" to """{"error":{"message":"`temperature` is deprecated for this model.","type":"invalid_request_error"}}"""
                        else "200 OK" to """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}]}"""
                        it.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${json.length}\r\nConnection: close\r\n\r\n$json").toByteArray())
                    }
                }
            }, "temp-server").apply { isDaemon = true }.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}/v1"
        override fun close() = socket.close()

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }
    }

    @Test fun deprecatedTemperatureIsDroppedAndRetried() = Server().use { s ->
        val client = OpenAiClient(ProviderConfig(baseUrl = s.baseUrl, apiKey = "k", model = "m"))
        val first = runBlocking { client.complete(listOf(OpenAiClient.ChatMessage("user", "hey")), 50, 0.2) }
        assertEquals("hi", first.getOrNull())
        assertTrue(client.temperatureRejected)
        // Later calls go without it straight away: one request, not two.
        val before = s.bodies.size
        runBlocking { client.complete(listOf(OpenAiClient.ChatMessage("user", "again")), 50, 0.2) }
        assertEquals(before + 1, s.bodies.size)
        assertFalse("\"temperature\"" in s.bodies.last())
    }

    @Test fun onlyATemperatureRefusalCounts() {
        assertTrue(OpenAiClient.rejectedTemperature("HTTP 400: {\"error\":{\"message\":\"`temperature` is deprecated for this model.\"}}"))
        assertFalse(OpenAiClient.rejectedTemperature("HTTP 401: Invalid Anthropic API Key"))
        assertFalse(OpenAiClient.rejectedTemperature("HTTP 400: max_tokens is too large"))
    }
}
