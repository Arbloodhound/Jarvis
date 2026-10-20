package com.agent.jarvis.harness.connectors

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpotifyConnectorTest {
    private class Fake(val routes: Map<String, HttpResp>) : HttpTransport {
        val seen = ArrayList<HttpReq>()
        override suspend fun send(req: HttpReq) = req.also { seen += it }.let { r ->
            routes.entries.firstOrNull { r.url.contains(it.key) }?.value ?: HttpResp(404, "")
        }
    }

    @Test fun pkceChallengeIsUnpaddedBase64UrlSha256() {
        // Expected value computed independently: base64url(sha256(verifier)) without padding (Python hashlib).
        assertEquals("3B0JJpUgiYMDYwqw54yXfVgkodetu6joCdkz_3IpVGw", SpotifyAuth.challenge("dBjftJeZ4CVP-mJ92K1RnaRumGBfwo9Ww4jJx3J89Ho"))
    }

    @Test fun readsRunPlaybackChangesAsk() = runBlocking {
        val v = InMemoryVault().also { it.store("spotify", "tok", SpotifyConnector.HOSTS) }
        val fake = Fake(mapOf("/me/player/currently-playing" to HttpResp(200,
            """{"is_playing":true,"item":{"name":"Song","artists":[{"name":"Band"}],"album":{"name":"LP"}}}"""),
            "/v1/me" to HttpResp(200, "{}")))
        val reg = ConnectorRegistry(object : GrantCheck {
            override suspend fun covered(level: String, tool: String, argsJson: String, channel: String?, account: String?, recipient: String?) = false
        })
        reg.register(SpotifyConnector(v, AuthedTransport(v, fake)))
        val r = reg.call("spotify", "now_playing", emptyMap())
        assertEquals("Playing: Song — Band (LP)", (r as CallResult.Ok).text)
        assertTrue(reg.call("spotify", "pause", emptyMap()) is CallResult.NeedsApproval)
        assertTrue(fake.seen.none { it.url.contains("/pause") })
    }

    @Test fun searchListsUris() {
        val o = JSONObject("""{"tracks":{"items":[{"name":"A","uri":"spotify:track:1","artists":[{"name":"X"}]}]}}""")
        assertEquals("- A — X (spotify:track:1)", SpotifyConnector.tracks(o))
    }
}
