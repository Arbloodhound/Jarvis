package com.agent.ultra.harness.connectors

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google sign-in for the four Google connectors, through Google's Android authorization API
 * (play-services-auth). No client ID string lives in the app: the owner registers an Android OAuth
 * client in Google Cloud for this package and its signing key, and Google matches the app by that.
 *
 * One consent covers Gmail, Calendar, Drive and Docs. Access tokens last about an hour; [refresh]
 * asks again silently before each connector call (no screen once consent was given). Tokens go into
 * the [CredentialVault] bound to each connector's own API hosts, so the agent never sees one.
 */
object GoogleAccess {
    val CONNECTORS = listOf("gmail", "google_calendar", "google_drive", "google_docs")

    private fun request(scopes: List<String>) =
        AuthorizationRequest.builder().setRequestedScopes(scopes.map { Scope(it) }).build()

    /** Asks Google; the result either holds a token or needs the owner's consent screen. */
    suspend fun authorize(ctx: Context, scopes: List<String>): AuthorizationResult = suspendCancellableCoroutine { c ->
        Identity.getAuthorizationClient(ctx).authorize(request(scopes))
            .addOnSuccessListener { c.resume(it) }
            .addOnFailureListener { c.resumeWithException(it) }
    }

    /** The result the consent screen hands back. */
    fun resultFrom(ctx: Context, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data)

    /** Stores [token] for each connector, bound to that connector's hosts (replacing an old one). */
    fun store(vault: CredentialVault, token: String, connectors: List<GoogleConnector>) {
        for (c in connectors) {
            vault.handleFor(c.id())?.let { vault.remove(it) }
            vault.store(c.id(), token, c.hosts)
        }
    }

    /**
     * Silent refresh before a call: a fresh token when consent was given, else false (the owner
     * connects in Settings). Never shows a screen.
     */
    suspend fun refresh(ctx: Context, vault: CredentialVault, connectors: List<GoogleConnector>): Boolean = try {
        val r = authorize(ctx, connectors.flatMap { it.scopes }.distinct())
        val token = r.accessToken
        if (r.hasResolution() || token.isNullOrBlank()) false else { store(vault, token, connectors); true }
    } catch (e: Exception) {
        android.util.Log.i("UltraConnect", "google refresh failed: ${e.javaClass.simpleName}")
        false
    }
}
