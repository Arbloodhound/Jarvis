package com.agent.ultra.harness.connectors

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/** One HTTP exchange; the connector code is written against this so it is testable without a network. */
interface HttpTransport {
    suspend fun send(req: HttpReq): HttpResp
}

data class HttpReq(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String = "application/json",
)

data class HttpResp(val code: Int, val body: String)

/**
 * The network boundary for connectors. Requests carry a credential *handle* ([authHandle]); this
 * transport checks the request's host against the hosts the handle is bound to, refuses anything
 * else, and only then swaps the handle for the real bearer token. Plain http is refused.
 * The secret never appears in the request object the connector built, in logs, or in results.
 */
class AuthedTransport(private val vault: CredentialVault, private val inner: HttpTransport) {

    class HostNotAllowed(host: String) : Exception("credential is not bound to $host")

    suspend fun send(req: HttpReq, authHandle: String): HttpResp {
        val host = hostOf(req.url) ?: throw HostNotAllowed("(no host)")
        if (!req.url.startsWith("https://")) throw HostNotAllowed(host)
        if (host !in vault.hostsFor(authHandle)) throw HostNotAllowed(host)
        val token = vault.reveal(authHandle) ?: return HttpResp(401, "")
        return inner.send(req.copy(headers = req.headers + ("Authorization" to "Bearer $token")))
    }

    companion object {
        fun hostOf(url: String): String? =
            Regex("^https?://([^/:?#]+)").find(url)?.groupValues?.get(1)?.lowercase()
    }
}

/** OkHttp implementation. Stop cancels the call (same pattern as WebResearchRun). */
class OkHttpTransport(private val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient()) : HttpTransport {
    override suspend fun send(req: HttpReq): HttpResp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val body = req.body?.toRequestBody(req.contentType.toMediaTypeOrNull())
        val r = okhttp3.Request.Builder().url(req.url).method(req.method, body)
        req.headers.forEach { (k, v) -> r.header(k, v) }
        val call = client.newCall(r.build())
        com.agent.ultra.net.cancelOnStop(call) {
            call.execute().use { HttpResp(it.code, it.body?.string().orEmpty()) }
        }
    }
}
