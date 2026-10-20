package com.agent.ultra.harness.connectors

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify sign-in with Authorization Code + PKCE: the owner's Spotify app client ID (Settings), no
 * client secret anywhere. The browser returns to [REDIRECT] (register it in the Spotify app), which
 * [SpotifyRedirectActivity] receives; the code is exchanged here. The refresh token is kept in the
 * vault bound to accounts.spotify.com, the access token bound to api.spotify.com.
 */
object SpotifyAuth {
    const val REDIRECT = "com.agent.ultra://spotify-callback"
    private const val AUTH = "https://accounts.spotify.com/authorize"
    private const val TOKEN = "https://accounts.spotify.com/api/token"
    private const val REFRESH_ID = "spotify_refresh"
    private val SCOPES = listOf("user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing")
    private const val PREFS = "spotify_auth"

    fun clientId(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("client_id", "").orEmpty()
    fun setClientId(ctx: Context, id: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("client_id", id.trim()).apply()

    /** Pure. PKCE: the S256 challenge for [verifier] (RFC 7636). */
    fun challenge(verifier: String): String = android.util.Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)

    /** Opens Spotify's consent page in the browser. False when no client ID is set. */
    fun start(ctx: Context): Boolean {
        val id = clientId(ctx).ifBlank { return false }
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val state = java.util.UUID.randomUUID().toString()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("verifier", verifier).putString("state", state).apply()
        val url = Uri.parse(AUTH).buildUpon()
            .appendQueryParameter("client_id", id).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge(verifier)).appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES.joinToString(" ")).build()
        ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** The redirect: checks state, exchanges the code, stores the tokens. A line for the owner. */
    suspend fun finish(ctx: Context, data: Uri): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        data.getQueryParameter("error")?.let { return "Spotify said: $it. Nothing was connected." }
        if (data.getQueryParameter("state") != prefs.getString("state", null)) return "That sign-in didn't come from this phone's request. Nothing was connected."
        val code = data.getQueryParameter("code") ?: return "Spotify sent no code. Nothing was connected."
        val verifier = prefs.getString("verifier", null) ?: return "The sign-in expired. Try again."
        prefs.edit().remove("verifier").remove("state").apply()
        return if (tokenRequest(ctx, "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT)}" +
                "&client_id=${enc(clientId(ctx))}&code_verifier=${enc(verifier)}")) "Spotify connected." else "Spotify refused the sign-in. Nothing was connected."
    }

    /** Silent refresh before a call; false when there is nothing to refresh with. */
    suspend fun refresh(ctx: Context): Boolean {
        val vault = AppConnectors.vault(ctx)
        val rt = vault.handleFor(REFRESH_ID)?.let(vault::reveal) ?: return false
        return tokenRequest(ctx, "grant_type=refresh_token&refresh_token=${enc(rt)}&client_id=${enc(clientId(ctx))}")
    }

    fun disconnect(ctx: Context) {
        val v = AppConnectors.vault(ctx)
        listOf(SpotifyConnector.ID, REFRESH_ID).forEach { id -> v.handleFor(id)?.let(v::remove) }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun tokenRequest(ctx: Context, form: String): Boolean {
        val r = OkHttpTransport().send(HttpReq("POST", TOKEN, body = form, contentType = "application/x-www-form-urlencoded"))
        if (r.code !in 200..299) return false
        val o = org.json.JSONObject(r.body)
        val vault = AppConnectors.vault(ctx)
        vault.store(SpotifyConnector.ID, o.getString("access_token"), SpotifyConnector.HOSTS)
        o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { vault.store(REFRESH_ID, it, setOf("accounts.spotify.com")) }
        return true
    }
}
