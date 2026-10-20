package com.agent.jarvis.net

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** A fake SOCKS5 proxy on 127.0.0.1 for tests: records each CONNECT and answers one HTTP page. */
internal class Connect(val atyp: Int, val host: String, val port: Int)

internal class FakeSocks(private val page: String) : AutoCloseable {
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val connects = CopyOnWriteArrayList<Connect>()
    val accepted = CopyOnWriteArrayList<Socket>()
    init {
        Thread({
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: Exception) { break }
                accepted += c
                Thread({ runCatching { serve(c) }; runCatching { c.close() } }, "fake-socks-conn").apply { isDaemon = true }.start()
            }
        }, "fake-socks").apply { isDaemon = true }.start()
    }
    val port: Int get() = server.localPort

    private fun serve(c: Socket) {
        val inp = DataInputStream(c.getInputStream())
        val out = c.getOutputStream()
        if (inp.readUnsignedByte() != 5) return
        val n = inp.readUnsignedByte(); repeat(n) { inp.readUnsignedByte() }
        out.write(byteArrayOf(5, 0)); out.flush()
        val ver = inp.readUnsignedByte(); val cmd = inp.readUnsignedByte(); inp.readUnsignedByte()
        if (ver != 5 || cmd != 1) return
        val atyp = inp.readUnsignedByte()
        val host = when (atyp) {
            3 -> ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }.toString(Charsets.US_ASCII)
            1 -> ByteArray(4).also { inp.readFully(it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
            else -> ByteArray(16).also { inp.readFully(it) }.toString(Charsets.ISO_8859_1)
        }
        val port = inp.readUnsignedShort()
        connects += Connect(atyp, host, port)
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
        // the HTTP request, up to its blank line
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return; sb.append(b.toChar()) }
        val body = page.toByteArray()
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    override fun close() { server.close(); accepted.forEach { runCatching { it.close() } } }
}

