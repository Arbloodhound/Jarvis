package com.agent.ultra.harness.connectors

import android.content.Context

/**
 * The app's one connector registry (HANDOFF_V5 §1): the four Google connectors, the Keystore vault
 * their tokens live in, and the durable approval grants the registry checks before a write or send.
 */
object AppConnectors {
    @Volatile private var registry: ConnectorRegistry? = null
    @Volatile private var google: List<GoogleConnector> = emptyList()
    @Volatile private var vault: CredentialVault? = null

    @Synchronized fun registry(ctx: Context): ConnectorRegistry = registry ?: run {
        val app = ctx.applicationContext
        val v = KeystoreVault(app)
        val transport = AuthedTransport(v, OkHttpTransport())
        // No client ID string: the Android authorization API matches the app by package and key.
        val oauth = GoogleOAuth("android")
        val g = listOf(GmailConnector(v, transport, oauth), GoogleCalendarConnector(v, transport, oauth),
            GoogleDriveConnector(v, transport, oauth), GoogleDocsConnector(v, transport, oauth))
        val grants = ApprovalGrantsCheck(com.agent.ultra.harness.ApprovalGrants(com.agent.ultra.data.UltraDatabase.get(app).grants()))
        ConnectorRegistry(grants).also { r -> g.forEach(r::register); registry = r; google = g; vault = v }
    }

    fun google(ctx: Context): List<GoogleConnector> { registry(ctx); return google }
    fun vault(ctx: Context): CredentialVault { registry(ctx); return vault!! }

    /** Every Google scope the connectors ask for, as one consent. */
    fun googleScopes(ctx: Context): List<String> = google(ctx).flatMap { it.scopes }.distinct()
}
