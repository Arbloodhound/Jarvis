package com.agent.ultra.net

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Tor client hands the .onion name to the SOCKS proxy (CONNECT with ATYP 0x03 and the name's
 * bytes) and never looks it up locally. Served by a fake SOCKS5 proxy on 127.0.0.1 that records
 * the CONNECT and answers one HTTP page.
 */
class TorProxyTest {

    private val onion = "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion"

    /** Records every lookup it is asked for (there must be none for the onion name). */
    private class SpyDns : Dns {
        val lookups = CopyOnWriteArrayList<String>()
        override fun lookup(hostname: String): List<InetAddress> { lookups += hostname; return OnionBlockingDns().lookup(hostname) }
    }

    @Test fun onionNameGoesToTheProxyAsADomainAndIsNeverResolvedLocally() = runBlocking {
        FakeSocks("<html><title>Wiki</title><body>hello from the onion</body></html>").use { s ->
            val dns = SpyDns()
            val tor = TorProxy(port = s.port, dns = dns)
            val html = tor.get("http://$onion/wiki/index.html")
            assertTrue(html!!.contains("hello from the onion"))
            val c = s.connects.single()
            assertEquals(0x03, c.atyp)
            assertEquals(onion, c.host)
            assertEquals(80, c.port)
            assertTrue("no local DNS lookup: ${dns.lookups}", dns.lookups.isEmpty())
        }
    }

    @Test fun clearnetUrlsAreRefusedAndNeverSent() = runBlocking {
        FakeSocks("x").use { s ->
            val tor = TorProxy(port = s.port)
            assertNull(tor.get("https://example.com/"))
            assertNull(tor.get("not a url"))
            Thread.sleep(100)
            assertTrue(s.connects.isEmpty())
        }
    }

    @Test fun availableOnlyWhileSomethingListens() {
        val s = FakeSocks("x")
        val port = s.port
        assertTrue(TorProxy(port = port).isAvailable())
        s.close()
        assertFalse(TorProxy(port = port).isAvailable())
    }

    @Test fun downProxyGivesNullNotAClearnetFetch() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(TorProxy(port = port).get("http://$onion/"))
    }

    @Test fun onionBlockingDnsRefusesOnionNames() {
        try { OnionBlockingDns().lookup(onion); fail("looked up an onion name") } catch (_: UnknownHostException) {}
        try { OnionBlockingDns().lookup("WWW.$onion."); fail("looked up an onion name") } catch (_: UnknownHostException) {}
        assertEquals(9050, TorProxy.PORT)
    }
}
