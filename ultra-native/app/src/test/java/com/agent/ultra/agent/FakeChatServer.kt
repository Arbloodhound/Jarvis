package com.agent.ultra.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted OpenAI-compatible endpoint on 127.0.0.1 (an ephemeral port) for driving the real
 * [Brain] in a JVM test. `POST /v1/chat/completions` answers each request with the next scripted
 * reply, streamed as SSE the way [com.agent.ultra.provider.OpenAiClient.completeStreaming] reads
 * it: `data: {"choices":[{"delta":{"content":…},"finish_reason":null}]}` frames, a last frame
 * carrying finish_reason, then `data: [DONE]`. Every request body is recorded. Test-only.
 *
 * A minimal HTTP/1.1 server on [ServerSocket], one request per connection (`Connection: close`):
 * the JDK's com.sun.net.httpserver is not on the Android unit-test compile classpath (android.jar
 * is the boot classpath), and MockWebServer would be a new dependency for one test.
 */
class FakeChatServer : AutoCloseable {
    sealed class Reply {
        /** Streamed [text] in a few chunks, ending with [finishReason]. */
        data class Text(val text: String, val finishReason: String = "stop") : Reply()
        /** A plain HTTP error with [body]. */
        data class Error(val code: Int, val body: String = "internal error") : Reply()
    }

    private val queue = ConcurrentLinkedQueue<Reply>()
    /** Every request body received, in order. */
    val requests: MutableList<String> = CopyOnWriteArrayList()

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    init {
        Thread({
            while (!socket.isClosed) {
                val c = try { socket.accept() } catch (_: Exception) { break }
                Thread({ c.use { serve(it) } }, "fake-chat-conn").apply { isDaemon = true }.start()
            }
        }, "fake-chat-server").apply { isDaemon = true }.start()
    }

    val port: Int get() = socket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"

    fun enqueue(vararg replies: Reply) { queue.addAll(replies) }
    fun reply(text: String, finishReason: String = "stop") = enqueue(Reply.Text(text, finishReason))
    fun fail(code: Int = 500, body: String = "internal error") = enqueue(Reply.Error(code, body))

    /** The text of every message in request [i] (0-based), joined. */
    fun messagesOf(i: Int): String {
        val msgs = JSONObject(requests[i]).getJSONArray("messages")
        return (0 until msgs.length()).joinToString("\n") { msgs.getJSONObject(it).optString("content") }
    }

    /** The last message of request [i]. */
    fun lastMessageOf(i: Int): JSONObject {
        val msgs = JSONObject(requests[i]).getJSONArray("messages")
        return msgs.getJSONObject(msgs.length() - 1)
    }

    override fun close() = socket.close()

    private fun serve(c: Socket) {
        val input = BufferedInputStream(c.getInputStream())
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toInt()
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val out = c.getOutputStream()
        fun respond(code: Int, contentType: String, payload: ByteArray) {
            out.write(("HTTP/1.1 $code Scripted\r\nContent-Type: $contentType\r\n" +
                "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(payload)
            out.flush()
        }
        if (!requestLine.startsWith("POST /v1/chat/completions ")) {
            respond(404, "text/plain", "not found: $requestLine".toByteArray())
            return
        }
        requests += String(body, Charsets.UTF_8)
        when (val r = queue.poll() ?: Reply.Error(599, "fake server: no scripted reply left")) {
            is Reply.Error -> respond(r.code, "text/plain", r.body.toByteArray())
            is Reply.Text -> {
                val sse = StringBuilder()
                for (piece in r.text.chunked(7)) sse.append(frame(JSONObject().put("content", piece), null))
                sse.append(frame(JSONObject(), r.finishReason))
                sse.append("data: [DONE]\n\n")
                respond(200, "text/event-stream", sse.toString().toByteArray())
            }
        }
    }

    /** One CRLF-terminated header line (without the line break), or null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val ch = input.read()
            if (ch < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (ch == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(ch)
        }
    }

    private fun frame(delta: JSONObject, finish: String?): String {
        val choice = JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)
        return "data: " + JSONObject().put("choices", JSONArray().put(choice)) + "\n\n"
    }
}
