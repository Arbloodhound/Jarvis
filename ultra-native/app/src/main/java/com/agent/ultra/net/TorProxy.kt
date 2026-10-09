package com.agent.ultra.net

import kotlinx.coroutines.CancellationException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Reading .onion pages through Tor's SOCKS proxy (Orbot), for research only — Settings →
 * "Onion research via Tor", off by default.
 *
 * ## Scope (owner, 2026-10-09)
 * Reading hidden wikis and forums with scam-vs-legit screening, and checking products and
 * companies across clearnet and onion. Explicitly OUT of scope ("I don't need special skills for
 * the dark net", "I don't wanna find illegal items"): marketplace navigation, vendor or seller
 * reputation or vetting, and any purchasing. This class only fetches .onion URLs it is handed; it
 * never discovers new ones and never carries clearnet traffic.
 *
 * ## No local lookup of the onion name
 * The proxy is a SOCKS proxy, so OkHttp hands the destination to the socket as an UNRESOLVED
 * address (OkHttp 4.12 RouteSelector: for Proxy.Type.SOCKS it builds
 * InetSocketAddress.createUnresolved(host, port) and never calls [Dns]), and the SOCKS5 CONNECT
 * carries the hostname itself (ATYP 0x03): Tor resolves it, this phone never does. As defence in
 * depth the client's [Dns] throws for any .onion name ([OnionBlockingDns]), and a network
 * interceptor refuses any request that isn't .onion (a redirect to a clearnet site is not
 * followed through Tor). [isAvailable] is a fast TCP probe of the proxy port; [get] runs under
 * [cancelOnStop], so Stop ends a slow Tor fetch.
 */
class TorProxy(
    private val host: String = HOST,
    private val port: Int = PORT,
    dns: Dns = OnionBlockingDns(Dns.SYSTEM),
) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port)))
        .dns(dns)
        .addNetworkInterceptor(Interceptor { chain ->
            val h = chain.request().url.host
            if (!isOnion(h)) throw IOException("Tor client refuses non-onion host $h")
            chain.proceed(chain.request())
        })
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    /** Is something listening on the proxy port? A local TCP connect, [timeoutMs] at most. */
    fun isAvailable(timeoutMs: Int = 400): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs); true }
    } catch (_: Exception) { false }

    /**
     * The page at [url] (a .onion URL only; anything else is refused with null, never sent) as
     * text, at most 600k chars; null when it failed or isn't HTML/text. Stop cancels the call.
     */
    suspend fun get(url: String): String? {
        val h = url.trim().toHttpUrlOrNull()?.host
        if (h == null || !isOnion(h)) return null
        return try {
            val call = client.newCall(Request.Builder().url(url.trim()).header("User-Agent", UA).header("Accept-Language", "en").build())
            cancelOnStop(call) { call.execute().use { r ->
                if (!r.isSuccessful) null else r.body?.let { b ->
                    val type = b.contentType()
                    if (type == null || type.subtype.contains("html") || type.type == "text") b.string().take(600_000) else null
                }
            } }
        } catch (e: CancellationException) { throw e      // Stop, not a failed page
        } catch (_: Exception) { null }
    }

    companion object {
        /** Orbot's (and the tor daemon's) loopback address. */
        const val HOST = "127.0.0.1"

        /**
         * Orbot's default SOCKS port (also the tor daemon's). A constant, not a setting: Orbot
         * uses it unless the owner changes it there. Tor Browser's own port (9150) is not used —
         * it's the browser's, not shared with other apps on Android.
         */
        const val PORT = 9050

        /** Tor Browser's user agent family: the same as many onion visitors, nothing that names Jarvis. */
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

        /** The app's one Tor client (Orbot's default port). */
        val shared: TorProxy by lazy { TorProxy() }

        fun isOnion(host: String): Boolean = host.lowercase().trimEnd('.').endsWith(".onion")

    }
}

/**
 * A [Dns] that refuses every .onion name: an onion address looked up by the phone's resolver
 * leaks where it's going and never resolves anyway. Everything else goes to [delegate].
 */
class OnionBlockingDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (TorProxy.isOnion(hostname)) throw UnknownHostException("refusing a local DNS lookup of an onion name")
        return delegate.lookup(hostname)
    }
}
