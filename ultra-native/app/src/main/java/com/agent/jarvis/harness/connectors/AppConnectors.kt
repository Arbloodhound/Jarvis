package com.agent.jarvis.harness.connectors

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
        val grants = ApprovalGrantsCheck(com.agent.jarvis.harness.ApprovalGrants(com.agent.jarvis.data.UltraDatabase.get(app).grants()))
        ConnectorRegistry(grants).also { r ->
            g.forEach(r::register); r.register(SpotifyConnector(v, transport)); registry = r; google = g; vault = v
        }
    }

    /** The account recipient grants are keyed to: the one signed-in Google account. */
    const val ACCOUNT = "me"

    private const val PREFS = "connector_grants"
    private const val K_RECIPIENTS = "gmail_recipients"

    /** Addresses the owner lets Jarvis email without asking each time (durable recipient grants). */
    fun trustedRecipients(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_RECIPIENTS, "").orEmpty()
            .lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** Sets the list: grants for new addresses, revokes for removed ones. Purchases never get grants. */
    suspend fun setTrustedRecipients(ctx: Context, addresses: List<String>) {
        val grants = com.agent.jarvis.harness.ApprovalGrants(com.agent.jarvis.data.UltraDatabase.get(ctx.applicationContext).grants())
        val old = trustedRecipients(ctx).toSet()
        val new = addresses.map { it.trim().lowercase() }.filter { '@' in it }.toSet()
        (old - new).forEach { grants.revokeRecipient("gmail", ACCOUNT, it) }
        (new - old).forEach { grants.grantRecipient("gmail", ACCOUNT, it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_RECIPIENTS, new.sorted().joinToString("\n")).apply()
    }

    fun google(ctx: Context): List<GoogleConnector> { registry(ctx); return google }
    fun vault(ctx: Context): CredentialVault { registry(ctx); return vault!! }

    /** Every Google scope the connectors ask for, as one consent. */
    fun googleScopes(ctx: Context): List<String> = google(ctx).flatMap { it.scopes }.distinct()
}
