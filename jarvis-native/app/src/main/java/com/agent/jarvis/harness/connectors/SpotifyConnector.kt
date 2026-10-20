package com.agent.jarvis.harness.connectors

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Spotify (HANDOFF_V5 §1, "only if the user connects it"): what's playing, search, and playback
 * control through the Web API. Reads run at once; playback changes are writes, so the registry asks
 * the owner first. Playback endpoints need Spotify Premium — Spotify says so in its error, passed on.
 * The token comes from [SpotifyAuth] (PKCE sign-in, no client secret) and lives in the vault bound
 * to api.spotify.com only.
 */
class SpotifyConnector(private val vault: CredentialVault, private val transport: AuthedTransport) : Connector {
    override fun id() = ID
    override fun isConnected() = vault.handleFor(ID) != null
    override fun connect(): ConnectFlow = ConnectFlow.NeedsSetup(
        "Connect Spotify in Settings → CONNECTORS (it needs your Spotify app's client ID).")

    override suspend fun status(): ConnectorStatus {
        val h = vault.handleFor(ID) ?: return ConnectorStatus(ConnectorState.NOT_CONNECTED)
        return when (val c = transport.send(HttpReq("GET", "$API/me"), h).code) {
            in 200..299 -> ConnectorStatus(ConnectorState.CONNECTED)
            401 -> ConnectorStatus(ConnectorState.EXPIRED, "sign-in expired")
            else -> ConnectorStatus(ConnectorState.ERROR, "HTTP $c")
        }
    }

    override fun operations() = listOf(
        ConnectorOp("now_playing", OpEffect.READ, "what's playing now"),
        ConnectorOp("search", OpEffect.READ, "search tracks", listOf("query")),
        ConnectorOp("play", OpEffect.WRITE, "play a track (uri from search) or resume"),
        ConnectorOp("pause", OpEffect.WRITE, "pause playback"),
        ConnectorOp("next", OpEffect.WRITE, "skip to the next track"),
        ConnectorOp("queue", OpEffect.WRITE, "add a track to the queue", listOf("uri")),
    )

    override suspend fun run(op: ConnectorOp, args: Map<String, String>): String {
        val h = vault.handleFor(ID) ?: throw IllegalStateException("not connected")
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return when (op.name) {
            "now_playing" -> transport.send(HttpReq("GET", "$API/me/player/currently-playing"), h).let { r ->
                if (r.code == 204 || r.body.isBlank()) "Nothing is playing." else nowPlaying(JSONObject(check(r)))
            }
            "search" -> tracks(JSONObject(check(transport.send(HttpReq("GET", "$API/search?type=track&limit=5&q=${enc(args.getValue("query"))}"), h))))
            "play" -> { check(transport.send(HttpReq("PUT", "$API/me/player/play",
                body = args["uri"]?.takeIf { it.isNotBlank() }?.let { JSONObject().put("uris", org.json.JSONArray().put(it)).toString() } ?: "{}"), h)); "Playing." }
            "pause" -> { check(transport.send(HttpReq("PUT", "$API/me/player/pause", body = ""), h)); "Paused." }
            "next" -> { check(transport.send(HttpReq("POST", "$API/me/player/next", body = ""), h)); "Skipped." }
            "queue" -> { check(transport.send(HttpReq("POST", "$API/me/player/queue?uri=${enc(args.getValue("uri"))}", body = ""), h)); "Added to the queue." }
            else -> throw IllegalArgumentException("no op ${op.name}")
        }
    }

    private fun check(r: HttpResp): String {
        if (r.code in 200..299) return r.body
        val msg = runCatching { JSONObject(r.body).getJSONObject("error").getString("message") }.getOrNull()
        throw IllegalStateException("HTTP ${r.code}" + (msg?.let { ": $it" } ?: ""))
    }

    companion object {
        const val ID = "spotify"
        const val API = "https://api.spotify.com/v1"
        val HOSTS = setOf("api.spotify.com")

        /** Pure. "Song — Artist (album)" from a currently-playing body. */
        fun nowPlaying(o: JSONObject): String {
            val item = o.optJSONObject("item") ?: return "Nothing is playing."
            val artists = item.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString { a.getJSONObject(it).optString("name") } }.orEmpty()
            return "${if (o.optBoolean("is_playing")) "Playing" else "Paused"}: ${item.optString("name")} — $artists (${item.optJSONObject("album")?.optString("name").orEmpty()})"
        }

        /** Pure. One line per track with its uri, for play/queue. */
        fun tracks(o: JSONObject): String {
            val items = o.optJSONObject("tracks")?.optJSONArray("items") ?: return "No tracks found."
            if (items.length() == 0) return "No tracks found."
            return (0 until items.length()).joinToString("\n") { i ->
                val t = items.getJSONObject(i)
                val artists = t.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString { a.getJSONObject(it).optString("name") } }.orEmpty()
                "- ${t.optString("name")} — $artists (${t.optString("uri")})"
            }
        }
    }
}
